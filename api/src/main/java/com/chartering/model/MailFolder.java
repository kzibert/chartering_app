package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One of the app's own mail folders.
 *
 * <p>Nothing here corresponds to a folder on the mail server. Filing a message moves a row
 * in {@code mail_messages}; the mailbox is opened read-only and never touched. That is the
 * point of the design rather than a limitation of it — a mis-written rule can rearrange
 * this table and nothing else, and the mailbox stays exactly as its owner left it.
 *
 * <p>There is no row for the Inbox. A message with no folder <em>is</em> in the Inbox, which
 * is the same statement as "nothing has filed it yet".
 */
@Getter
@Setter
@Entity
@Table(name = "mail_folders")
public class MailFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String notes;

    /** Display order in the folder rail; ties break by name. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
