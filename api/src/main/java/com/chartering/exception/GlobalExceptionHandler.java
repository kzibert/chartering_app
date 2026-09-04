package com.chartering.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    /** Violations on @RequestParam/@PathVariable in @Validated controllers. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * A feature this deployment does not carry. 404, not 403: nothing here is being withheld
     * from this caller, the endpoint is simply not part of this environment.
     */
    @ExceptionHandler(FeatureDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureDisabled(FeatureDisabledException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** Mail isn't set up (or the SMTP host is unreachable) — the server can't serve the request yet. */
    @ExceptionHandler(MailNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleMailNotConfigured(MailNotConfiguredException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * The provider refused a message we did try to send. Its own words, verbatim.
     *
     * <p><b>503, and deliberately not 502</b> — which is what this returned until a reply
     * that would not go out was reported as "a 502 from the Mailbox tab" and nobody could
     * tell what had happened. On paper 502 is the better fit: this application really is
     * acting as a gateway to a mail server that gave it an answer it could not use. In a
     * browser it is unreadable. Every hop in front of this app emits 502 of its own —
     * Cloudflare, Render's edge, Render's router, the nginx in front of the api under
     * compose — and all of them mean "the instance is asleep, crashed, or unreachable".
     * A status that says something true about the mail and a status that says the
     * deployment is broken must not be the same number, because the person reading it is
     * being asked to tell those two apart.
     *
     * <p>So it is reported as 503, alongside {@link MailNotConfiguredException}: the mail
     * service this app depends on would not take the message. Which of the two it was is in
     * the body — the provider's own refusal, verbatim — and that is the part worth reading
     * either way.
     *
     * <p>Logged as well as returned, which it was not before: an SMTP refusal produced a
     * status code in the browser and <em>nothing at all</em> in the server log, so the one
     * place holding the provider's reply code never mentioned it. On a hosted instance that
     * log is the only forensics there is.
     */
    @ExceptionHandler(MailSendFailedException.class)
    public ResponseEntity<Map<String, Object>> handleMailSendFailed(MailSendFailedException ex) {
        log.warn("Outgoing mail was refused: {}", ex.getMessage(), ex);
        return body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /**
     * Wrong credentials, or the login endpoint temporarily refusing to answer after too many
     * attempts. One status and one message for all of those — see AuthService.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthFailed(AuthenticationFailedException ex) {
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** No password configured server-side: nobody can log in until one is. */
    @ExceptionHandler(AuthNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleAuthNotConfigured(AuthNotConfiguredException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /** Conflicting state, e.g. starting a campaign while one is already running. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> b = new HashMap<>();
        b.put("timestamp", OffsetDateTime.now());
        b.put("status", status.value());
        b.put("error", status.getReasonPhrase());
        b.put("message", message);
        return ResponseEntity.status(status).body(b);
    }
}
