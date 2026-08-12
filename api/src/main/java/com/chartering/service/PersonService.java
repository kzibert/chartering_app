package com.chartering.service;

import com.chartering.dto.ContactResponse;
import com.chartering.dto.PageResponse;
import com.chartering.dto.PersonDetailResponse;
import com.chartering.dto.PersonRequest;
import com.chartering.dto.PersonResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
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
        Specification<Person> spec = Specification.allOf(
                PersonSpecification.companyIdEquals(f.companyId()),
                PersonSpecification.nameContains(f.name()),
                PersonSpecification.hasContactMatching(
                        f.contactValue(), f.contactKind(), f.confirmed(), f.includeBanned(), f.legacy()));

        Page<Person> page = personRepository.findAll(spec, pageable);
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

    private Person find(Long id) {
        return personRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Person", id));
    }

    private void apply(Person p, PersonRequest req) {
        p.setFullName(req.getFullName());
        p.setTitle(req.getTitle());
        p.setGreetingName(req.getGreetingName());
        p.setNotes(req.getNotes());
        p.setCompany(resolveCompany(req.getCompanyId()));
    }

    private Company resolveCompany(Long companyId) {
        if (companyId == null) return null;
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
    }

    /** Filter holder so the controller stays readable with many optional params. */
    public record PeopleFilter(
            String name, Long companyId,
            String contactValue, String contactKind,
            Boolean confirmed, boolean includeBanned, Boolean legacy) {
    }
}
