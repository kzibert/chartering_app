package com.chartering.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private CompanyNames() {
    }

    /**
     * Comparison key that also drops punctuation and legal-form suffixes, so "Fednav Ltd."
     * and "FEDNAV" collide. Accents are folded off first ({@link #foldAccents}), so "ŞAHİN"
     * and "SAHIN" collide too; a letter with no plain a-z base is dropped with the punctuation.
     *
     * <p>Folding is not cosmetic: it is what makes the Turkish legal form strippable at all.
     * "Ltd. Şti." is how it is written, and while the cedilla was dropped rather than folded
     * the word reached the suffix list as "ti" — so "sti" on the list never fired for the one
     * spelling it is there for, and "Kaya Denizcilik Ltd. Şti." missed "Kaya Denizcilik".
     *
     * <p>A legal form is dropped wherever it stands, not only at the end: "AS" leads an
     * Estonian firm's name as "Ltd" ends an English one. A name that is nothing but legal
     * forms keys to the empty string, as a blank name does, and the empty key means "nothing
     * to compare" — a caller must not read two of them as one firm.
     */
    public static String similarityKey(String s) {
        if (s == null) return "";
        // "A/S" and "K/S" are legal forms too, but the punctuation pass below would split them
        // into two stray letters that no suffix list can match — so they go first, whole.
        String[] words = foldAccents(s)
                .replaceAll("\\b[ak]/s\\b", " ")
                .replaceAll("[^a-z0-9\\s]", " ").split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty() || LEGAL_SUFFIXES.contains(word)) continue;
            out.append(word);
        }
        return out.toString();
    }

    /**
     * Lower-cased, with accents taken off their letters: ş to s, ç to c, ğ to g, ö to o, and a
     * dotted capital İ to i (it decomposes to i and a combining dot). The dotless ı is a letter
     * of its own with nothing to decompose into, so it is mapped by hand; the Turkish capitals
     * I and İ both reach i already, so only text typed in lower case carries one.
     */
    public static String foldAccents(String s) {
        String decomposed = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").replace('ı', 'i');
    }
}
