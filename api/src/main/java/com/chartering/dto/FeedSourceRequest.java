package com.chartering.dto;

import com.chartering.model.FeedSourceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** A source to add or change. A blank name is filled from the handle or the host. */
@Data
public class FeedSourceRequest {

    private String name;

    @NotNull(message = "Choose what kind of source this is")
    private FeedSourceKind kind;

    /** For Telegram, @handle or a t.me link; for the others, the address. */
    @NotBlank(message = "An address is required")
    private String url;

    /** Required for a WEBSITE: which site parser reads it. */
    private String parserKey;

    private Boolean enabled;

    /**
     * Read this source's posts as circulars — cargoes, positions and company details — through
     * the parser the mailbox goes through.
     *
     * <p>Worth it for a board of pasted circulars and not for a trade-press feed: running the
     * extraction model over a news article spends GPU to produce nothing. Null leaves it as it
     * is, so a form that does not offer the option cannot silently clear it.
     */
    private Boolean intoIntake;
}
