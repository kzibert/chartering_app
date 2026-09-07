package com.chartering.service.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * One hull as an outside source describes her.
 *
 * <p><b>Deliberately narrow.</b> It carries only what a public ship database states about the
 * ship herself and states reliably: who she is, when she was built, how big she is, whose
 * flag she flies. It does not carry draft, capacities, gear or fittings — not because they
 * would be unwelcome but because the sources do not give them in a form worth trusting, and a
 * field that is sometimes right is worse here than a field that is absent. The standing
 * example is draught: the figure on a tracking page is the AIS-reported <em>current</em>
 * draught, which is how deep she is floating today with cargo in her. Writing that into
 * {@code maximumDraft} would put a loaded reading where a design limit belongs, and every
 * berth check afterwards would be wrong in the direction that loses cargoes.
 *
 * <p>{@code grossTonnage} has no column on {@code Vessel} and is kept anyway: it is not
 * offered for filling, it is shown as corroboration. Two hulls of one name are told apart by
 * their tonnage as readily as by their deadweight.
 *
 * @param sourceUrl the page a person can open to check. The whole feature rests on being
 *                  checkable, so nothing is stored without one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VesselParticulars(
        String imo,
        String name,
        String vesselType,
        String flag,
        Integer yearBuilt,
        BigDecimal grossTonnage,
        BigDecimal deadweightTonnage,
        BigDecimal lengthM,
        BigDecimal beamM,
        String sourceUrl) {
}
