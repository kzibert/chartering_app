package com.chartering.exception;

import jakarta.validation.ConstraintViolationException;
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

    /** Mail isn't set up (or the SMTP host is unreachable) — the server can't serve the request yet. */
    @ExceptionHandler(MailNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleMailNotConfigured(MailNotConfiguredException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    /** The provider refused a message we did try to send. Its own words, verbatim. */
    @ExceptionHandler(MailSendFailedException.class)
    public ResponseEntity<Map<String, Object>> handleMailSendFailed(MailSendFailedException ex) {
        return body(HttpStatus.BAD_GATEWAY, ex.getMessage());
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
