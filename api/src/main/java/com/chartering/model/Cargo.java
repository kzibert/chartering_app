package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A cargo in hand: a charterer's requirement, as it arrived.
 *
 * <p>Almost every field is nullable, and that is the design rather than laziness. A real
 * first email says "25,000 MT Wheat +/- 10%, Chornomorsk to Spain Med, geared bulker abt
 * 28-35,000 DWT, laycan please advise" and stops. A record that cannot be saved until it is
 * complete is a record that gets kept on paper instead, and the whole point of this table is
 * that Match has something to read.
 *
 * <p>The field names track the cargo half of the mail-corpus annotation template on purpose.
 * When the email parser lands, its output should drop into these columns without a
 * translation layer — and a second set of names for the same facts is exactly what a
 * translation layer grows out of.
 */
@Getter
@Setter
@Entity
@Table(name = "cargoes")
public class Cargo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CargoStatus status = CargoStatus.OPEN;

    @Column(name = "status_note", columnDefinition = "text")
    private String statusNote;

    @Column(nullable = false, length = 120)
    private String commodity;

    /**
     * Cubic feet per tonne. The number that decides whether a cargo cubes out before it
     * weighs out, and the only way a quantity can be compared against a grain capacity
     * rather than only against deadweight.
     */
    @Column(name = "stowage_factor")
    private BigDecimal stowageFactor;

    // ---- quantity: what was said, and what Match compares ----
    // quantity + quantityTolerance are the email's words ("25,000 MT", "+/- 10%").
    // quantityMin/Max are the range a hull is tested against, derived from those two when
    // the tolerance is a plain percentage and typed by hand when it is not - MOLOO, MOLCO
    // and "10 pct in owner's option" are all real and none of them is arithmetic.

    private BigDecimal quantity;

    @Column(name = "quantity_unit", nullable = false, length = 10)
    private String quantityUnit = "MT";

    @Column(name = "quantity_tolerance", length = 30)
    private String quantityTolerance;

    @Column(name = "quantity_min")
    private BigDecimal quantityMin;

    @Column(name = "quantity_max")
    private BigDecimal quantityMax;

    // ---- where it loads and discharges ----
    // Three columns each, because an email gives whichever it knows: a port, an area, or a
    // phrase naming neither. Match reads the port's own area when there is a port and the
    // area column when there is not.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "load_port_id")
    private Port loadPort;

    @Column(name = "load_port_text", length = 160)
    private String loadPortText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "load_area_id")
    private TradeArea loadArea;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discharge_port_id")
    private Port dischargePort;

    @Column(name = "discharge_port_text", length = 160)
    private String dischargePortText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discharge_area_id")
    private TradeArea dischargeArea;

    // ---- the laycan ----

    @Column(name = "laycan_from")
    private LocalDate laycanFrom;

    @Column(name = "laycan_to")
    private LocalDate laycanTo;

    /** What the email said, including "please advise suitable open tonnage". */
    @Column(name = "laycan_text", length = 80)
    private String laycanText;

    // ---- what the cargo requires of the ship ----
    // Null is "the charterer has not said", which is not "no limit". Match reports which of
    // the two it is looking at rather than assuming either.

    @Column(name = "max_draft")
    private BigDecimal maxDraft;

    @Column(name = "min_dwt")
    private BigDecimal minDwt;

    @Column(name = "max_dwt")
    private BigDecimal maxDwt;

    @Column(name = "max_age_years")
    private Short maxAgeYears;

    @Column(name = "requires_geared")
    private Boolean requiresGeared;

    @Column(name = "requires_grain_fitted")
    private Boolean requiresGrainFitted;

    @Column(name = "requires_imo_fitted")
    private Boolean requiresImoFitted;

    // ---- commercials, all free text ----
    // "USD 25 pmt", "abt 24.50 fio", "lumpsum 120k" and "market related" turn up in the same
    // week; a numeric column would keep the first and lose the rest.

    @Column(name = "freight_idea", length = 120)
    private String freightIdea;

    @Column(length = 60)
    private String commission;

    @Column(length = 200)
    private String terms;

    @Column(name = "load_rate", length = 60)
    private String loadRate;

    @Column(name = "discharge_rate", length = 60)
    private String dischargeRate;

    // ---- who it came from ----
    // Three separate links rather than one counterparty: the charterer is often not known at
    // first, because the enquiry arrives through a broker who is not saying yet.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charterer_company_id")
    private Company chartererCompany;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_company_id")
    private Company brokerCompany;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broker_person_id")
    private Person brokerPerson;

    /**
     * Read out of an email rather than typed. Stays true after the message is gone:
     * {@code mail_messages} mirrors a server whose folders get cleaned out, and how this
     * cargo reached the desk is a fact about the cargo.
     */
    @Column(name = "from_mail", nullable = false)
    private boolean fromMail = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_mail_message_id")
    private MailMessage sourceMailMessage;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(columnDefinition = "text")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
