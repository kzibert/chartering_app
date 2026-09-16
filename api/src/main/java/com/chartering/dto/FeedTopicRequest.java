package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class FeedTopicRequest {

    @NotBlank(message = "A topic needs a name")
    @Size(max = 200, message = "A topic name may be at most 200 characters")
    private String name;

    /** What an item must mention to be read for this topic. Empty means the words of the name. */
    private List<String> keywords;

    private Boolean selected;

    private Integer sortOrder;
}
