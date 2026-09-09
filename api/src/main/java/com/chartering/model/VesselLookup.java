package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One attempt to find a hull on an outside source.
 *
 * <p><b>Provenance is the point of this table, not a by-product.</b> Once a deadweight has
 * been accepted onto a vessel it is indistinguishable from one a broker typed — unless
 * something remembers where it came from. This row is that memory: the query, every
 * candidate as it was read, the one the matcher preferred, and the page a person can open to
 * check. A number with no source is a rumour.
 *
 * <p>Kept whether the search found anything or not. {@code NO_MATCH} is an answer — it stops
 * the same hull being searched for again every hour — and a failure is a fact about the
 * source rather than about the ship.
 */
@Getter
@Setter
@Entity
@Table(name = "vessel_lookups")
public class VesselLookup {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NO_MATCH = "NO_MATCH";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * Nothing was asked, because a search could not have helped.
     *
     * <p>A hull both the email and the record name by IMO is already identified; searching
     * for her would spend somebody else's bandwidth to be told what is on the screen. The
     * row exists anyway because the enrichment pass asks "which pending items have no lookup
     * row", and an item that produced none was answered again by every pass for ever — while
     * the hulls with no IMO queued behind it, which are the only ones this feature is for,
     * were never reached. Same reasoning as {@code NO_MATCH}: an answer recorded is what
     * stops the question being asked again.
     */
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The review item that prompted it. Answer the item and the lookup has done its job. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intake_item_id")
    private IntakeItem intakeItem;

    /** Set once applied, so a hull's particulars can be traced back to the search. */
    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(nullable = false, length = 60)
    private String provider;

    @Column(nullable = false, length = 255)
    private String query;

    @Column(nullable = false, length = 20)
    private String status = STATUS_OK;

    /**
     * Every candidate the search returned, before matching. Kept whole rather than just the
     * winner: when the wrong hull is proposed the question is always what else was on offer.
     */
    @Column(name = "candidates_json", columnDefinition = "text")
    private String candidatesJson;

    @Column(name = "matched_imo", length = 20)
    private String matchedImo;

    /** 0-100. How well the preferred candidate agreed with what was known. */
    private Integer confidence;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt = OffsetDateTime.now();

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
