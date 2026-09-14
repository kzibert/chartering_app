package com.chartering.service.parser;

import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.service.CompanyNames;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which companies on file a pasted company style might be — every one of them, with why.
 *
 * <p><b>It proposes and never picks.</b> Five kinds of evidence, of very different weight, and
 * the screen shows each candidate with its reasons so a person decides:
 *
 * <ul>
 *   <li><b>the same email</b> — an address is one mailbox, so a hit is as good as it gets,
 *       short of the trap the Introduced list met: an address filed under two firms
 *       brings both, and both are shown;</li>
 *   <li><b>the same name</b>, case aside — the importer's exact rule;</li>
 *   <li><b>the same phone</b>, compared on the last nine digits so {@code +90 212 …} and
 *       {@code 0212 …} meet;</li>
 *   <li><b>the same mail domain or website</b> — strong for a firm's own domain and worthless
 *       for gmail, so webmail domains are never compared;</li>
 *   <li><b>a similar name</b> — {@link CompanyNames#similarityKey}, legal forms stripped.</li>
 * </ul>
 *
 * <p>Queries go through the EntityManager rather than new repository methods: they exist for
 * this one screen, a person presses a button to run them, and a table of three thousand firms
 * scanned for a similar name is well inside what that button can afford.
 */
@Component
@RequiredArgsConstructor
public class CompanyMatcher {

    /** Past this many, a list of "maybe"s stops being read. */
    private static final int LIMIT = 8;

    private final EntityManager em;

    /**
     * @param how   {@code email}, {@code name}, {@code phone}, {@code domain} or {@code similar},
     *              strongest first — the first reason decides the ordering
     * @param reasons every piece of evidence, in words, e.g. {@code "has chartering@x.com"}
     */
    public record Match(Long companyId, String name, String city, String country,
                        String how, List<String> reasons) {

        /** Email, exact name and phone are identity; domain and similarity are resemblance. */
        public boolean strong() {
            return "email".equals(how) || "name".equals(how) || "phone".equals(how);
        }
    }

    private static final List<String> ORDER = List.of("email", "name", "phone", "domain", "similar");

    @Transactional(readOnly = true)
    public List<Match> match(CompanyStyleReader.Style style) {
        Map<Long, Found> found = new LinkedHashMap<>();

        List<String> emails = style.contacts().stream()
                .filter(c -> "email".equals(c.kind())).map(CompanyStyleReader.ContactLine::value).toList();
        if (!emails.isEmpty()) {
            List<Contact> hits = em.createQuery("""
                    select c from Contact c join fetch c.company
                    where c.contactKind = 'email' and lower(c.contactValue) in :emails
                    """, Contact.class).setParameter("emails", emails).getResultList();
            for (Contact c : hits) {
                add(found, c.getCompany(), "email", "has " + c.getContactValue().toLowerCase(Locale.ROOT));
            }
        }

        String name = style.name();
        if (name != null) {
            List<Company> exact = em.createQuery(
                    "select c from Company c where lower(trim(c.name)) = :name", Company.class)
                    .setParameter("name", name.strip().toLowerCase(Locale.ROOT)).getResultList();
            for (Company c : exact) add(found, c, "name", "same name");
        }

        Set<String> phoneTails = new LinkedHashSet<>();
        for (CompanyStyleReader.ContactLine c : style.contacts()) {
            if ("phone".equals(c.kind())) {
                String tail = tail(c.value());
                if (tail != null) phoneTails.add(tail);
            }
        }
        if (!phoneTails.isEmpty()) {
            List<Contact> phones = em.createQuery("""
                    select c from Contact c join fetch c.company
                    where c.contactKind = 'phone'
                    """, Contact.class).getResultList();
            for (Contact c : phones) {
                String tail = tail(c.getContactValue());
                if (tail != null && phoneTails.contains(tail)) {
                    add(found, c.getCompany(), "phone", "has phone " + c.getContactValue().strip());
                }
            }
        }

        for (String domain : domains(style)) {
            List<Contact> byDomain = em.createQuery("""
                    select c from Contact c join fetch c.company
                    where c.contactKind = 'email' and lower(c.contactValue) like :pattern
                    """, Contact.class).setParameter("pattern", "%@" + domain).setMaxResults(50)
                    .getResultList();
            for (Contact c : byDomain) add(found, c.getCompany(), "domain", "mail at @" + domain);
            List<Company> bySite = em.createQuery(
                    "select c from Company c where lower(c.website) like :pattern", Company.class)
                    .setParameter("pattern", "%" + domain + "%").setMaxResults(20).getResultList();
            for (Company c : bySite) add(found, c, "domain", "website " + c.getWebsite());
        }

        if (distinctiveKey(name).length() >= 4) {
            List<Company> all = em.createQuery("select c from Company c", Company.class).getResultList();
            for (Company c : all) {
                String reason = resemblance(name, c.getName());
                if (reason != null) add(found, c, "similar", reason);
            }
        }

        return found.values().stream()
                .sorted(Comparator.comparingInt((Found f) -> ORDER.indexOf(f.how)))
                .limit(LIMIT)
                .map(f -> new Match(f.company.getId(), f.company.getName(), f.company.getCityName(),
                        f.company.getCountry(), f.how, f.reasons))
                .toList();
    }

    private static final class Found {
        final Company company;
        String how;
        final List<String> reasons = new ArrayList<>();

        Found(Company company, String how) {
            this.company = company;
            this.how = how;
        }
    }

    private static void add(Map<Long, Found> found, Company company, String how, String reason) {
        if (company == null) return;
        Found f = found.computeIfAbsent(company.getId(), id -> new Found(company, how));
        if (ORDER.indexOf(how) < ORDER.indexOf(f.how)) f.how = how;
        if (!f.reasons.contains(reason)) f.reasons.add(reason);
    }

    /** The firm's own domains — from its addresses and its website, never webmail. */
    private static Set<String> domains(CompanyStyleReader.Style style) {
        Set<String> out = new LinkedHashSet<>();
        for (CompanyStyleReader.ContactLine c : style.contacts()) {
            if (!"email".equals(c.kind())) continue;
            String d = c.value().substring(c.value().indexOf('@') + 1).toLowerCase(Locale.ROOT);
            if (!CompanyStyleReader.isWebmail(d)) out.add(d);
        }
        if (style.website() != null && !CompanyStyleReader.isWebmail(style.website())) {
            out.add(style.website().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""));
        }
        return out;
    }

    /**
     * Words that say what trade a firm is in rather than which firm it is.
     *
     * <p>Stripped before names are compared for resemblance, on top of the legal forms, because
     * in this table they are the rule rather than the exception: with them left in, "North Sea
     * Example Chartering A/S" reduced to a key ending in "chartering", and "Shipping &amp;
     * Chartering GmbH" reduced to "chartering" alone — which every chartering firm on file
     * contains. Only the resemblance test drops them; an exact name match still reads the name
     * whole.
     */
    private static final Set<String> TRADE_WORDS = Set.of(
            "chartering", "charterers", "maritime", "marine", "navigation", "lines", "line",
            "logistics", "trading", "trade", "agency", "agencies", "bulk", "carriers", "carrier",
            "freight", "shipmanagement", "management", "international", "intl", "services",
            "transport", "transportation", "denizcilik", "nakliyat", "ticaret", "tic", "shipbrokers",
            "shipbroking", "brokers", "broking", "tankers", "ships", "sea", "ocean", "global");

    /**
     * The part of a name that tells one firm from another: legal forms and trade words gone,
     * letters run together. Empty when nothing distinctive is left, which is itself an answer —
     * a name that is all trade words resembles nothing in particular.
     */
    static String distinctiveKey(String name) {
        if (name == null) return "";
        StringBuilder kept = new StringBuilder();
        for (String word : name.toLowerCase(Locale.ROOT).replaceAll("\\b[ak]/s\\b", " ")
                .replaceAll("[^a-z0-9\\s]", " ").split("\\s+")) {
            if (word.isEmpty() || TRADE_WORDS.contains(word)) continue;
            kept.append(word).append(' ');
        }
        return CompanyNames.similarityKey(kept.toString());
    }

    /**
     * Why two names might be one firm, in words, or null. The same distinctive key is a
     * similar name; one key inside the other counts only when the shorter has five letters
     * of its own, so "AG" or "SEA" cannot drag in half the table.
     */
    static String resemblance(String parsed, String onFile) {
        String key = distinctiveKey(parsed);
        String other = distinctiveKey(onFile);
        if (key.length() < 4 || other.isEmpty()) return null;
        if (other.equals(key)) return "similar name";
        if (Math.min(other.length(), key.length()) >= 5 && (other.contains(key) || key.contains(other))) {
            return "name contains \"" + (other.length() < key.length() ? onFile : parsed) + "\"";
        }
        return null;
    }

    /** The last nine digits, or null for anything too short to be one number and not another. */
    static String tail(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 9 ? null : digits.substring(digits.length() - 9);
    }
}
