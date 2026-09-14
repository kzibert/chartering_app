package com.chartering.service;

import com.chartering.model.Vessel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Invented hulls, sized like the fleet's. */
class VesselCapacityServiceTest {

    private static Vessel vessel(String dwt, String dwcc, String grain, String bale) {
        Vessel v = new Vessel();
        v.setName("EXAMPLE STAR");
        v.setDeadweightTonnage(dwt == null ? null : new BigDecimal(dwt));
        v.setDeadweightCargoCapacity(dwcc == null ? null : new BigDecimal(dwcc));
        v.setGrainCapacityM3(grain == null ? null : new BigDecimal(grain));
        v.setBaleCapacityM3(bale == null ? null : new BigDecimal(bale));
        return v;
    }

    @Test
    void figuresThatFitInCubicMetresAreNotReported() {
        assertThat(VesselCapacityService.classify(vessel("28000", null, "37000", "35500"))).isEmpty();
    }

    @Test
    void cubicFeetAreConvertedToWholeCubicMetres() {
        List<VesselCapacityService.Finding> found =
                VesselCapacityService.classify(vessel("38000", null, "1628000", "35500"));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).field()).isEqualTo("grainCapacityM3");
        assertThat(found.get(0).isCubicFeet()).isTrue();
        assertThat(found.get(0).cubicMetres()).isEqualByComparingTo("46100");
    }

    @Test
    void aFigureNoUnitExplainsIsReportedButNotConverted() {
        List<VesselCapacityService.Finding> found =
                VesselCapacityService.classify(vessel("9000", null, "340", null));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.isCubicFeet()).isFalse();
            assertThat(f.perTonne()).isEqualByComparingTo("0.04");
        });
    }

    @Test
    void cargoDeadweightStandsInAndAShipWithNoSizeSaysNothing() {
        assertThat(VesselCapacityService.classify(vessel("0", "3000", "112000", null)))
                .singleElement().satisfies(f -> assertThat(f.isCubicFeet()).isTrue());
        assertThat(VesselCapacityService.classify(vessel(null, null, "1628000", null))).isEmpty();
    }
}
