package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The permanent record of one circulation: what was sent, when, from where, and how it ended.
 *
 * <p>{@code composedHtml} is the body-plus-footer exactly as it entered the mail merge,
 * stored once for the whole run rather than once per recipient. The merge is deterministic,
 * so replaying it against a recipient's stored fields reproduces the message that person
 * actually received — see {@code CirculationHistoryService#renderMessage}.
 *
 * <p>Template and footer are recorded by <em>name</em>, not by foreign key. History has to
 * stay truthful after someone deletes the footer it used.
 */
@Getter
@Setter
@Entity
@Table(name = "circulation_runs")
public class CirculationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The subject before the merge — still carrying its {{placeholders}}. */
    @Column(name = "subject_template", nullable = false)
    private String subjectTemplate;

    @Column(name = "composed_html", nullable = false, columnDefinition = "text")
    private String composedHtml;

    @Column(name = "footer_id")
    private Long footerId;

    @Column(name = "footer_name")
    private String footerName;

    /** Which list the recipients came from, when they came from a saved one. */
    @Column(name = "list_id")
    private Long listId;

    @Column(name = "list_name")
    private String listName;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "reply_to")
    private String replyTo;

    /** RUNNING while in flight, then the same terminal states the campaign status reports. */
    @Column(nullable = false)
    private String state = "RUNNING";

    @Column(nullable = false)
    private int total = 0;

    @Column(nullable = false)
    private int sent = 0;

    @Column(nullable = false)
    private int failed = 0;

    @Column(nullable = false)
    private int skipped = 0;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(columnDefinition = "text")
    private String message;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<CirculationRunRecipient> recipients = new ArrayList<>();

    public void addRecipient(CirculationRunRecipient r) {
        r.setRun(this);
        r.setSortOrder(recipients.size());
        recipients.add(r);
    }
}
