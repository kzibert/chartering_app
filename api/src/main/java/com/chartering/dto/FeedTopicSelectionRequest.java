package com.chartering.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** Exactly these topics are selected; every other one is not. */
@Data
public class FeedTopicSelectionRequest {

    @NotNull(message = "selectedIds is required (it may be empty)")
    private List<Long> selectedIds;
}
