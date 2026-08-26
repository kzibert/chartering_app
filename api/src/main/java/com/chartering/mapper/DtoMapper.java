package com.chartering.mapper;

import com.chartering.audit.RevertSupport;
import com.chartering.dto.*;
import com.chartering.model.AnalysisSample;
import com.chartering.model.Cargo;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.DataChange;
import com.chartering.model.MailFolder;
import com.chartering.model.MailMessage;
import com.chartering.model.MailRule;
import com.chartering.model.Person;
import com.chartering.model.Port;
import com.chartering.model.TradeArea;
import com.chartering.model.Vessel;
import com.chartering.model.VesselExName;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entity -> response-DTO mapping. Centralized so services stay thin and mapping is
 * consistent. Only touches already-loaded associations (owner is fetched via EntityGraph
 * on the read paths) to avoid surprise lazy loads.
 */
@Component
public class DtoMapper {

    /**
     * A vessel without her former names — the shape every existing caller wanted.
     *
     * <p>Kept as the default rather than lazily loading the collection here: the ex-names
     * are a separate table, and a mapper that fetched them per row would put a query behind
     * every line of a twenty-row page. Callers that want them read them in one go and use
     * the overload.
     */
    public VesselResponse toVesselResponse(Vessel v) {
        return toVesselResponse(v, List.of());
    }

    public VesselResponse toVesselResponse(Vessel v, List<VesselExNameResponse> exNames) {
        Company owner = v.getOwner();
        return new VesselResponse(
                v.getId(), v.getName(), v.getImoNumber(),
                v.getDeadweightTonnage(), v.getDeadweightCargoCapacity(),
                v.getGrainCapacityM3(), v.getBaleCapacityM3(), v.getMaximumDraft(),
                v.getYearBuilt(), v.getVesselType(), v.getFlag(),
                v.getGeared(), v.getGearDescription(), v.getHolds(), v.getHatches(),
                v.getGrainFitted(), v.getTimberFitted(), v.getImoFitted(), v.getIceClass(),
                // Empty rather than null: an absent list and a vessel that has never been
                // renamed are the same thing to every caller, and NON_NULL would send the
                // first as nothing while sending the second as [].
                exNames == null ? List.of() : exNames,
                owner != null ? owner.getId() : null,
                owner != null ? owner.getName() : null,
                v.getNotes(),
                v.isConfirmed(), v.getConfirmedAt(), v.getConfirmedBy(), v.getConfirmNotes(),
                v.isBanned(), v.isLegacy());
    }

