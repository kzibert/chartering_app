package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Where a ship is free, and when — one row per position reported, not one per vessel.
 *
 * <p>That is the whole design decision, and everything on the Open Fleet tab follows from
 * it:
 *
 * <ul>
 *   <li>A position is a fact with a date on it. "SPOT AT MARMARA" was true on Monday and is
 *       a lie by Friday, and a column overwritten in place leaves no way to know which.
 *   <li>The same hull is reported by several people, and they disagree — about the dates,
 *       sometimes about the deadweight. Two rows is the honest record; one row is whichever
 *       email synced last.
 *   <li>A vessel that has opened in the Adriatic five times this year is a vessel to
 *       remember when an Adriatic cargo lands, and that pattern only exists if the old rows
 *       were kept.
 *   <li>The parser that lands later inserts rows and never has to reason about what it is
 *       replacing — which is the kind of reasoning a parser gets wrong quietly.
 * </ul>
 *
 * <p>The Open Fleet tab therefore shows the newest live row per vessel, and the vessel's own
 * record shows the list.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_positions")
public class VesselPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PositionStatus status = PositionStatus.LIVE;

    // ---- where she opens ----
    // Said three ways for the reason a cargo's load point is: "SALERNO 1/2 SEPT" names a
    // port, "SPOT AT MARMARA" names only an area, "= MOROCCO 7/9" names a country.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_port_id")
    private Port openPort;

    @Column(name = "open_port_text", length = 160)
    private String openPortText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "open_area_id")
    private TradeArea openArea;

    // ---- when ----

    @Column(name = "open_from")
    private LocalDate openFrom;

    @Column(name = "open_to")
    private LocalDate openTo;

    /** What was written: "01 / 02 SEPT", "SPOT", "PPT". */
    @Column(name = "open_text", length = 80)
    private String openText;

    /**
     * What she just carried. Asked about constantly and recorded almost never: a hold that
     * last had cement in it is not offered for grain without a cleaning conversation.
     */
    @Column(name = "last_cargo", length = 160)
    private String lastCargo;

    /**
     * What her owners want next. Free text on purpose — "prefers grain, no scrap, no
     * Israel, min 20 days duration" is one sentence in an email and would be five badly
     * fitting columns here. The hard constraints Match can actually test live on the vessel
     * as fitted flags; this is for the broker to read.
     */
    @Column(name = "cargo_preferences", columnDefinition = "text")
    private String cargoPreferences;

    // ---- who said so ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_company_id")
    private Company reportedByCompany;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_person_id")
    private Person reportedByPerson;

    @Column(name = "from_mail", nullable = false)
    private boolean fromMail = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_mail_message_id")
    private MailMessage sourceMailMessage;

    /**
     * When we were told, which is not when the row was written: a list read out of a
     * three-day-old email is three days old, and staleness is the first thing the Open
     * Fleet tab has to show.
     */
    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt = OffsetDateTime.now();

    @Column(columnDefinition = "text")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
