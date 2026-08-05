package com.chartering.service;

import com.chartering.config.MailCampaignProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The campaign audit trail, kept as a plain text file rather than in the database.
 *
 * <p>Retention rule: a new run overwrites the log <em>only</em> if the previous run
 * finished cleanly. A run that failed, aborted or was interrupted is rotated aside to a
 * timestamped file first — those are exactly the ones you need afterwards to work out who
 * already received the circular before things went wrong.
 *
 * <p>The outcome of the previous run is recovered from the log's own end marker rather
 * than from memory, so the rule still holds across an API restart.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignLogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String END_MARKER = "=== CAMPAIGN END";
    private static final String CLEAN_OUTCOME = "status=COMPLETED";
    private static final String NO_FAILURES = "failed=0";

    private final MailCampaignProperties props;

    private Path path() {
        return Paths.get(props.getLogFile()).toAbsolutePath();
    }

    /**
     * Begin a fresh log for a new run, preserving the previous one unless it ended clean.
     *
     * @return a note about what happened to the previous log, for the new log's header
     */
    public synchronized String beginRun(String subject, int recipientCount, long minDelayMs, long maxDelayMs) {
        Path p = path();
        String carriedOver = "no previous log";
        try {
            Files.createDirectories(p.getParent());
            if (Files.exists(p)) {
                if (previousRunWasClean(p)) {
                    carriedOver = "previous run finished clean — log overwritten";
                } else {
                    Path rotated = rotate(p);
                    carriedOver = "previous run did not finish clean — preserved as " + rotated.getFileName();
                }
            }
            String header = """
                    === CAMPAIGN START %s ===
                    subject      : %s
                    recipients   : %d
                    pacing       : one message per recipient, random %.1f-%.1fs apart
                    previous log : %s
                    ---------------------------------------------------------------
                    """.formatted(LocalDateTime.now().format(TS), subject, recipientCount,
                    minDelayMs / 1000.0, maxDelayMs / 1000.0, carriedOver);
            Files.writeString(p, header, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the campaign log at " + p, e);
        }
        return carriedOver;
    }

    /** Append one timestamped line. Never throws — losing a log line must not kill a send. */
    public synchronized void append(String line) {
        try {
            Files.writeString(path(), LocalDateTime.now().format(TS) + "  " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not append to the campaign log: {}", e.getMessage());
        }
    }

    /**
     * Write the end marker. {@code status} plus the failure count is what
     * {@link #previousRunWasClean} reads back to decide whether the next run may overwrite.
     */
    public synchronized void endRun(String status, int sent, int failed, int skipped) {
        append("---------------------------------------------------------------");
        try {
            String marker = "%s status=%s sent=%d failed=%d skipped=%d at=%s ===%s".formatted(
                    END_MARKER, status, sent, failed, skipped,
                    LocalDateTime.now().format(TS), System.lineSeparator());
            Files.writeString(path(), marker, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Could not write the campaign end marker: {}", e.getMessage());
        }
    }

    /** Whole log, or empty string if there isn't one yet. */
    public synchronized String read() {
        Path p = path();
        if (!Files.exists(p)) {
            return "";
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Could not read the campaign log: " + e.getMessage();
        }
    }

    /** True when the log on disk ends with a clean, zero-failure end marker. */
    private boolean previousRunWasClean(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                return line.startsWith(END_MARKER) && line.contains(CLEAN_OUTCOME) && line.contains(NO_FAILURES);
            }
            return true; // empty file — nothing worth keeping
        } catch (IOException e) {
            log.warn("Could not inspect the previous campaign log, keeping it to be safe: {}", e.getMessage());
            return false;
        }
    }

    private Path rotate(Path p) throws IOException {
        String name = p.getFileName().toString().replaceFirst("\\.log$", "");
        Path target = p.resolveSibling("%s-%s.log".formatted(name, LocalDateTime.now().format(FILE_TS)));
        Files.move(p, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
