package com.chartering.service.ask;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.service.parser.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether "Ask the desk" exists on this deployment: the answering model and the embedding server
 * are both configured.
 *
 * <p><b>Both, or it is off.</b> The answer needs the model to write it and the embedding server to
 * find the passages it is written from. With only one of them there is nothing useful to do, so the
 * feature is absent rather than half-working: every endpoint except the status answers 404, the way
 * Intake and Analysis do when their switch is off.
 *
 * <p>Read on every call rather than cached, so a changed environment is seen at the next request
 * after a restart rather than never.
 */
@Component
@RequiredArgsConstructor
public class AskFeature {

    private final ParserProperties parser;
    private final EmbeddingClient embeddings;

    public boolean isEnabled() {
        return parser.isEnabled() && embeddings.isEnabled();
    }

    /** 404 when the feature is off. The status endpoint does not call this. */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new FeatureDisabledException("Ask the desk needs PARSER_ENABLED=true and EMBEDDING_URL "
                    + "set on this deployment.");
        }
    }
}
