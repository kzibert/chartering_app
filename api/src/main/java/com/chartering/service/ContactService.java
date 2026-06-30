package com.chartering.service;

import com.chartering.dto.*;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.specification.ContactSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final PersonRepository personRepository;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<ContactResponse> search(String kind, String value, Long companyId,
                                                Boolean confirmed, boolean includeBanned,
                                                Pageable pageable) {
        Specification<Contact> spec = Specification.allOf(
                ContactSpecification.kindEquals(kind),
                ContactSpecification.valueContains(value),
                ContactSpecification.companyIdEquals(companyId),
                ContactSpecification.confirmedEquals(confirmed),
                ContactSpecification.excludeBanned(includeBanned));
        return PageResponse.from(contactRepository.findAll(spec, pageable).map(mapper::toContactResponse));
    }

    @Transactional(readOnly = true)
    public ContactResponse get(Long id) {
        return mapper.toContactResponse(find(id));
    }

    @Transactional
    public ContactResponse create(ContactRequest req) {
        Contact ct = new Contact();
        apply(ct, req);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    @Transactional
    public ContactResponse update(Long id, ContactRequest req) {
        Contact ct = find(id);
        apply(ct, req);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    @Transactional
    public void delete(Long id) {
        if (!contactRepository.existsById(id)) {
            throw new ResourceNotFoundException("Contact", id);
        }
        contactRepository.deleteById(id);
    }

    @Transactional
    public ContactResponse setConfirmed(Long id, boolean confirmed, ConfirmRequest req) {
        Contact ct = find(id);
        ct.setConfirmed(confirmed);
        ct.setConfirmedAt(confirmed ? OffsetDateTime.now() : null);
        ct.setConfirmedBy(confirmed && req != null ? req.getConfirmedBy() : null);
        ct.setConfirmNotes(confirmed && req != null ? req.getConfirmNotes() : null);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    private Contact find(Long id) {
        return contactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contact", id));
    }

    private void apply(Contact ct, ContactRequest req) {
        ct.setContactKind(req.getContactKind());
        ct.setContactValue(req.getContactValue());
        ct.setNotes(req.getNotes());
        ct.setPerson(resolvePerson(req.getPersonId()));
        ct.setCompany(resolveCompany(req.getCompanyId()));
    }

    private Person resolvePerson(Long personId) {
        if (personId == null) return null;
        return personRepository.findById(personId)
                .orElseThrow(() -> new ResourceNotFoundException("Person", personId));
    }

    private Company resolveCompany(Long companyId) {
        if (companyId == null) return null;
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));
    }
}
