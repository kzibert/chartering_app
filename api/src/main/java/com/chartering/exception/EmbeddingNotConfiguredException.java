package com.chartering.exception;

/**
 * The embedding index was asked for, but this deployment has no embedding server address.
 *
 * <p>Answered with 503 rather than 404: the feature is present and its switch is on, but the
 * server it needs is not named, which is a fact the person can fix by setting EMBEDDING_URL.
 * That is the difference from {@link FeatureDisabledException}, which says the feature is not
 * part of this deployment at all.
 */
public class EmbeddingNotConfiguredException extends RuntimeException {

    public EmbeddingNotConfiguredException(String message) {
        super(message);
    }
}
