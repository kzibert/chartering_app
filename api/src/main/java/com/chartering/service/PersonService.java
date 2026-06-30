package com.chartering.service;

import com.chartering.dto.PersonRequest;
import com.chartering.dto.PersonResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Person;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PersonService {

    private final PersonRepository personRepository;
    private final CompanyRepository companyRepository;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public List<PersonResponse> list(Long companyId) {
        List<Person> people = companyId != null
                ? personRepository.findByCompanyId(companyId)
                : personRepository.findAll();
        return people.stream().map(mapper::toPersonResponse).toList();
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
}
