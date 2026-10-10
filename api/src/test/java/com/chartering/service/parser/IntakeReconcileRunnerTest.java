package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.config.VesselLookupProperties;
import com.chartering.tenancy.TenantDirectory;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class IntakeReconcileRunnerTest {

    /**
     * The waiting questions are weighed on the first tick and then every half hour, while the
     * senders are still asked after on every tick: re-weighing is what an idle installation
     * spent its database transfer on.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reweighsOnTheFirstTickAndNotOnTheNext() {
        IntakeService intake = mock(IntakeService.class);
        ParserProperties parser = new ParserProperties();
        parser.setEnabled(true);
        VesselLookupProperties lookups = new VesselLookupProperties();
        lookups.setEnabled(false);
        TenantDirectory tenants = mock(TenantDirectory.class);
        doAnswer(inv -> {
            ((Consumer<Long>) inv.getArgument(1)).accept(1L);
            return null;
        }).when(tenants).forEachActive(anyString(), any());

        IntakeReconcileRunner runner = new IntakeReconcileRunner(intake, parser, lookups, tenants);
        runner.run();
        runner.run();

        verify(intake, times(1)).reweighPending();
        verify(intake, times(2)).attributeUnreported();
    }
}
