package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.model.ParseStatus;
import com.chartering.model.ParsedEmail;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.ParsedEmailRepository;
import com.chartering.service.ParserSettings;
import com.chartering.service.lookup.VesselLookupService;
import com.chartering.tenancy.TenantContext;
import com.chartering.tenancy.TenantDirectory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParserSweepServiceTest {

    /**
     * "Parse selected" reads what nobody has read yet and leaves the rest alone: a message
     * already PARSED or SKIPPED is not sent to the model again, while one with no row or with
     * a FAILED row is - a failure is exactly what a person picks again to retry. The runner
     * answers null for the two it is asked for, which is what it does for nothing to read, and
     * the sweep counts that as skipped too.
     */
    @Test
    void shouldReadOnlyWhatIsNotAlreadyRead_whenParsingSelected() throws InterruptedException {
        ParserProperties props = new ParserProperties();
        props.setEnabled(true);
        ParserAvailability availability = new ParserAvailability(props);

        ParsedEmailRepository parsedEmails = mock(ParsedEmailRepository.class);
        when(parsedEmails.findByMailMessageId(1L)).thenReturn(Optional.of(row(ParseStatus.PARSED)));
        when(parsedEmails.findByMailMessageId(2L)).thenReturn(Optional.empty());
        when(parsedEmails.findByMailMessageId(3L)).thenReturn(Optional.of(row(ParseStatus.FAILED)));
        EmailParseRunner runner = mock(EmailParseRunner.class);

        ParserSweepService sweeps = new ParserSweepService(
                availability,
                props,
                mock(ParserSettings.class),
                parsedEmails,
                mock(FeedItemRepository.class),
                runner,
                mock(VesselLookupService.class),
                mock(TenantDirectory.class));

        TenantContext.runAs(1L, () -> sweeps.requestParse(List.of(1L, 2L, 3L, 2L)));
        awaitReport(sweeps);

        verify(runner, never()).parseOne(1L);
        verify(runner).parseOne(2L);
        verify(runner).parseOne(3L);
        // Id 2 is de-duplicated, so the run is over three ids: one already read, two sent on
        // and answered with nothing to read.
        TenantContext.runAs(1L, () -> assertThat(sweeps.lastReport().skipped()).isEqualTo(3));
        TenantContext.runAs(1L, () -> assertThat(sweeps.lastReport().mode()).isEqualTo("SELECTED"));
    }

    private static ParsedEmail row(ParseStatus status) {
        ParsedEmail row = new ParsedEmail();
        row.setStatus(status);
        return row;
    }

    /** The sweep runs on its own worker; wait for this desk's report to land. */
    private static void awaitReport(ParserSweepService sweeps) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (TenantContext.callAs(1L, sweeps::lastReport) == null) {
            if (System.currentTimeMillis() > deadline) fail("The selected run did not finish.");
            Thread.sleep(10);
        }
    }
}
