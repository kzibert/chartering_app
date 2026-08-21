package com.chartering.mapper;

import com.chartering.dto.*;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.MailFolder;
import com.chartering.model.MailMessage;
import com.chartering.model.MailRule;
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

    /**
     * {@code noWorkingEmail} is derived from the company's contacts, which this mapper
     * cannot see — callers must resolve it (batched, for lists) and pass it in. There is
     * deliberately no single-argument overload: defaulting it to false would quietly
     * under-report dead companies wherever someone forgot.
     */
    public CompanyResponse toCompanyResponse(Company c, boolean noWorkingEmail) {
        return new CompanyResponse(
                c.getId(), c.getName(),
                c.isShipowner(), c.isCharterer(), c.isBroker(), c.isAgent(), c.isSolo(),
                c.getCityName(), c.getNotes(),
                c.isConfirmed(), c.getConfirmedAt(), c.getConfirmedBy(), c.getConfirmNotes(),
                c.isBanned(), c.isLegacy(), noWorkingEmail);
    }

    public ContactResponse toContactResponse(Contact ct) {
        Person p = ct.getPerson();
        return new ContactResponse(
                ct.getId(),
                p != null ? p.getId() : null,
                p != null ? p.getFullName() : null,
                p != null ? p.getTitle() : null,
                effectiveGreeting(ct),
                blankToNull(ct.getGreetingName()),
                p == null && ct.getCompany() != null,
                ct.getCompany() != null ? ct.getCompany().getId() : null,
                ct.getCompany() != null ? ct.getCompany().getName() : null,
                ct.getContactKind(), ct.getContactValue(), ct.getNotes(),
                ct.isConfirmed(), ct.getConfirmedAt(), ct.getConfirmedBy(), ct.getConfirmNotes(),
                ct.isBanned(), ct.isLegacy(), ct.isMain(), ct.isWorking(), ct.isCirc(), ct.isNoCirc(),
                ct.isHasWhatsapp());
    }

    /**
     * The greeting for one address: its own override, else the person's.
     *
     * <p>Stops here rather than falling through to the person's full name or to "Sirs".
     * Those last two steps belong to the merge, which applies them per placeholder — the
     * contact row and the circulation list want to show "no greeting on file" as blank,
     * not to display a fallback as though somebody had chosen it.
     *
     * <p>A company-wide address with nothing typed on it therefore comes back null and is
     * greeted generally, which is the intended default.
     */
    public static String effectiveGreeting(Contact ct) {
        String own = blankToNull(ct.getGreetingName());
        if (own != null) {
            return own;
        }
        return ct.getPerson() != null ? blankToNull(ct.getPerson().getGreetingName()) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public PersonResponse toPersonResponse(Person p) {
        Company c = p.getCompany();
        return new PersonResponse(
                p.getId(), p.getFullName(), p.getTitle(), p.getGreetingName(),
                c != null ? c.getId() : null,
                c != null ? c.getName() : null,
                p.getNotes(), p.isLegacy());
    }

    /**
     * A message as one row of the list. Reads folder, company and person, which the search
     * loads through an entity graph — see {@code MailMessageRepository#findAll}.
     */
    public MailMessageResponse toMailMessageResponse(MailMessage m) {
        MailFolder f = m.getFolder();
        Company c = m.getCompany();
        Person p = m.getPerson();
        return new MailMessageResponse(
                m.getId(), m.getFromAddress(), m.getFromName(), m.getSubject(), m.getSnippet(),
                m.getSentAt(), m.getReceivedAt(), m.isRead(), m.isHasAttachments(),
                f != null ? f.getId() : null,
                f != null ? f.getName() : null,
                m.getFiledByRuleId(),
                c != null ? c.getId() : null,
                c != null ? c.getName() : null,
                p != null ? p.getId() : null,
                p != null ? p.getFullName() : null,
                m.isLinkManual());
    }

    /**
     * The opened message. The HTML body is sanitized by the caller, not here — this mapper
     * holds no collaborators, and quietly returning unsanitized markup from one path while
     * the other cleaned it is exactly the inconsistency worth avoiding.
     */
    public MailMessageDetailResponse toMailMessageDetail(MailMessage m, String sanitizedHtml) {
        return new MailMessageDetailResponse(
                toMailMessageResponse(m), m.getToAddresses(), m.getCcAddresses(),
                sanitizedHtml, m.getBodyText(), m.getAttachmentNames(), m.getSizeBytes(),
                m.getMessageId());
    }

    public MailRuleResponse toMailRuleResponse(MailRule r) {
        return new MailRuleResponse(
                r.getId(), r.getName(), r.getFolder().getId(), r.getFolder().getName(),
                r.isEnabled(), r.getSortOrder(), r.getMatchType().name(), r.isMarkRead(),
                r.getConditions().stream()
                        .map(c -> new MailRuleConditionResponse(
                                c.getId(), c.getField().name(), c.getOperator().name(), c.getValue()))
                        .toList());
    }
}
