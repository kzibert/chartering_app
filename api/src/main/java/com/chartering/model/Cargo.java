package com.chartering.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.TenantId;
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

    /** The desk this row belongs to. Written and filtered by Hibernate - see TenantIdentifierResolver. */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

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

    /**
     * How far this desk will ballast a ship for this cargo, in days.
     *
     * <p>Null is "use the desk-wide figure" rather than "no limit", which is the one place
     * this column departs from the rule above it. Almost every cargo says nothing here and
     * takes {@code match.maxBallastDays}; the override exists because a full cargo worth
     * crossing an ocean for and a part cargo nobody would cross the Med for are both an
     * ordinary week, and no single number is right for both. A cargo that really would take
     * any ballast says so with a large number.
     */
    @Column(name = "max_ballast_days")
    private Short maxBallastDays;

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
     * Typed, mailed, or read off a board. Stays right after the source is gone:
     * {@code mail_messages} mirrors a server whose folders get cleaned out and a board rolls
     * its entries off the bottom, while how this cargo reached the desk is a fact about the
     * cargo. Was a {@code fromMail} boolean until there were three answers to it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 10)
    private SourceKind sourceKind = SourceKind.MANUAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_mail_message_id")
    private MailMessage sourceMailMessage;

    /** The board post it was read off, while the fetcher still holds a copy of it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_feed_item_id")
    private FeedItem sourceFeedItem;

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

    // ---- read, never written: what the Cargoes tab sorts and filters on ----
    // Formulas rather than columns. Each is a fact already held elsewhere in the row or in
    // cargo_sources, and a stored copy would be a second version of it to keep in step - on an
    // audited entity, where every rewrite is also a change-log row saying nothing happened.
    // None is null-safe to write, so none has a setter.

    /** The order the status column sorts in. Kept in step with the enum by CargoStatusRankTest. */
    public static final String STATUS_RANK_SQL = "case status"
            + " when 'OPEN' then 0 when 'QUOTED' then 1 when 'FIRM' then 2"
            + " when 'FIXED' then 3 when 'FAILED' then 4 when 'EXPIRED' then 5"
            + " when 'WITHDRAWN' then 6 when 'NOT_WORKABLE' then 7 else 99 end";

    /**
     * When this cargo last reached the desk: the newest arrival in {@code cargo_sources}, else
     * the received date on the row itself, else when it was entered.
     *
     * <p>Not a column, because a re-send would then rewrite the cargo and every rewrite would
     * log a broker repeating himself, while the fact is one indexed max away
     * ({@code ix_cargo_sources_cargo}). The two fallbacks keep it from ever being null:
     * cargoes predating that table carry only {@code received_at}, a typed one has neither, and
     * a newest-first sort that put every typed cargo above this morning's mail would read as
     * broken.
     *
     * <p>Arrivals from the desk's own addresses ({@link AppSetting#OWN_ADDRESSES}) do not
     * count: the sweep reads our replies too, and a reply quoting a cargo is not the cargo
     * being sent to us. Read from the setting in the query, so a change to the list applies to
     * rows already stored. The setting is the cargo's own desk's: settings are per desk, and
     * this SQL is one of the few places Hibernate's tenant filter does not reach, so it names
     * the desk itself.
     */
    @Formula("coalesce((select max(s.reported_at) from cargo_sources s where s.cargo_id = id"
            + " and not exists (select 1 from app_settings o where o.key = '"
            + AppSetting.OWN_ADDRESSES + "' and o.tenant_id = tenant_id"
            + " and lower(s.from_address) = any(string_to_array(o.value, ',')))),"
            + " received_at, created_at)")
    @Setter(AccessLevel.NONE)
    private OffsetDateTime lastSentAt;

    /**
     * Lifecycle order rather than alphabetical. Sorted by name the column would read EXPIRED,
     * FAILED, FIRM, FIXED - which puts the live ones nowhere in particular.
     */
    @Formula(STATUS_RANK_SQL)
    @Setter(AccessLevel.NONE)
    private Integer statusRank;

    /**
     * The load place as the list prints it - the port on file, else what was written, else the
     * area - lower-cased so a port typed in capitals does not sort ahead of everything else.
     * A sort key only: sorting on {@code loadPort.name} would lump every cargo that names an
     * area or a port not on file into one block of nulls.
     */
    @Formula("lower(coalesce((select p.name from ports p where p.id = load_port_id),"
            + " load_port_text, (select a.name from trade_areas a where a.id = load_area_id)))")
    @Setter(AccessLevel.NONE)
    private String loadPlace;

    @Formula("lower(coalesce((select p.name from ports p where p.id = discharge_port_id),"
            + " discharge_port_text, (select a.name from trade_areas a where a.id = discharge_area_id)))")
    @Setter(AccessLevel.NONE)
    private String dischargePlace;

    /**
     * The total commission a broker is taking, as a percentage, read out of the free-text
     * {@link #commission}. Null where the text states no figure ("as usual", blank).
     *
     * <p>Read in this order, first non-null wins: a figure marked as the total ("3.75 PCT TTL",
     * "5% ttl incl 1.25% adcom" - the largest such figure); else the sum of every figure written
     * as a percentage ("2.5% + 1.25% adcom" is 3.75, brokerage and address commission together); else the
     * whole text being a bare number ("1.25"). A figure of 20 or more is ignored in the first
     * two, because "2.5% on 100% of freight" is a rate and a basis, not a commission of 102.5.
     *
     * <p>A formula rather than a column for the same reason as the rest of this group: it is a
     * reading of the text, and a stored copy would be a second answer to keep in step with the
     * first on an audited row. Kept as a sort and filter key only; the broker's words are still
     * what the form shows and what the mail said.
     *
     * <p>{@code COMMISSION_PCT_SQL} is written with no {@code ?}, no {@code ::} and no
     * {@code substring(... from ...)}, all three of which Hibernate's formula templating
     * mishandles. A mistake there does not fail; it reads null through the entity, so
     * {@code CargoCommissionIntegrationTest} reads every case back through a load.
     */
    @Formula(COMMISSION_PCT_SQL)
    @Setter(AccessLevel.NONE)
    private BigDecimal commissionPct;

    /**
     * Sort key for an ascending sort of {@link #commissionPct}: a cargo with no figure reads as
     * 1,000,000,000, above any real commission, so it lands last.
     *
     * <p>Exists because Spring Data's {@code Sort.Order.nullsLast()} is refused on criteria
     * queries by Hibernate 6 ("Applying Null Precedence using Criteria Queries is not yet
     * supported"), and Postgres puts NULLs first on a descending sort. A sentinel chosen per
     * direction is the way round it: one key cannot serve both, since a value that sorts a
     * missing figure last ascending sorts it first descending. The service maps a sort on
     * {@code commissionPct} onto the key for its direction ({@code CargoService}).
     */
    @Formula("coalesce(" + COMMISSION_PCT_SQL + ", 1000000000)")
    @Setter(AccessLevel.NONE)
    private BigDecimal commissionSortAsc;

    /** Sort key for a descending sort of {@link #commissionPct}: no figure reads as -1, below any real one. */
    @Formula("coalesce(" + COMMISSION_PCT_SQL + ", -1)")
    @Setter(AccessLevel.NONE)
    private BigDecimal commissionSortDesc;

    /** A decimal comma between digits becomes a point, so "3,75%" reads as "3.75%". */
    private static final String COMMISSION_NORMALISED =
            "regexp_replace(lower(commission), '(\\d),(\\d)', '\\1.\\2', 'g')";

    /** A number, optionally with a decimal part. Group 1 is the whole figure. */
    private static final String COMMISSION_NUMBER = "(\\d+(\\.\\d+){0,1})";

    /**
     * A figure marked as the total. The percent sign, "pct", "per cent" or "percent" and the dot
     * after "pct" are each optional, so "3.75 PCT TTL" and "5% total" both match.
     */
    private static final String COMMISSION_TOTAL_RE =
            COMMISSION_NUMBER + "\\s*(%|pct|per cent|percent){0,1}\\.{0,1}\\s*(ttl|total)";

    /** A figure written as a percentage, by itself or added to another. */
    private static final String COMMISSION_PERCENT_RE =
            COMMISSION_NUMBER + "\\s*(%|pct|per cent|percent)";

    /** The whole text is one bare number and nothing else. */
    private static final String COMMISSION_BARE_RE = "^\\s*" + COMMISSION_NUMBER + "\\s*$";

    /*
     * Hibernate's formula templating qualifies a bare identifier with this entity's table alias
     * wherever it is not a function name or follows a dot, so an alias anywhere in the FROM
     * clause (t(m), as t, t) came out as a reference to cargoes - a syntax error or a wrong
     * column. With no alias, the set-returning function is named regexp_matches in both places,
     * and that name is only ever a function call or the left of a dot.
     */
    private static final String COMMISSION_PCT_SQL = "coalesce("
            + "(select max(cast(regexp_matches.regexp_matches[1] as numeric)) from regexp_matches("
            + COMMISSION_NORMALISED + ", '" + COMMISSION_TOTAL_RE + "', 'g')"
            + " where cast(regexp_matches.regexp_matches[1] as numeric) < 20),"
            + " (select sum(cast(regexp_matches.regexp_matches[1] as numeric)) from regexp_matches("
            + COMMISSION_NORMALISED + ", '" + COMMISSION_PERCENT_RE + "', 'g')"
            + " where cast(regexp_matches.regexp_matches[1] as numeric) < 20),"
            + " cast((regexp_match(" + COMMISSION_NORMALISED + ", '" + COMMISSION_BARE_RE
            + "'))[1] as numeric))";
}
