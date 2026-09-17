package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * Which hull one firm means by one name.
 *
 * <p><b>The case it exists for is a name two ships share.</b> Matching is IMO, then name
 * (current or former), each exact, and two rows answering to one name is treated as no match
 * at all — deliberately, because picking whichever came first would file one owner's position
 * on another owner's ship and nothing downstream would ever question it. Two hulls on this
 * desk are called PHANTOM and neither carries an IMO, so every list naming her raised a
 * {@code NEW_VESSEL} item; the reviewer pointed it at the right hull on three separate
 * mornings and the fourth asked again.
 *
 * <p><b>Linking could record nothing, and that is not an oversight in {@code vessel_ex_names}
 * — it is that table being asked the wrong question.</b> A former name is a fact about the
 * <em>ship</em>: she used to be called that, it is true for everyone, and it is what lets any
 * broker's list find her. Here the name the email used is the name she already carries, so
 * there was nothing to file, and filing it anyway would have said something false about the
 * other PHANTOM as well.
 *
 * <p>This is a fact about a <em>correspondent's vocabulary</em>, and that is exactly what can
 * settle an ambiguous name: PHANTOM is ambiguous in the fleet and unambiguous in one broker's
 * list. It follows that it must never widen the search — an alias can only answer a question
 * the two exact tiers have already failed, or it would start overruling a hull's own name.
 *
 * <p>One hull per firm per name, replaced rather than added to. An owner sells a ship and
 * takes the name to the next one; the firm's later statement is the one to act on, and a
 * second row would put the resolver back where it started.
 */
@Getter
@Setter
@Entity
@Table(name = "intake_vessel_aliases")
public class IntakeVesselAlias {

    /** The reviewer pointed the reading at a hull already on file. */
    public static final String LINKED = "LINKED";

    /** The reviewer made her from this email, so this firm's name for her is hers. */
    public static final String CREATED = "CREATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    /**
     * Required, unlike the sender on {@link IntakeFieldDecision}. An alias with nobody behind
     * it is a claim about the name itself, which belongs in {@code vessel_ex_names}; without a
     * firm to scope it, resolving PHANTOM this way would be the arbitrary pick the resolver
     * refuses to make.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by_company_id", nullable = false)
    private Company reportedByCompany;

    /** As the email spelled it, for a screen to print. */
    @Column(nullable = false, length = 255)
    private String name;

    /** Folded for lookup, the way {@code port_aliases.alias_key} is: the fold lives in one place. */
    @Column(name = "name_key", nullable = false, length = 255)
    private String nameKey;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 255)
    private String createdBy;

    /**
     * Case and surrounding space folded, and nothing else.
     *
     * <p>Deliberately not the aggressive fold {@code PortDirectory} uses on its aliases. This
     * decides which hull a position is filed against, so it stays as close to the exact match
     * the tier above it makes as it can: what it forgives is a broker typing her in capitals
     * one week and title case the next, not a different name that reduces to the same letters.
     */
    public static String key(String name) {
        return name == null ? null : name.trim().toLowerCase(Locale.ROOT).replaceAll("\s+", " ");
    }
}
