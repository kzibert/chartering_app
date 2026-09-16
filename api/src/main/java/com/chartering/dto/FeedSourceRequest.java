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
}
