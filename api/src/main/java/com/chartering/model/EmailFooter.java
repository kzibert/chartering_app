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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
