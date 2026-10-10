package com.chartering.service.ask;

/**
 * The question could not be answered because a server it needs did not do its part: the embedding
 * server is down, the model is unreachable, or the model server has no adapter to serve the answer
 * with. The message says which, and what to do about it, because the person asking is at a desk and
 * cannot read a stack trace.
 *
 * <p>Mapped to 503 by {@code GlobalExceptionHandler}. That is the honest answer: the feature is
 * configured and the server is not answering, which is the same status the embedding server gets
 * when it is not configured.
 */
public class AskUnavailableException extends RuntimeException {

    public AskUnavailableException(String message) {
        super(message);
    }

    public AskUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
