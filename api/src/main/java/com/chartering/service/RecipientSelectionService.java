package com.chartering.service;

import com.chartering.model.Contact;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which of an entity's email addresses belong on a circulation list.
 *
 * <p>Bulk-collecting every address a company has on file is how a circular reaches the same
 * desk four times; taking only one is how it misses the person who actually charters. The
 * flags let the user say which, per address, and this rule reads them:
 *
 * <pre>
 *   any address flagged circ?  -> take all of them
 *   else a main address?       -> take that one
 *   else                       -> take every working address
 * </pre>
 *
 * <p>The rule is applied <b>per person</b>, with a company's person-less addresses forming
 * one more group of their own. So flagging one person's address does not silence their
 * colleagues, and a company with an unflagged switchboard address still contributes it.
 *
 * <p>Everything here operates on contacts the repository has already filtered down to
 * working, non-banned emails — a dead address is not a candidate under any flag.
 */
@Service
public class RecipientSelectionService {

    /**
     * Apply the precedence rule to a flat list of email contacts.
     *
     * <p>Input order matters only in that it decides the output order; the rule itself
     * re-reads the flags rather than trusting the query's ORDER BY, so the same list is
     * produced whether or not the caller sorted circ-first.
     */
    public List<Contact> select(Collection<Contact> emailContacts) {
        Map<String, List<Contact>> groups = new LinkedHashMap<>();
        for (Contact c : emailContacts) {
            groups.computeIfAbsent(groupKey(c), k -> new ArrayList<>()).add(c);
        }
        List<Contact> out = new ArrayList<>();
        for (List<Contact> group : groups.values()) {
            out.addAll(selectWithinGroup(group));
        }
        return out;
    }

    private static List<Contact> selectWithinGroup(List<Contact> group) {
        List<Contact> circ = group.stream().filter(Contact::isCirc).toList();
        if (!circ.isEmpty()) {
            return circ;
        }
        // At most one main email per company, so this is a single address when present.
        // It may belong to a colleague's group rather than this one, in which case this
        // group falls through to "all" — which is the intended reading: main answers
        // "how do I reach the company", not "who at the company gets the circular".
        List<Contact> main = group.stream().filter(Contact::isMain).toList();
        if (!main.isEmpty()) {
            return main;
        }
        return group;
    }

    /**
     * The unit the rule is evaluated over: a person, or — for addresses attached to no
     * person — the company they hang off. An address with neither is its own group, since
     * there is nothing to group it with and dropping it would lose data silently.
     */
    private static String groupKey(Contact c) {
        if (c.getPerson() != null) {
            return "p:" + c.getPerson().getId();
        }
        if (c.getCompany() != null) {
            return "c:" + c.getCompany().getId();
        }
        return "x:" + c.getId();
    }
}
