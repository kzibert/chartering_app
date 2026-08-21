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

    /**
     * Flag an address for use in circulations, or clear it. Unlike {@link #setMain} nothing
     * is demoted: any number of addresses per company or person may carry the flag, because
     * "who gets the circular" can legitimately be three people at one desk.
     */
    @Transactional
    public ContactResponse setCirc(Long id, boolean circ) {
        Contact ct = find(id);
        if (circ && !"email".equalsIgnoreCase(ct.getContactKind())) {
            throw new IllegalArgumentException(
                    "Only email contacts can be flagged for circulations — contact " + id
                            + " is a " + ct.getContactKind() + ".");
        }
        ct.setCirc(circ);
        if (circ) {
            // The two are exact opposites, so holding both would leave the address in a
            // state no rule could read. The flag just set wins — it is the one the user
            // chose most recently, and silently refusing the click would be worse.
            ct.setNoCirc(false);
        }
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    /**
     * Flag an address as never to be circulated to, or clear it.
     *
     * <p>Deliberately not the same as marking it not-working. The address is fine and stays
     * the right one to write to by hand; it is only bulk mail it must be kept out of. Using
     * the dead flag for this would hide the address everywhere and destroy the distinction
     * between a mailbox that bounced and a person who asked to be left off the circular.
     *
     * <p>Phones are never circulated, so flagging one would be meaningless rather than
     * merely redundant — hence the refusal instead of a silent no-op.
     */
    @Transactional
    public ContactResponse setNoCirc(Long id, boolean noCirc) {
        Contact ct = find(id);
        if (noCirc && !"email".equalsIgnoreCase(ct.getContactKind())) {
            throw new IllegalArgumentException(
                    "Only email contacts can be flagged as not for circulations — contact " + id
                            + " is a " + ct.getContactKind() + ".");
        }
        ct.setNoCirc(noCirc);
        if (noCirc) {
            ct.setCirc(false);
        }
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    /**
     * Record that this number is (or is no longer) on WhatsApp.
     *
     * <p>Set by hand on purpose. Nothing here checks WhatsApp — no open API answers "is this
     * number registered", so the UI opens a wa.me link with the configured greeting prefilled
     * and the user reports what they saw. The flag is therefore an observation with a date on
     * it only in the user's head; it is never inferred, refreshed or cleared automatically.
     *
     * <p>Email contacts are refused rather than silently ignored: an address can never be on
     * WhatsApp, so the click can only be a mistake worth surfacing.
     */
    @Transactional
    public ContactResponse setHasWhatsapp(Long id, boolean hasWhatsapp) {
        Contact ct = find(id);
        if (hasWhatsapp && !"phone".equalsIgnoreCase(ct.getContactKind())) {
            throw new IllegalArgumentException(
                    "Only phone contacts can be on WhatsApp — contact " + id
                            + " is a " + ct.getContactKind() + ".");
        }
        ct.setHasWhatsapp(hasWhatsapp);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    /**
     * Refile an address under a different person, or under the company itself.
     *
     * <p>Its own operation rather than a {@code PUT} with the whole contact, because moving
     * one is exactly when the caller has the least reason to be re-sending every other field.
     * A drag that also had to carry the notes, the greeting and the kind is a drag that can
     * silently blank one of them; this endpoint cannot, because it is not given them.
     *
     * <p>The {@code main} flag survives a move between people at the same company, and that is
     * deliberate: main answers "which address reaches this company", which does not change
     * because the address moved desks. Moving to a <em>different</em> company surrenders it —
     * the target may already have a main of that kind, and the unique index would refuse.
     *
     * @param personId  who to file it under, or null for the company itself
     * @param companyId the company; null only ever makes sense alongside a person
     */
    @Transactional
    public ContactResponse assign(Long id, Long personId, Long companyId) {
        if (personId == null && companyId == null) {
            throw new IllegalArgumentException(
                    "A contact must belong to a person or to a company. Leave the person "
                            + "empty for an address that belongs to the company itself.");
        }
        Contact ct = find(id);
        Person person = resolvePerson(personId);
        Company company = resolveCompany(companyId);
        Long oldCompanyId = ct.getCompany() != null ? ct.getCompany().getId() : null;
        Long newCompanyId = company != null ? company.getId() : null;
        if (ct.isMain() && !Objects.equals(oldCompanyId, newCompanyId)) {
            ct.setMain(false);
        }
        ct.setPerson(person);
        ct.setCompany(company);
        return mapper.toContactResponse(contactRepository.save(ct));
    }

    private Contact find(Long id) {
        return contactRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contact", id));
    }

    private void apply(Contact ct, ContactRequest req) {
        // A contact hanging off neither a person nor a company is reachable from no screen
        // in the app: the company drawer lists by company, the people tab by person, and
        // the vessel drawer through the owner. It would still be in the table, and still be
        // mailed by any list it already sat in — findable only by searching for the address
        // itself. Refused rather than saved, because the shape is never what was meant:
        // clearing both boxes is how somebody tries to detach an address, not how they
        // choose to lose it.
        if (req.getPersonId() == null && req.getCompanyId() == null) {
            throw new IllegalArgumentException(
                    "A contact must belong to a person or to a company. Leave the person "
                            + "empty for an address that belongs to the company itself.");
        }
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
        // Blank is stored as null, not as "": the two would read the same on screen but
        // differently in the merge, where an empty string is a greeting somebody chose and
        // null is the absence that falls through to the person's, then to "Sirs".
        ct.setGreetingName(blankToNull(req.getGreetingName()));
        ct.setPerson(resolvePerson(req.getPersonId()));
        ct.setCompany(newCompany);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
