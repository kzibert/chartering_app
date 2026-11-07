package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * An answer to a question on the desk, with the passages it cites.
 *
 * <p>{@code n} on each source is the number the answer writes in brackets, so a reader can follow
 * {@code [2]} to the second source. {@code model} is absent when nothing was found and the model was
 * not asked, and is dropped from the JSON rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskResponse(
        /** The answer, with citations as [n]. */
        String answer,
        /** The passages the answer was given from, in citation order. */
        List<Source> sources,
        /** The model that wrote the answer, where one was asked. */
        String model) {

    /**
     * One passage the answer may cite.
     *
     * @param kind    MAIL, POST, CARGO or POSITION
     * @param id      the record's id on the desk, for a link to it
     * @param date    the date shown beside the passage (ISO), where the record has one
     * @param snippet the start of the passage, on one line
     */
    public record Source(int n, String kind, long id, String title, String date, String snippet) {
    }
}
