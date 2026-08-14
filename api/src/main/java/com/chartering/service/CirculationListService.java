package com.chartering.service;

import com.chartering.dto.CirculationListEntryRequest;
import com.chartering.dto.CirculationListEntryResponse;
import com.chartering.dto.CirculationListRequest;
import com.chartering.dto.CirculationListResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.CirculationList;
import com.chartering.model.CirculationListEntry;
import com.chartering.model.Contact;
import com.chartering.repository.CirculationListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Circulation lists: the named sets prepared in advance, plus the single unnamed draft the
 * Companies/Vessels/People tabs collect into.
 *
 * <p>Lists live in the database rather than in the browser so the same prepared list is
 * there on any machine, and so a run recorded in history can name the list it came from.
 *
 * <p>Every write dedupes by address, case-insensitively — the same rule the campaign
 * applies at send time. Catching it here means the count on screen is the number of
 * messages that will actually go out.
 */
@Service
@RequiredArgsConstructor
public class CirculationListService {

    private final CirculationListRepository lists;

    // ---------------------------------------------------------------- reading

    /** Saved lists with their entry counts; the draft is excluded (fetch it by itself). */
    @Transactional(readOnly = true)
    public List<CirculationListResponse> list() {
        Map<Long, Integer> counts = entryCounts();
        return lists.findByDraftFalseOrderByNameAsc().stream()
                .map(l -> toSummary(l, counts.getOrDefault(l.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CirculationListResponse get(Long id) {
        return toDetail(find(id));
    }

    /**
     * The current list, created on first use. The unique index guarantees there is at most
     * one, and the seed patch inserts it — this fallback only matters on a database that
     * predates the patch's INSERT.
     */
    @Transactional
    public CirculationListResponse getDraft() {
        return toDetail(draft());
    }

    // ---------------------------------------------------------------- list lifecycle

    @Transactional
    public CirculationListResponse create(CirculationListRequest req) {
        String name = requireName(req.getName());
        requireNameAvailable(name, null);
        CirculationList l = new CirculationList();
        l.setName(name);
        l.setNotes(req.getNotes());
        return toDetail(lists.save(l));
    }

    /** Rename / re-note a saved list. Entries are untouched — this is not a replace. */
    @Transactional
    public CirculationListResponse update(Long id, CirculationListRequest req) {
        CirculationList l = find(id);
        if (l.isDraft()) {
            throw new IllegalArgumentException(
                    "The current list has no name. Save it as a new list to give it one.");
        }
        String name = requireName(req.getName());
        requireNameAvailable(name, id);
        l.setName(name);
        l.setNotes(req.getNotes());
        return toDetail(lists.save(l));
    }

    @Transactional
    public void delete(Long id) {
        CirculationList l = find(id);
        if (l.isDraft()) {
            // Deleting it would leave the tabs with nowhere to add into, and "empty it"
            // is what the user actually means here.
            throw new IllegalArgumentException("The current list cannot be deleted — clear it instead.");
        }
        lists.delete(l);
    }

    /**
     * Copy a list's contents into a new named one. This is "Save as" on the current list:
     * the draft keeps its rows so collecting can continue where it left off.
     */
    @Transactional
    public CirculationListResponse copy(Long sourceId, CirculationListRequest req) {
        CirculationList source = find(sourceId);
        String name = requireName(req.getName());
        requireNameAvailable(name, null);

        CirculationList copy = new CirculationList();
        copy.setName(name);
        copy.setNotes(req.getNotes());
        source.getEntries().forEach(e -> copy.addEntry(cloneEntry(e)));
        return toDetail(lists.save(copy));
    }

    /** Replace a list's contents with another's — "load this saved list into the current one". */
    @Transactional
    public CirculationListResponse replaceEntriesFrom(Long targetId, Long sourceId) {
        CirculationList target = find(targetId);
        CirculationList source = find(sourceId);
        target.getEntries().clear();
        source.getEntries().forEach(e -> target.addEntry(cloneEntry(e)));
        return toDetail(lists.save(target));
    }

    // ---------------------------------------------------------------- entries

    /**
     * Add addresses to a list, skipping the ones already on it.
     *
     * @return how many rows were actually added — the UI reports "added N (M already there)",
     * which is only honest if the duplicate check happens where the write happens.
     */
    @Transactional
    public int addEntries(Long listId, List<CirculationListEntryRequest> requests) {
        CirculationList l = find(listId);
        Set<String> seen = existingAddresses(l);
        int added = 0;
        for (CirculationListEntryRequest req : requests) {
            String email = normalise(req.getEmail());
            if (email == null || !seen.add(email)) {
                continue;
            }
            CirculationListEntry e = new CirculationListEntry();
            e.setEmail(req.getEmail().trim());
            e.setContactId(req.getContactId());
            e.setPersonId(req.getPersonId());
            e.setPersonName(req.getPersonName());
            e.setGreetingName(req.getGreetingName());
            e.setTitle(req.getTitle());
            e.setCompanyId(req.getCompanyId());
            e.setCompanyName(req.getCompanyName());
            l.addEntry(e);
            added++;
        }
        lists.save(l);
        return added;
    }

    /** Add contacts straight from a selection, without the caller shaping them into DTOs. */
    @Transactional
    public int addContacts(Long listId, List<Contact> contacts) {
        return addEntries(listId, contacts.stream().map(CirculationListService::toEntryRequest).toList());
    }

    /** Edit one row's merge fields. The list is a document; this does not touch the contact. */
    @Transactional
    public CirculationListResponse updateEntry(Long listId, Long entryId, CirculationListEntryRequest req) {
        CirculationList l = find(listId);
        CirculationListEntry e = l.getEntries().stream()
                .filter(x -> x.getId().equals(entryId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Circulation list entry", entryId));

        String email = normalise(req.getEmail());
        boolean clash = l.getEntries().stream()
                .anyMatch(x -> !x.getId().equals(entryId) && normalise(x.getEmail()).equals(email));
        if (clash) {
            throw new IllegalArgumentException(req.getEmail().trim() + " is already on this list.");
        }

        e.setEmail(req.getEmail().trim());
        e.setPersonName(req.getPersonName());
        e.setGreetingName(req.getGreetingName());
        e.setTitle(req.getTitle());
        e.setCompanyName(req.getCompanyName());
        return toDetail(lists.save(l));
    }

    /**
     * Remove addresses from a list, matched by address rather than by entry id.
     *
     * <p>Address is the right key here: it is what dedupe and the sender both work on, so
     * "take this list's people off the current one" still holds when the same mailbox was
     * collected through two different contacts, or typed in by hand on one side.
     *
     * @return how many rows were actually removed — the rest were not on the list
     */
    @Transactional
    public int removeEntriesByEmail(Long listId, List<String> emails) {
        CirculationList l = find(listId);
        Set<String> targets = emails.stream()
                .map(CirculationListService::normalise)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (targets.isEmpty()) {
            return 0;
        }
        int before = l.getEntries().size();
        l.getEntries().removeIf(e -> targets.contains(normalise(e.getEmail())));
        int removed = before - l.getEntries().size();
        if (removed > 0) {
            lists.save(l);
        }
        return removed;
    }

    @Transactional
    public void removeEntry(Long listId, Long entryId) {
        CirculationList l = find(listId);
        boolean removed = l.getEntries().removeIf(e -> e.getId().equals(entryId));
        if (!removed) {
            throw new ResourceNotFoundException("Circulation list entry", entryId);
        }
        lists.save(l);
    }

    @Transactional
    public CirculationListResponse clear(Long listId) {
        CirculationList l = find(listId);
        l.getEntries().clear();
        return toDetail(lists.save(l));
    }

    // ---------------------------------------------------------------- internals

    private CirculationList draft() {
        return lists.findByDraftTrue().orElseGet(() -> {
            CirculationList l = new CirculationList();
            l.setDraft(true);
            return lists.save(l);
        });
    }

    private CirculationList find(Long id) {
        return lists.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Circulation list", id));
    }

    private Map<Long, Integer> entryCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : lists.countEntriesPerList()) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    private static Set<String> existingAddresses(CirculationList l) {
        Set<String> seen = new LinkedHashSet<>();
        l.getEntries().forEach(e -> seen.add(normalise(e.getEmail())));
        return seen;
    }

    private static CirculationListEntry cloneEntry(CirculationListEntry src) {
        CirculationListEntry e = new CirculationListEntry();
        e.setEmail(src.getEmail());
        e.setContactId(src.getContactId());
        e.setPersonId(src.getPersonId());
        e.setPersonName(src.getPersonName());
        e.setGreetingName(src.getGreetingName());
        e.setTitle(src.getTitle());
        e.setCompanyId(src.getCompanyId());
        e.setCompanyName(src.getCompanyName());
        return e;
    }

    private static CirculationListEntryRequest toEntryRequest(Contact c) {
        return new CirculationListEntryRequest(
                c.getContactValue(),
                c.getId(),
                c.getPerson() != null ? c.getPerson().getId() : null,
                c.getPerson() != null ? c.getPerson().getFullName() : null,
                c.getPerson() != null ? c.getPerson().getGreetingName() : null,
                c.getPerson() != null ? c.getPerson().getTitle() : null,
                c.getCompany() != null ? c.getCompany().getId() : null,
                c.getCompany() != null ? c.getCompany().getName() : null);
    }

    private static String normalise(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A list name is required.");
        }
        return name.trim();
    }

    /** Checked here as well as by the unique index, so the user gets a readable 400. */
    private void requireNameAvailable(String name, Long selfId) {
        lists.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new IllegalArgumentException("A list named \"" + name + "\" already exists.");
            }
        });
    }

    // ---------------------------------------------------------------- mapping

    private static CirculationListResponse toSummary(CirculationList l, int entryCount) {
        return new CirculationListResponse(l.getId(), l.getName(), l.isDraft(), l.getNotes(),
                entryCount, null, l.getCreatedAt(), l.getUpdatedAt());
    }

    private static CirculationListResponse toDetail(CirculationList l) {
        List<CirculationListEntryResponse> entries = l.getEntries().stream()
                .map(e -> new CirculationListEntryResponse(e.getId(), e.getContactId(), e.getEmail(),
                        e.getPersonId(), e.getPersonName(), e.getGreetingName(), e.getTitle(),
                        e.getCompanyId(), e.getCompanyName()))
                .toList();
        return new CirculationListResponse(l.getId(), l.getName(), l.isDraft(), l.getNotes(),
                entries.size(), entries, l.getCreatedAt(), l.getUpdatedAt());
    }
}
