package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Reusable signature block appended to a circular at send time. Mail-merge placeholders
 * work here too, so a footer can address the recipient by name or echo their address.
 */
@Getter
@Setter
@Entity
@Table(name = "email_footers")
public class EmailFooter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String html;

    /**
     * Pre-selected in the compose tab. At most one row may have this set — enforced by a
     * partial unique index in the database, not just by application code.
     */
    @Column(name = "is_default", nullable = false)
    private boolean defaultFooter = false;

    /**
     * Pre-selected when replying to a message in the Mailbox tab, and a separate flag on
     * purpose: a circular closes with the desk's full block, while a reply to one broker in
     * a thread they started usually wants something shorter. One footer may hold both flags,
     * either, or neither. Its own partial unique index, for the same reason.
     */
    @Column(name = "is_reply_default", nullable = false)
    private boolean replyDefault = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
