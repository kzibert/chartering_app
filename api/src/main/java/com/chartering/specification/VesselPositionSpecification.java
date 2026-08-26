package com.chartering.specification;

import com.chartering.model.PositionStatus;
import com.chartering.model.Vessel;
import com.chartering.model.VesselExName;
import com.chartering.model.VesselPosition;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Null-safe, composable filters for the Open Fleet tab.
 */
public final class VesselPositionSpecification {

    private VesselPositionSpecification() {
    }

    /**
     * Only the newest live position per vessel — what "Open Fleet" means.
     *
     * <p>Written as "no newer live row exists for this vessel" rather than with a window
     * function, because that phrasing is what the {@code (vessel_id, reported_at DESC)}
     * index actually serves, and because it composes: as a predicate it can be AND'd with
     * every other filter and still be paged by the same query the rest of the app uses.
     *
     * <p>Ties on the timestamp break by id, so two positions reported in the same instant
     * still yield exactly one row rather than both or neither.
     *
     * <p>One row per vessel, whoever gave the newest reading. Two brokers who disagree are
     * therefore <em>not</em> both shown here — a fleet list with the same hull on it twice is
     * a fleet list nobody can count. The disagreement is not lost: both rows stay LIVE in the
     * table, and {@code current=false} or the vessel's own history shows them side by side,
     * which is where that question is actually asked.
     */
    public static Specification<VesselPosition> currentOnly(boolean current) {
        return (root, query, cb) -> {
            if (!current) return null;
            Subquery<Long> newer = query.subquery(Long.class);
            Root<VesselPosition> n = newer.from(VesselPosition.class);
            return cb.and(
                    cb.equal(root.get("status"), PositionStatus.LIVE),
                    cb.not(cb.exists(newer.select(n.get("id")).where(
                            cb.equal(n.get("vessel").get("id"), root.get("vessel").get("id")),
                            cb.equal(n.get("status"), PositionStatus.LIVE),
                            cb.or(
                                    cb.greaterThan(n.get("reportedAt"), root.get("reportedAt")),
                                    cb.and(
                                            cb.equal(n.get("reportedAt"), root.get("reportedAt")),
                                            cb.greaterThan(n.get("id"), root.get("id"))))))));
        };
    }

    public static Specification<VesselPosition> statusIn(List<PositionStatus> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty() ? null
                : root.get("status").in(statuses);
    }

    /** By vessel name, current or former — the same rule the vessel search follows. */
    public static Specification<VesselPosition> vesselNameContains(String name) {
        return (root, query, cb) -> {
            if (name == null || name.isBlank()) return null;
            String pattern = "%" + name.toLowerCase() + "%";
            Subquery<Long> ex = query.subquery(Long.class);
            Root<VesselExName> e = ex.from(VesselExName.class);
            return cb.or(
                    cb.like(cb.lower(root.get("vessel").get("name")), pattern),
                    cb.exists(ex.select(e.get("id")).where(
                            cb.equal(e.get("vessel").get("id"), root.get("vessel").get("id")),
                            cb.like(cb.lower(e.get("name")), pattern))));
        };
    }

    public static Specification<VesselPosition> vesselIdEquals(Long vesselId) {
        return (root, query, cb) -> vesselId == null ? null
                : cb.equal(root.get("vessel").get("id"), vesselId);
    }

    /**
     * Positions opening in this area, counting an open port that sits in it.
     *
     * <p>Both halves are needed: "SPOT AT MARMARA" gives an area and no port, "SALERNO 1/2
     * SEPT" gives a port and no area, and a search for the West Med has to find the second.
     */
    public static Specification<VesselPosition> openAreaEquals(Long areaId) {
        return (root, query, cb) -> {
            if (areaId == null) return null;
            return cb.or(
                    cb.equal(root.get("openArea").get("id"), areaId),
                    cb.equal(root.join("openPort", JoinType.LEFT).get("tradeArea").get("id"), areaId));
        };
    }

    /**
     * Positions whose open window overlaps the one asked about.
     *
     * <p>Overlap rather than containment, for the reason a cargo laycan uses it: a ship open
     * 1/15 September is a ship worth seeing when looking at the first week. Positions with no
     * dates on file come back whatever the window — "SPOT" and "PPT" are real answers that
     * name no day, and hiding them would hide the promptest tonnage on the list.
     */
    public static Specification<VesselPosition> openOverlaps(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return null;
            List<Predicate> within = new ArrayList<>();
            if (to != null) within.add(cb.lessThanOrEqualTo(root.get("openFrom"), to));
            if (from != null) within.add(cb.greaterThanOrEqualTo(root.get("openTo"), from));
            return cb.or(
                    cb.and(within.toArray(Predicate[]::new)),
                    cb.and(cb.isNull(root.get("openFrom")), cb.isNull(root.get("openTo"))));
        };
    }

    /** Who told us. */
    public static Specification<VesselPosition> reportedByCompany(Long companyId) {
        return (root, query, cb) -> companyId == null ? null
                : cb.equal(root.get("reportedByCompany").get("id"), companyId);
    }

    /**
     * Positions no older than this many days.
     *
     * <p>The filter a fleet list is actually worked with: a page of readings from three
     * weeks ago is not a fleet, it is an archive, and this is what separates the two.
     */
    public static Specification<VesselPosition> reportedWithinDays(Integer days) {
        return (root, query, cb) -> days == null ? null
                : cb.greaterThanOrEqualTo(root.get("reportedAt"), OffsetDateTime.now().minusDays(days));
    }

    /**
     * Positions whose vessel's size lies in a range.
     *
     * <p>Reads DWCC where it is on file and falls back to DWT where it is not — which is the
     * one place this app treats the two as interchangeable rather than OR-ing them, and it
     * has to: the position lists quote "cc" and the vessel records hold whichever figure
     * somebody had. 2,355 vessels have a DWT and no DWCC, so testing DWCC alone would empty
     * half the fleet out of every size search made here.
     *
     * <p>0 means unknown in both columns, as everywhere else in this schema, so a vessel with
     * neither figure does not match a range at all.
     */
    public static Specification<VesselPosition> vesselSizeBetween(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return null;
            var vessel = root.<VesselPosition, Vessel>join("vessel");
            var dwcc = vessel.<BigDecimal>get("deadweightCargoCapacity");
            var dwt = vessel.<BigDecimal>get("deadweightTonnage");
            // coalesce of "DWCC if it is a real figure, else DWT" - nullif turns the 0
            // sentinel into a NULL the coalesce can fall through.
            var size = cb.coalesce(cb.nullif(dwcc, BigDecimal.ZERO), dwt);
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.greaterThan(size, BigDecimal.ZERO));
            if (min != null) ps.add(cb.greaterThanOrEqualTo(size, min));
            if (max != null) ps.add(cb.lessThanOrEqualTo(size, max));
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** Geared or gearless, read off the vessel. */
    public static Specification<VesselPosition> vesselGeared(Boolean geared) {
        return (root, query, cb) -> geared == null ? null
                : cb.equal(root.get("vessel").get("geared"), geared);
    }

    /** Russian-rooted vessels are out of every list unless explicitly included. */
    public static Specification<VesselPosition> excludeBanned(boolean includeBanned) {
        return (root, query, cb) -> includeBanned ? null
                : cb.isFalse(root.get("vessel").get("banned"));
    }
}
