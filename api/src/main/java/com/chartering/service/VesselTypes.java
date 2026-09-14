package com.chartering.service;

import java.util.List;
import java.util.Locale;

/**
 * The vessel types this desk sorts its fleet by, and how a broker's wording maps onto them.
 *
 * <p><b>A list, not whatever the column happens to hold.</b> The type dropdown used to be every
 * distinct value in {@code vessels.vessel_type}, and every parse that gap-filled the column with a
 * circular's own description added one more: "SID, BOX", "Gen Cargo", "GENERAL CARGO / BOX /
 * DOUBLE SKINNED" — twenty-six one-offs on thirty-odd ships beside the twelve categories that
 * cover the other four and a half thousand. A filter offering "SID BOX" and "SID, BOX" as two
 * choices finds half of each. So the categories are fixed here, the dropdown offers these, and a
 * reading never writes free text into the column again.
 *
 * <p><b>The mapping only ever suggests a category, and returns nothing when the words do not
 * name one.</b> Order matters: the specific kinds first (a "tweendeck general cargo" ship is a
 * tweendecker), then river, then box shape, and only then the general dry-cargo wordings that
 * mean SEA TYPE on this desk. "GRD 2 CR 120 MT" describes gear, not a hull, and maps to nothing.
 */
public final class VesselTypes {

    public static final String SEA_TYPE = "SEA TYPE";
    public static final String SEA_TYPE_BOX_SHAPE = "SEA TYPE BOX SHAPE";
    public static final String SEA_RIVER_TYPE = "SEA+RIVER TYPE";
    public static final String RIVER_TYPE_ONLY = "RIVER TYPE ONLY";
    public static final String TWEENDECKER = "TWEENDECKER";
    public static final String TANKER = "TANKER";
    public static final String BARGE = "BARGE";
    public static final String RO_RO = "RO-RO";
    public static final String NRV = "NRV";
    public static final String REFRIGERATOR = "REFRIGERATOR";
    public static final String TUG = "TUG";
    public static final String LIVESTOCK = "LIVESTOCK";

    /** In the order the dropdown shows them: most of the fleet first. */
    public static final List<String> CANONICAL = List.of(
            SEA_TYPE, SEA_TYPE_BOX_SHAPE, SEA_RIVER_TYPE, RIVER_TYPE_ONLY, TWEENDECKER, TANKER,
            BARGE, RO_RO, NRV, REFRIGERATOR, TUG, LIVESTOCK);

    private VesselTypes() {
    }

    /** Whether a stored value is one of the categories exactly, casing included. */
    public static boolean isCanonical(String type) {
        return type != null && CANONICAL.contains(type);
    }

    /** The category a wording names, or null when it names none. */
    public static String canonical(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        for (String c : CANONICAL) {
            if (c.equalsIgnoreCase(trimmed)) return c;
        }
        // Words padded with spaces, so " gc " cannot be found inside "gcargo" and " bc " not
        // inside "abc". The plus survives for SEA+RIVER, which is already caught above.
        String s = " " + trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";

        if (s.contains("tanker")) return TANKER;
        if (s.contains(" ro ro ") || s.contains(" roro ")) return RO_RO;
        if (s.contains("reefer") || s.contains("refrigerat")) return REFRIGERATOR;
        if (s.contains("livestock")) return LIVESTOCK;
        if (s.contains(" tug ")) return TUG;
        if (s.contains("barge")) return BARGE;
        if (s.contains("tween")) return TWEENDECKER;
        if (s.contains("river") || s.contains("sormovsky")) {
            return s.contains(" only ") ? RIVER_TYPE_ONLY : SEA_RIVER_TYPE;
        }
        if (s.contains("box")) return SEA_TYPE_BOX_SHAPE;
        if (s.contains("general cargo") || s.contains(" gen cargo ") || s.contains(" gc ")
                || s.contains(" sid ") || s.contains("single deck") || s.contains(" bc ")
                || s.contains("bulk") || s.contains("dry cargo") || s.contains("sea type")) {
            return SEA_TYPE;
        }
        return null;
    }
}
