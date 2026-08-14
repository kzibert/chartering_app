package com.chartering.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One user-changed setting. The key is the identity — there is no surrogate id, because a
 * setting is its name.
 *
 * <p>Only overridden values are stored: a missing row means the configured default from
 * application.yml is in force. That keeps the environment variables meaningful as the
 * baseline and makes "reset to defaults" a delete rather than a write.
 */
@Getter
@Setter
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(name = "key", nullable = false, length = 100)
    private String key;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    @PrePersist
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
