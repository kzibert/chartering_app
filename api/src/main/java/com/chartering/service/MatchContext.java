package com.chartering.service;

import java.time.LocalDate;

/**
 * Everything {@link MatchScorer} needs that is not the cargo or the ship.
 *
 * <p>One object rather than four arguments, and built once per request rather than once per
 * pairing. That is not tidiness: {@link MatchSettings#values()} reads the database, and a
 * screen scoring a thousand positions against a hundred cargoes would otherwise read it a
 * hundred thousand times to be told the same four numbers. Fixing {@code today} here has the
 * same effect on a different failure — a request that runs across midnight would otherwise
 * age half its ships a year.
 *
 * @param areas   the trade-area vocabulary: what a written water means, and the broker's
 *                round figure between two of them
 * @param routes  the sea network: miles between two berths, and the straits on the way. Asked
 *                first where both ends name a port, because it can tell Constanza from Rostov
 *                and the area table cannot
 * @param tuning  the four numbers the rule cannot derive — speed, allowance, and the two ends
 *                of what counts as filling a ship
 * @param today   what "how old is she" is measured against
 */
public record MatchContext(TradeAreaGraph areas,
                           SeaRouteGraph routes,
                           MatchSettings.Values tuning,
                           LocalDate today) {
}
