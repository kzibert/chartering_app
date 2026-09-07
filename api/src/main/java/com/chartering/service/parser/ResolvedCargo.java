package com.chartering.service.parser;

import com.chartering.model.Company;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;

import java.time.LocalDate;

/**
 * A parsed cargo with its lookups already done.
 *
 * <p>Resolving happens once and travels, rather than being repeated at each place that needs
 * it. Three things read these — the duplicate test, the merge, and the create — and each
 * would otherwise resolve the same load port against the same table and could, given an edit
 * to one of them, resolve it differently. A cargo that was tested for duplication against
 * one reading of its load point and then written with another is a bug nobody would find by
 * reading either method.
 *
 * @param parsed the model's own words, kept alongside so text columns can carry them
 */
public record ResolvedCargo(Extraction.ExtractedCargo parsed,
                            Port loadPort,
                            TradeArea loadArea,
                            Port dischargePort,
                            TradeArea dischargeArea,
                            LocalDate laycanFrom,
                            LocalDate laycanTo,
                            Company charterer) {
}
