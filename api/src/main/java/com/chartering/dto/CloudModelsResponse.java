package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The chat models the cloud provider serves to the caller's key, for the Settings card's model
 * picker.
 *
 * <p>A failure is an {@code error} beside an empty list rather than an error status, because the
 * picker is a convenience over a field that still takes a typed name: no key saved yet, a key the
 * provider refuses or a provider that is down all leave the field working, and the card says why
 * the list is empty instead of the notification tray raising an alarm on every visit to Settings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloudModelsResponse(List<Model> models, String error) {

    /** {@code ownedBy} and {@code contextWindow} are absent where the provider does not say. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Model(String id, String ownedBy, Integer contextWindow) {}
}
