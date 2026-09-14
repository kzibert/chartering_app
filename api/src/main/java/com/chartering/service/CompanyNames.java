package com.chartering.service;

import java.util.Locale;
import java.util.Set;

/**
 * How two company names are compared when the question is "might these be one firm".
 *
 * <p>Shared by the contacts importer and the Intake paste, which ask that question of the
 * same table and must not answer it two ways: a firm the importer calls similar and the paste
 * calls unrelated is a firm entered twice by whichever screen was used second.
 *
 * <p><b>Only ever a suggestion.</b> Neither caller merges on it. "Fednav Ltd" and "Fednav"
 * are probably the same firm and a person should be shown that; deciding it for them is how a
 * machine quietly merges two companies a broker keeps apart on purpose.
 */
public final class CompanyNames {

    /**
     * Legal-form suffixes, stripped only when looking for a <em>similar</em> company.
     *
     * <p>"shipping" and "group" are on it although neither is a legal form: in this trade they
     * are what a firm's name ends in about as often as "Ltd" is, and "Soylu Shipping" against
     * "SOYLU" is the ordinary case of one firm written two ways.
     */
    public static final Set<String> LEGAL_SUFFIXES = Set.of(
            "ltd", "limited", "llc", "lc", "inc", "incorporated", "corp", "corporation",
            "co", "company", "gmbh", "ag", "sa", "sas", "srl", "spa", "bv", "nv", "as",
            "asa", "oy", "ab", "aps", "plc", "pte", "pty", "kg", "sarl", "sl", "sti",
            "ltdsti", "lp", "llp", "group", "holding", "holdings", "shipping");

    private CompanyNames() {
    }

    /**
     * Comparison key that also drops punctuation and legal-form suffixes, so "Fednav Ltd."
     * and "FEDNAV" collide. Letters outside a-z are dropped with the punctuation, which is
     * what makes "DENİZCİLİK" and "DENIZCILIK" meet only partway — acceptable for a hint.
     */
    public static String similarityKey(String s) {
        if (s == null) return "";
        // "A/S" and "K/S" are legal forms too, but the punctuation pass below would split them
        // into two stray letters that no suffix list can match — so they go first, whole.
        String[] words = s.toLowerCase(Locale.ROOT)
                .replaceAll("\\b[ak]/s\\b", " ")
                .replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty() || LEGAL_SUFFIXES.contains(word)) continue;
            out.append(word);
        }
        return out.toString();
    }
}