    public VesselExNameResponse toVesselExNameResponse(VesselExName e) {
        return new VesselExNameResponse(
                e.getId(), e.getVessel().getId(), e.getName(),
                e.getSource(), e.getRenamedAt(), e.getNotes());
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
                c.getCityName(), c.getCountry(), c.getWebsite(), c.getNotes(),
                c.isConfirmed(), c.getConfirmedAt(), c.getConfirmedBy(), c.getConfirmNotes(),
                c.isBanned(), c.isLegacy(), noWorkingEmail);
    }

    public ContactResponse toContactResponse(Contact ct) {
        Person p = ct.getPerson();
        return new ContactResponse(
                ct.getId(),
                p != null ? p.getId() : null,
                p != null ? p.getFullName() : null,
                p != null && p.isHasLeft(),
                p != null ? p.getTitle() : null,
                p != null ? p.getJobTitle() : null,
                effectiveGreeting(ct),
                blankToNull(ct.getGreetingName()),
                p == null && ct.getCompany() != null,
                ct.getCompany() != null ? ct.getCompany().getId() : null,
                ct.getCompany() != null ? ct.getCompany().getName() : null,
                ct.getContactKind(), ct.getContactValue(), blankToNull(ct.getLabel()), ct.getNotes(),
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

    /**
     * One change-log entry.
     *
     * <p>{@code revertible} is worked out here rather than stored, because it is a question
     * about the code as it stands now — whether the field still exists and whether its type
     * can be read back — not about what was true when the change was made. A log row written
     * before a field was renamed is still a true record of what happened; it has simply
     * stopped being something this version can put back.
     */
    public DataChangeResponse toDataChangeResponse(DataChange d) {
        String blocked = RevertSupport.blockedReason(d);
        return new DataChangeResponse(
                d.getId(), d.getChangeSet(), d.getEntityType(), d.getEntityId(),
                d.getEntityLabel(), d.getOperation(), d.getFieldName(),
                d.getOldValue(), d.getNewValue(),
                d.getChangedAt(), d.getChangedBy(), d.getContext(),
                blocked == null, blocked);
    }

    public PersonResponse toPersonResponse(Person p) {
        Company c = p.getCompany();
        return new PersonResponse(
                p.getId(), p.getFullName(), p.getTitle(), p.getJobTitle(), p.getGreetingName(),
                c != null ? c.getId() : null,
                c != null ? c.getName() : null,
                p.getNotes(), p.isLegacy(), p.isHasLeft());
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
                m.getImapFolder(),
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
    public MailMessageDetailResponse toMailMessageDetail(MailMessage m, String sanitizedHtml,
                                                        LocalDateTime repliedAt) {
        return new MailMessageDetailResponse(
                toMailMessageResponse(m), m.getToAddresses(), m.getCcAddresses(),
                sanitizedHtml, m.getBodyText(), m.getAttachmentNames(), m.getSizeBytes(),
                m.getMessageId(), repliedAt);
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

    /**
     * One row of the training corpus.
     *
     * <p>The snippet is passed in rather than derived here for the same reason
     * {@code noWorkingEmail} is on the company mapping: this mapper holds no collaborators
     * and does no work of its own, and a body flattened one way here and another way in the
     * service would put two different previews of the same sample on one screen.
     *
     * <p>{@code mailMessage} is a lazy association read only for its id, which is already
     * loaded on the proxy — so no query fires and a message deleted from the mailbox since
     * simply reports null.
     */
    public AnalysisSampleResponse toAnalysisSampleResponse(AnalysisSample s, String snippet) {
        return new AnalysisSampleResponse(
                s.getId(), s.getSource(),
                s.getMailMessage() != null ? s.getMailMessage().getId() : null,
                s.getFromAddress(), s.getFromName(), s.getSubject(),
                s.getSentAt(), s.getReceivedAt(), snippet, s.getAttachmentNames(),
                s.getLabel(), s.getStatus(),
                s.getAnnotation() != null,
                s.getBodyText() != null ? s.getBodyText().length() : 0,
                s.getNotes(), s.getCreatedAt(), s.getUpdatedAt());
    }

    /**
     * A cargo, with each place sent as id, name and raw text.
     *
     * <p>The area a place resolves to is the port's own when there is a port and the cargo's
     * area column when there is not — the same precedence Match applies, kept in one place
     * so the screen cannot show one thing while the scoring reads another.
     */
    public CargoResponse toCargoResponse(Cargo c) {
        TradeArea loadArea = effectiveArea(c.getLoadPort(), c.getLoadArea());
        TradeArea dischargeArea = effectiveArea(c.getDischargePort(), c.getDischargeArea());
        Company charterer = c.getChartererCompany();
        Company broker = c.getBrokerCompany();
        Person brokerPerson = c.getBrokerPerson();
        return new CargoResponse(
                c.getId(), c.getStatus().name(), c.getStatusNote(),
                c.getCommodity(), c.getStowageFactor(),
                c.getQuantity(), c.getQuantityUnit(), c.getQuantityTolerance(),
                c.getQuantityMin(), c.getQuantityMax(),
                c.getLoadPort() != null ? c.getLoadPort().getId() : null,
                c.getLoadPort() != null ? c.getLoadPort().getName() : null,
                c.getLoadPortText(),
                loadArea != null ? loadArea.getId() : null,
                loadArea != null ? loadArea.getCode() : null,
                loadArea != null ? loadArea.getName() : null,
                c.getDischargePort() != null ? c.getDischargePort().getId() : null,
                c.getDischargePort() != null ? c.getDischargePort().getName() : null,
                c.getDischargePortText(),
                dischargeArea != null ? dischargeArea.getId() : null,
                dischargeArea != null ? dischargeArea.getCode() : null,
                dischargeArea != null ? dischargeArea.getName() : null,
                c.getLaycanFrom(), c.getLaycanTo(), c.getLaycanText(),
                c.getMaxDraft(), c.getMinDwt(), c.getMaxDwt(), c.getMaxAgeYears(),
                c.getRequiresGeared(), c.getRequiresGrainFitted(), c.getRequiresImoFitted(),
                c.getFreightIdea(), c.getCommission(), c.getTerms(),
                c.getLoadRate(), c.getDischargeRate(),
                charterer != null ? charterer.getId() : null,
                charterer != null ? charterer.getName() : null,
                broker != null ? broker.getId() : null,
                broker != null ? broker.getName() : null,
                brokerPerson != null ? brokerPerson.getId() : null,
                brokerPerson != null ? brokerPerson.getFullName() : null,
                c.isFromMail(),
                c.getSourceMailMessage() != null ? c.getSourceMailMessage().getId() : null,
                c.getReceivedAt(), c.getNotes(), c.getCreatedAt(), c.getUpdatedAt());
    }

    /**
     * The area a place actually sits in: the port's, else the one entered by hand.
     *
     * <p>A named port wins because it is the more precise statement — somebody who wrote
     * "Salerno" said more than somebody who wrote "W.Med", and if the two disagree the port
     * is the one that was looked up rather than typed.
     */
    public static TradeArea effectiveArea(Port port, TradeArea fallback) {
        if (port != null && port.getTradeArea() != null) return port.getTradeArea();
        return fallback;
    }
}
