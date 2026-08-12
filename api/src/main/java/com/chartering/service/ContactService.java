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
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final PersonRepository personRepository;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public PageResponse<ContactResponse> search(String kind, String value, Long companyId,
                                                Long personId, Boolean confirmed,
                                                boolean includeBanned,
                                                Boolean legacy, Pageable pageable) {
        Specification<Contact> spec = Specification.allOf(
                ContactSpecification.kindEquals(kind),
                ContactSpecification.valueContains(value),
                ContactSpecification.companyIdEquals(companyId),
                ContactSpecification.personIdEquals(personId),
                ContactSpecification.confirmedEquals(confirmed),
                ContactSpecification.excludeBanned(includeBanned),
                ContactSpecification.legacyEquals(legacy));
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
    public ContactResponse setBanned(Long id, boolean banned) {
        Contact ct = find(id);
        ct.setBanned(banned);
        return mapper.toContactResponse(contactRepository.save(ct));
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

    /**
     * Flag this contact as its company's main email/phone, demoting whatever held that
     * slot before. Demoting is part of the same transaction as promoting, so the partial
     * unique index never sees two mains at once.
     */
    @Transactional
    public ContactResponse setMain(Long id, boolean main) {
        Contact ct = find(id);
        if (main) {
            if (ct.getCompany() == null) {
                throw new IllegalArgumentException(
                        "Contact " + id + " belongs to no company, so it cannot be a company's main contact");
            }
            contactRepository.clearMain(ct.getCompany().getId(), ct.getContactKind(), id);
        }
        ct.setMain(main);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    /**
     * Flag an address/number as dead (or revive it). The main flag is deliberately left
     * alone: a company's main email can be dead, and the bulk collection simply falls
     * through to the next working one — so reviving it later restores the old preference.
     */
    @Transactional
    public ContactResponse setWorking(Long id, boolean working) {
        Contact ct = find(id);
        ct.setWorking(working);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    private Contact find(Long id) {
        return contactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contact", id));
    }

    private void apply(Contact ct, ContactRequest req) {
        Company newCompany = resolveCompany(req.getCompanyId());
        // Moving a main contact to another company (or to none) surrenders the main slot:
        // the target may already have one, and keeping the flag would breach the unique index.
        Long oldCompanyId = ct.getCompany() != null ? ct.getCompany().getId() : null;
        Long newCompanyId = newCompany != null ? newCompany.getId() : null;
        if (ct.isMain() && !Objects.equals(oldCompanyId, newCompanyId)) {
            ct.setMain(false);
        }
        // Likewise for a kind change (email <-> phone): main is per company *and* kind.
        if (ct.isMain() && !Objects.equals(ct.getContactKind(), req.getContactKind())) {
            ct.setMain(false);
        }
        ct.setContactKind(req.getContactKind());
        ct.setContactValue(req.getContactValue());
        ct.setNotes(req.getNotes());
        ct.setPerson(resolvePerson(req.getPersonId()));
        ct.setCompany(newCompany);
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
