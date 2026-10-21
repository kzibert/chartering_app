package com.chartering.it;

import com.chartering.dto.CargoResponse;
import com.chartering.model.Cargo;
import com.chartering.repository.CargoRepository;
import com.chartering.service.CargoService;
import com.chartering.service.CargoService.CargoFilter;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The commission figure read out of a broker's words, and what the Cargoes tab does with it.
 *
 * <p>The formula is the part that can fail silently: Hibernate rewrites a {@code @Formula}
 * before it reaches Postgres, so a regular expression that is correct in Postgres can still
 * come back null through the entity. Every case is therefore read back through a fresh load of
 * the entity, not computed in Java. Each test invents its own commodity tag, so the search
 * results contain only the cargoes that test created.
 */
class CargoCommissionIntegrationTest extends IntegrationTest {

    /** The desk the migrations seed (V30). */
    private static final long DESK = 1L;

    @Autowired
    private CargoRepository cargoRepository;

    @Autowired
    private CargoService cargoService;

    @Test
    void theTotalIsReadOutOfTheBrokersWords() {
        assertPct("3.75 PCT TTL", "3.75");
        assertPct("2.5% + 1.25% adcom", "3.75");
        assertPct("3,75%", "3.75");
        assertPct("5% ttl incl 1.25% adcom", "5");
        assertPct("1.25", "1.25");
        assertPct("as usual", null);
        assertPct(null, null);
        assertPct("2.5% on 100% of freight", "2.5");
    }

    @Test
    void aRangeKeepsOnlyCargoesWhoseFigureIsInIt() {
        String tag = tag();
        cargo(tag, "3.75 PCT TTL");
        cargo(tag, "5% ttl incl 1.25% adcom");
        cargo(tag, "as usual");
        cargo(tag, "1.25");

        List<CargoResponse> found = search(tag, bd("3"), bd("4"), null, null);

        assertThat(found).extracting(CargoResponse::commission).containsExactly("3.75 PCT TTL");
    }

    @Test
    void aRangeWithNotStatedAlsoKeepsCargoesWithNoFigure() {
        String tag = tag();
        cargo(tag, "3.75 PCT TTL");
        cargo(tag, "5% ttl incl 1.25% adcom");
        cargo(tag, "as usual");
        cargo(tag, null);

        List<CargoResponse> found = search(tag, bd("3"), bd("4"), true, null);

        assertThat(found).extracting(CargoResponse::commission)
                .containsExactlyInAnyOrder("3.75 PCT TTL", "as usual", null);
    }

    @Test
    void notStatedAloneKeepsOnlyCargoesWithNoFigure() {
        String tag = tag();
        cargo(tag, "3.75 PCT TTL");
        cargo(tag, "as usual");
        cargo(tag, null);

        List<CargoResponse> found = search(tag, null, null, true, null);

        assertThat(found).extracting(CargoResponse::commission)
                .containsExactlyInAnyOrder("as usual", null);
    }

    @Test
    void sortingDescendingPutsCargoesWithNoFigureLast() {
        String tag = tag();
        cargo(tag, "1.25");
        cargo(tag, "as usual");
        cargo(tag, "3.75 PCT TTL");
        cargo(tag, null);
        cargo(tag, "5% ttl incl 1.25% adcom");
        cargo(tag, "2.5% + 1.25% adcom");

        List<CargoResponse> found = search(tag, null, null, null,
                Sort.by(Sort.Order.desc("commissionPct")));

        assertThat(found).extracting(CargoResponse::commissionPct)
                .usingElementComparator(NULLS_LAST)
                .containsExactly(bd("5"), bd("3.75"), bd("3.75"), bd("1.25"), null, null);
    }

    @Test
    void sortingAscendingPutsCargoesWithNoFigureLast() {
        String tag = tag();
        cargo(tag, "1.25");
        cargo(tag, "as usual");
        cargo(tag, "3.75 PCT TTL");
        cargo(tag, null);
        cargo(tag, "5% ttl incl 1.25% adcom");
        cargo(tag, "2.5% + 1.25% adcom");

        List<CargoResponse> found = search(tag, null, null, null,
                Sort.by(Sort.Order.asc("commissionPct")));

        assertThat(found).extracting(CargoResponse::commissionPct)
                .usingElementComparator(NULLS_LAST)
                .containsExactly(bd("1.25"), bd("3.75"), bd("3.75"), bd("5"), null, null);
    }

    private static final Comparator<BigDecimal> NULLS_LAST =
            Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder());

    private void assertPct(String commission, String expected) {
        BigDecimal actual = TenantContext.callAs(DESK, () ->
                cargoRepository.findById(cargo(tag(), commission)).orElseThrow().getCommissionPct());
        if (expected == null) {
            assertThat(actual).as("commissionPct of %s", commission).isNull();
        } else {
            assertThat(actual).as("commissionPct of %s", commission)
                    .isNotNull()
                    .isEqualByComparingTo(bd(expected));
        }
    }

    private long cargo(String tag, String commission) {
        return TenantContext.callAs(DESK, () -> {
            Cargo c = new Cargo();
            c.setCommodity(tag);
            c.setCommission(commission);
            return cargoRepository.save(c).getId();
        });
    }

    private List<CargoResponse> search(String tag, BigDecimal min, BigDecimal max,
                                       Boolean notStated, Sort sort) {
        CargoFilter filter = new CargoFilter(tag, null, null, null, null, null, null,
                null, null, null, null, null, null, null, min, max, notStated);
        PageRequest page = PageRequest.of(0, 100, sort == null ? Sort.unsorted() : sort);
        return TenantContext.callAs(DESK, () -> cargoService.search(filter, page).content());
    }

    private static String tag() {
        return "commission-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
