package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.FeedItem;
import com.chartering.model.FeedSummaryStrategy;
import com.chartering.model.FeedTopic;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedTopicRepository;
import com.chartering.service.feed.FeedItemSelector.Candidate;
import com.chartering.service.feed.FeedLlmClient.Completion;
import com.chartering.service.feed.FeedLlmClient.ModelUnavailableException;
import com.chartering.service.parser.ParserSweepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Summarise": one summary per selected topic, written by the local model from the collected
 * items and made to fit its window.
 *
 * <p>Manual only, by decision — GPU time is spent when somebody asks. One worker, one flag, the
 * button returns at once and the tab watches {@link #progress()}.
 *
 * <p><b>Not while the email parser is reading.</b> Both use one llama-server, whose slots share
 * one KV cache: a 7,000-token position list and a 7,000-token batch of feed items at the same
 * moment do not both fit in 8,192, and the parser is the one whose failure writes a FAILED row
 * against somebody's circular. The refusal says so rather than queuing behind a sweep that may
 * run for minutes.
 *
 * <p><b>A model that stops answering stops the run</b>, for the sweep's reason: every topic after
 * it would spend a timeout finding out the same thing. Anything else fails that topic alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeedSummaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final String NOTHING_RELEVANT = "- nothing relevant";

    private final FeedProperties props;
    private final FeedSettings settings;
    private final FeedTopicRepository topics;
    private final FeedItemRepository items;
    private final FeedLlmClient llm;
    private final FeedSummaryRecorder recorder;
    private final ParserSweepService parserSweep;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Progress progress;
    private volatile RunReport lastReport;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feed-summary");
        t.setDaemon(true);
        return t;
    });

    public record Progress(UUID runId, int topicIndex, int topicCount, String topicName, String stage,
                           OffsetDateTime startedAt) {
    }

    public record RunReport(UUID runId, int topics, int done, int failed, String message,
                            OffsetDateTime finishedAt) {
    }

    public boolean isRunning() {
        return running.get();
    }

    public Progress progress() {
        return progress;
    }

    public RunReport lastReport() {
        return lastReport;
    }

    /** Runs the selected topics. Returns at once; refused while the parser sweep is reading. */
    public void requestRun() {
        if (!props.isAnalysisEnabled()) {
            throw new FeatureDisabledException(
                    "Summarising is not enabled on this deployment (FEED_ANALYSIS_ENABLED).");
        }
        if (llm.sharesServerWithParser() && parserSweep.isRunning()) {
            throw new IllegalStateException("The email parser is reading mail on the same model right now. "
                    + "Summarise again when that sweep has finished.");
        }
        if (topics.findBySelectedTrueOrderBySortOrderAscIdAsc().isEmpty()) {
            throw new IllegalArgumentException("No topics are selected. Tick at least one on the Topics tab.");
        }
        if (running.get()) return;
        worker.submit(this::runIfIdle);
    }

    private void runIfIdle() {
        if (!running.compareAndSet(false, true)) return;
        UUID runId = UUID.randomUUID();
        try {
            lastReport = run(runId);
        } catch (Exception e) {
            log.error("Feed summary run failed", e);
            lastReport = new RunReport(runId, 0, 0, 0, "The run failed: " + e.getMessage(), OffsetDateTime.now());
        } finally {
            progress = null;
            running.set(false);
        }
    }

    private RunReport run(UUID runId) {
        FeedSettings.Values values = settings.values();
        List<FeedTopic> selected = topics.findBySelectedTrueOrderBySortOrderAscIdAsc();
        LocalDateTime now = LocalDateTime.now();
        // Loaded once for the run: every topic reads the same collection, and a fetch landing
        // mid-run must not give the second topic material the first never saw.
        List<FeedItem> pool = items.findForSummary(now.minusDays(values.lookbackDays()));
        OffsetDateTime started = OffsetDateTime.now();

        int done = 0;
        int failed = 0;
        String stopped = null;
        for (int i = 0; i < selected.size(); i++) {
            FeedTopic topic = selected.get(i);
            progress = new Progress(runId, i + 1, selected.size(), topic.getName(), "selecting items", started);
            Long summaryId = recorder.start(runId, topic.getId(), topic.getName(), values.contextWindowTokens());
            TopicRun tr = new TopicRun(runId, i + 1, selected.size(), topic, values, now, started);
            try {
                recorder.finish(summaryId, tr.execute(pool));
                done++;
            } catch (ModelUnavailableException e) {
                failed++;
                recorder.fail(summaryId, e.getMessage(), tr.calls, tr.elapsed(), tr.systemPrompt);
                stopped = e.getMessage();
                break;
            } catch (Exception e) {
                failed++;
                log.warn("Topic '{}' could not be summarised", topic.getName(), e);
                recorder.fail(summaryId, e.getMessage() == null ? e.toString() : e.getMessage(),
                        tr.calls, tr.elapsed(), tr.systemPrompt);
            }
        }
        String message = stopped != null
                ? "Stopped after %d of %d topic(s): %s".formatted(done + failed, selected.size(), stopped)
                : "%d topic(s) summarised%s.".formatted(done, failed > 0 ? ", " + failed + " failed" : "");
        return new RunReport(runId, selected.size(), done, failed, message, OffsetDateTime.now());
    }

    /** One topic's pass through select, budget, plan and the model calls. */
    private final class TopicRun {
        private final UUID runId;
        private final int index;
        private final int count;
        private final FeedTopic topic;
        private final FeedSettings.Values values;
        private final LocalDateTime now;
        private final OffsetDateTime runStarted;
        private final long started = System.currentTimeMillis();

        int calls;
        int promptTokens;
        int completionTokens;
        String model;
        String systemPrompt;

        TopicRun(UUID runId, int index, int count, FeedTopic topic, FeedSettings.Values values,
                 LocalDateTime now, OffsetDateTime runStarted) {
            this.runId = runId;
            this.index = index;
            this.count = count;
            this.topic = topic;
            this.values = values;
            this.now = now;
            this.runStarted = runStarted;
        }

        int elapsed() {
            return (int) (System.currentTimeMillis() - started);
        }

        FeedSummaryRecorder.Outcome execute(List<FeedItem> pool) {
            List<String> keywords = topic.keywordList();
            var selection = FeedItemSelector.select(pool, FeedItemSelector.terms(topic.getName(), keywords), now);
            List<Candidate> ranked = selection.ranked();
            String period = period(ranked);
            systemPrompt = FeedPrompts.render(values.systemPrompt(), topic.getName(), keywords, LocalDate.now(), period);
            String notesPrompt = FeedPrompts.render(values.notesPrompt(), topic.getName(), keywords, LocalDate.now(), period);

            if (ranked.isEmpty()) {
                String content = "Nothing collected in the last %d day(s) mentions this topic%s."
                        .formatted(values.lookbackDays(), keywords.isEmpty() ? "" : " (" + String.join(", ", keywords) + ")");
                return outcome(content, null, 0, selection.considered(), List.of(), 0);
            }

            stage("planning");
            var budget = new SummaryPlanner.Budget(values.contextWindowTokens(), llm.count(systemPrompt),
                    llm.count(notesPrompt), values.summaryMaxTokens(), values.notesMaxTokens(),
                    values.maxCallsPerTopic());
            SummaryPlanner.Plan plan = SummaryPlanner.plan(ranked, budget, llm);
            if (plan.used().isEmpty()) {
                throw new IllegalArgumentException("Not even the best item fits the window beside the prompt. "
                        + "Raise the context window or shorten the prompt.");
            }

            String content;
            int levels = 0;
            if (plan.strategy() == FeedSummaryStrategy.SINGLE_PASS) {
                stage("writing the summary");
                content = call(systemPrompt, "Source material:\n\n"
                        + String.join(SummaryPlanner.SEPARATOR, plan.singlePassMaterial()), values.summaryMaxTokens());
            } else {
                List<String> notes = new ArrayList<>();
                for (int b = 0; b < plan.batches().size(); b++) {
                    stage("reading batch %d of %d".formatted(b + 1, plan.batches().size()));
                    String n = call(notesPrompt, "Source material:\n\n" + plan.batches().get(b), values.notesMaxTokens());
                    if (!n.isBlank() && !n.strip().equalsIgnoreCase(NOTHING_RELEVANT)) notes.add(n);
                }
                if (notes.isEmpty()) {
                    return outcome("The items that matched this topic's keywords held nothing the model found relevant to it.",
                            FeedSummaryStrategy.MAP_REDUCE, 0, selection.considered(), plan.used(), plan.dropped());
                }
                while (llm.count(String.join("\n\n", notes)) > budget.forSummary()) {
                    levels++;
                    List<String> groups = SummaryPlanner.pack(notes, budget.forNotes(), llm);
                    // Condensing that makes no progress would loop for ever; so would a cap already spent.
                    if (groups.size() >= notes.size() || calls + groups.size() + 1 > values.maxCallsPerTopic()) {
                        notes = trimToFit(notes, budget.forSummary());
                        break;
                    }
                    List<String> condensed = new ArrayList<>();
                    for (int g = 0; g < groups.size(); g++) {
                        stage("condensing notes, level %d (%d of %d)".formatted(levels, g + 1, groups.size()));
                        condensed.add(call(notesPrompt, "Notes to condense into one list, keeping every figure:\n\n"
                                + groups.get(g), values.notesMaxTokens()));
                    }
                    notes = condensed;
                }
                stage("writing the summary");
                content = call(systemPrompt, "Notes taken from the source material:\n\n"
                        + String.join("\n\n", notes), values.summaryMaxTokens());
            }

            List<String> unverified = FigureCheck.unverified(content, plan.used().stream().map(Candidate::block).toList());
            if (!unverified.isEmpty()) {
                content += "\n\nNot found in the items this was written from — check before use: "
                        + String.join(", ", unverified) + ".";
            }
            if (plan.dropped() > 0) {
                content += "\n\n(%d further matching item(s) were not read: the limit of %d model calls per topic was reached.)"
                        .formatted(plan.dropped(), values.maxCallsPerTopic());
            }
            return outcome(content, plan.strategy(), levels, selection.considered(), plan.used(), plan.dropped());
        }

        private String call(String system, String user, int maxTokens) {
            Completion c = llm.chat(system, user, maxTokens);
            calls++;
            promptTokens += c.promptTokens();
            completionTokens += c.completionTokens();
            if (c.model() != null) model = c.model();
            return c.content();
        }

        /** Only reached when condensing stalls or the cap is spent: keep the notes that fit, in order. */
        private List<String> trimToFit(List<String> notes, int available) {
            List<String> kept = new ArrayList<>();
            for (String n : notes) {
                kept.add(n);
                if (llm.count(String.join("\n\n", kept)) > available) {
                    kept.remove(kept.size() - 1);
                    break;
                }
            }
            return kept;
        }

        private void stage(String stage) {
            progress = new Progress(runId, index, count, topic.getName(), stage, runStarted);
        }

        private FeedSummaryRecorder.Outcome outcome(String content, FeedSummaryStrategy strategy, int levels,
                                                    int considered, List<Candidate> used, int dropped) {
            LocalDateTime from = used.stream().map(Candidate::when).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime to = used.stream().map(Candidate::when).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            return new FeedSummaryRecorder.Outcome(content, strategy, levels, calls, considered, used.size(), dropped,
                    promptTokens, completionTokens, from, to, systemPrompt, model, elapsed(),
                    used.stream().map(Candidate::itemId).toList());
        }

        private String period(List<Candidate> ranked) {
            LocalDateTime from = ranked.stream().map(Candidate::when).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime to = ranked.stream().map(Candidate::when).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            if (from == null) return "the last " + values.lookbackDays() + " days";
            return from.format(DAY) + " to " + to.format(DAY);
        }
    }
}
