package com.chartering.mapper;

import com.chartering.dto.*;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.Person;
import com.chartering.model.Vessel;
import org.springframework.stereotype.Component;

/**
 * Entity -> response-DTO mapping. Centralized so services stay thin and mapping is
 * consistent. Only touches already-loaded associations (owner is fetched via EntityGraph
 * on the read paths) to avoid surprise lazy loads.
 */
@Component
public class DtoMapper {

    public VesselResponse toVesselResponse(Vessel v) {
        Company owner = v.getOwner();
        return new VesselResponse(
                v.getId(), v.getName(), v.getImoNumber(),
                v.getDeadweightTonnage(), v.getDeadweightCargoCapacity(),
                v.getGrainCapacityM3(), v.getBaleCapacityM3(), v.getMaximumDraft(),
                v.getYearBuilt(), v.getVesselType(), v.getFlag(),
                owner != null ? owner.getId() : null,
                owner != null ? owner.getName() : null,
                v.getNotes(),
                v.isConfirmed(), v.getConfirmedAt(), v.getConfirmedBy(), v.getConfirmNotes(),
                v.isBanned(), v.isLegacy());
    }

    public CompanyResponse toCompanyResponse(Company c) {
        return new CompanyResponse(
                c.getId(), c.getName(),
                c.isShipowner(), c.isCharterer(), c.isBroker(), c.isAgent(),
                c.getCityName(), c.getNotes(),
                c.isConfirmed(), c.getConfirmedAt(), c.getConfirmedBy(), c.getConfirmNotes(),
                c.isBanned(), c.isLegacy());
    }

    public ContactResponse toContactResponse(Contact ct) {
        Person p = ct.getPerson();
        return new ContactResponse(
                ct.getId(),
                p != null ? p.getId() : null,
                p != null ? p.getFullName() : null,
                p != null ? p.getTitle() : null,
                p != null ? p.getGreetingName() : null,
                ct.getCompany() != null ? ct.getCompany().getId() : null,
                ct.getCompany() != null ? ct.getCompany().getName() : null,
                ct.getContactKind(), ct.getContactValue(), ct.getNotes(),
                ct.isConfirmed(), ct.getConfirmedAt(), ct.getConfirmedBy(), ct.getConfirmNotes(),
                ct.isBanned(), ct.isLegacy());
    }

    public PersonResponse toPersonResponse(Person p) {
        Company c = p.getCompany();
        return new PersonResponse(
                p.getId(), p.getFullName(), p.getTitle(), p.getGreetingName(),
                c != null ? c.getId() : null,
                c != null ? c.getName() : null,
                p.getNotes(), p.isLegacy());
    }
}
