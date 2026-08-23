package com.chartering.service;

import com.chartering.dto.ContactResponse;
import com.chartering.dto.PageResponse;
import com.chartering.dto.PersonDetailResponse;
import com.chartering.dto.PersonRequest;
import com.chartering.dto.PersonResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.specification.PersonSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PersonService {

    private final PersonRepository personRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final RecipientSelectionService recipientSelection;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public List<PersonResponse> list(Long companyId, String name) {
        Specification<Person> spec = Specification.allOf(
                PersonSpecification.companyIdEquals(companyId),
                PersonSpecification.nameContains(name));
        return personRepository.findAll(spec, Sort.by("fullName")).stream()
                .map(mapper::toPersonResponse)
                .toList();
    }

    /**
     * Paginated people search with their contacts attached — powers the People page, which
     * absorbed what used to be a separate flat contact list.
     *
     * The contact criteria narrow <em>which people</em> come back, but each person still
     * arrives with their whole contact list: searching one address should show you that
     * person's other numbers too, not hide them.
     */
    @Transactional(readOnly = true)
    public PageResponse<PersonDetailResponse> search(PeopleFilter f, Pageable pageable) {
        Page<Person> page = personRepository.findAll(buildSpec(f), pageable);
        List<Long> ids = page.getContent().stream().map(Person::getId).toList();
        // Skip the round trip on an empty page rather than issuing "in ()".
        Map<Long, List<ContactResponse>> byPerson = ids.isEmpty() ? Map.of()
                : contactRepository.findByPersonIds(ids, f.includeBanned()).stream()
                        .collect(Collectors.groupingBy(c -> c.getPerson().getId(), LinkedHashMap::new,
                                Collectors.mapping(mapper::toContactResponse, Collectors.toList())));

        return PageResponse.from(page.map(p -> new PersonDetailResponse(
                mapper.toPersonResponse(p),
                byPerson.getOrDefault(p.getId(), List.of()))));
    }

    /**
     * The addresses to circulate to for a set of people — the People tab's bulk add.
     *
     * <p>Which addresses those are is decided by {@link RecipientSelectionService}: circ-
     * flagged ones if the person has any, else their main one, else all their working ones.
     *
     * @param personIds     specific people (the checkbox selection); when empty the whole
     *                      filtered set is used instead, which is the "add all matching" case
     * @param confirmedOnly restrict to confirmed addresses
     */
    @Transactional(readOnly = true)
    public List<ContactResponse> emailContacts(PeopleFilter f, List<Long> personIds,
                                               boolean confirmedOnly) {
        List<Long> ids = personIds == null || personIds.isEmpty()
                ? personRepository.findAll(buildSpec(f)).stream().map(Person::getId).toList()
                : personIds;
        if (ids.isEmpty()) return List.of();
        List<Contact> emails = contactRepository.findEmailContactsByPersonIds(
                ids, confirmedOnly, f.includeBanned());
        return recipientSelection.select(emails).stream()
                .map(mapper::toContactResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PersonResponse get(Long id) {
        return mapper.toPersonResponse(find(id));
    }

    @Transactional
    public PersonResponse create(PersonRequest req) {
        Person p = new Person();
        apply(p, req);
        return mapper.toPersonResponse(personRepository.save(p));
    }

    @Transactional
    public PersonResponse update(Long id, PersonRequest req) {
        Person p = find(id);
        apply(p, req);
        return mapper.toPersonResponse(personRepository.save(p));
    }

    @Transactional
    public void delete(Long id) {
        if (!personRepository.existsById(id)) {
            throw new ResourceNotFoundException("Person", id);
        }
        personRepository.deleteById(id);
    }

    /**
     * Record that this person has left the company, or that they are back.
     *
     * <p>Set on the person, never copied down onto their contacts. One statement, one place
     * to undo it: flagging five addresses individually is the same fact asserted five times,
     * and the sixth address added next month would quietly miss it. It also keeps the
     * addresses' own flags meaning what they say — a mailbox that still works is not marked
     * dead just because the person behind it moved on.
     *
     * <p>Nothing is deleted and the company link is left alone. History references the
     * person by id, the addresses stay searchable in the mailbox, and "who did we deal with
     * there before?" keeps its answer. Only the mail stops.
     */
    @Transactional
    public PersonResponse setHasLeft(Long id, boolean hasLeft) {
        Person p = find(id);
        p.setHasLeft(hasLeft);
        return mapper.toPersonResponse(personRepository.save(p));
    }

    private Person find(Long id) {
        return personRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
    }

    private void apply(Person p, PersonRequest req) {
        p.setFullName(req.getFullName());
        p.setTitle(req.getTitle());
        p.setJobTitle(blankToNull(req.getJobTitle()));
        p.setGreetingName(req.getGreetingName());
        p.setNotes(req.getNotes());
        p.setCompany(resolveCompany(req.getCompanyId()));
    }

    /**
     * A field cleared in the form arrives as "" rather than absent. Stored as null so
     * "no position on file" is one value in the column and not two, and so the response —
     * which omits nulls — leaves the field out instead of sending an empty string that
     * every screen would have to test for separately.
     */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Company resolveCompany(Long companyId) {
        if (companyId == null) return null;
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
    }

    /** Shared by the paged search and the bulk collect, so both see the same result set. */
    private Specification<Person> buildSpec(PeopleFilter f) {
        return Specification.allOf(
                PersonSpecification.companyIdEquals(f.companyId()),
                PersonSpecification.nameContains(f.name()),
                PersonSpecification.hasContactMatching(
                        f.contactValue(), f.contactKind(), f.confirmed(), f.includeBanned(), f.legacy()));
    }

    /** Filter holder so the controller stays readable with many optional params. */
    public record PeopleFilter(
            String name, Long companyId,
            String contactValue, String contactKind,
            Boolean confirmed, boolean includeBanned, Boolean legacy) {
    }
}
