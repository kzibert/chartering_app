package com.chartering.service.feed;

import com.chartering.config.FeedProperties;
import com.chartering.dto.FeedItemResponse;
import com.chartering.dto.FeedParserResponse;
import com.chartering.dto.FeedStatusResponse;
import com.chartering.dto.FeedSummaryResponse;
import com.chartering.dto.PageResponse;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSummary;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSourceRepository;
import com.chartering.repository.FeedSummaryRepository;
import com.chartering.repository.FeedTopicRepository;
import com.chartering.specification.FeedItemSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The Feed's reads: status, items, summaries, parsers. All of it answers on every deployment. */
@Service
@RequiredArgsConstructor
public class FeedQueryService {

    private final FeedProperties props;
    private final FeedSourceRepository sources;
    private final FeedItemRepository items;
    private final FeedTopicRepository topics;
    private final FeedSummaryRepository summaries;
    private final FeedFetchService fetch;
    private final FeedSummaryService summary;
    private final FeedLlmClient llm;
    private final WebsiteReader websites;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public FeedStatusResponse status() {
        List<FeedSource> all = sources.findAll();
        long enabled = all.stream().filter(FeedSource::isEnabled).count();
        long topicCount = topics.count();
        long selected = topics.findBySelectedTrueOrderBySortOrderAscIdAsc().size();
        boolean analysis = props.isAnalysisEnabled();
        Boolean reachable = analysis ? llm.isReachable() : null;

        List<String> warnings = new ArrayList<>();
        if (analysis && !reachable) {
            warnings.add("The model is not answering at " + llm.modelUrl()
                    + ". Start it in chartering-ml: make serve-docker.");
        }
        if (all.isEmpty()) warnings.add("No sources yet — add a Telegram channel, a feed or a site on the Sources tab.");
        if (topicCount > 0 && selected == 0) warnings.add("No topic is selected, so Summarise has nothing to run.");

        var fetchReport = fetch.lastReport();
        var progress = summary.progress();
        var runReport = summary.lastReport();
        return new FeedStatusResponse(analysis, reachable, analysis ? llm.modelUrl() : null,
                all.size(), enabled, items.count(), topicCount, selected,
                fetch.isRunning(), analysis ? fetch.lastFetchAt() : null, fetch.nextFetchAt(),
                fetchReport == null ? null : fetchReport.message(),
                summary.isRunning(),
                progress == null ? null : progress.topicIndex(),
                progress == null ? null : progress.topicCount(),
                progress == null ? null : progress.topicName(),
                progress == null ? null : progress.stage(),
                runReport == null ? null : runReport.message(),
                runReport == null ? null : runReport.finishedAt(),
                warnings);
    }

    @Transactional(readOnly = true)
    public FeedItemResponse item(Long id) {
        return items.findById(id)
                .map(mapper::toFeedItemResponse)
                .orElseThrow(() -> new com.chartering.exception.ResourceNotFoundException(
                        "Feed item", id));
    }

    @Transactional(readOnly = true)
    public PageResponse<FeedItemResponse> items(Long sourceId, String q, LocalDate from, LocalDate to, int page, int size) {
        Page<FeedItemResponse> result = items.findAll(FeedItemSpecification.filter(sourceId, q, from, to),
                        PageRequest.of(page, Math.min(Math.max(size, 1), 200)))
                .map(mapper::toFeedItemResponse);
        return PageResponse.from(result);
    }

    @Transactional(readOnly = true)
    public PageResponse<FeedSummaryResponse> summaries(Long topicId, int page, int size) {
        PageRequest request = PageRequest.of(page, Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<FeedSummary> result = topicId == null ? summaries.findAll(request) : summaries.findByTopicId(topicId, request);
        return PageResponse.from(result.map(s -> mapper.toFeedSummaryResponse(s, false)));
    }

    @Transactional(readOnly = true)
    public FeedSummaryResponse summary(Long id) {
        return summaries.findWithItems(id).map(s -> mapper.toFeedSummaryResponse(s, true))
                .orElseThrow(() -> new ResourceNotFoundException("Feed summary", id));
    }

    public List<FeedParserResponse> parsers() {
        return websites.all().stream().map(p -> new FeedParserResponse(p.key(), p.label(), p.exampleUrl())).toList();
    }

    /** The window the model server reports, for the Settings card's Detect button. Local only. */
    public Integer detectContextWindow() {
        if (!props.isAnalysisEnabled()) {
            throw new FeatureDisabledException("There is no model on this deployment (FEED_ANALYSIS_ENABLED).");
        }
        Integer window = llm.contextWindow();
        if (window == null) {
            throw new IllegalStateException("The model server at " + llm.modelUrl() + " did not report its context size.");
        }
        return window;
    }
}
