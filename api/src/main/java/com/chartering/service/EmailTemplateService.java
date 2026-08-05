package com.chartering.service;

import com.chartering.dto.EmailTemplateRequest;
import com.chartering.dto.EmailTemplateResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.EmailTemplate;
import com.chartering.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final EmailTemplateRepository repository;
    private final HtmlSanitizer sanitizer;

    @Transactional(readOnly = true)
    public List<EmailTemplateResponse> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public EmailTemplateResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public EmailTemplateResponse create(EmailTemplateRequest req) {
        requireNameAvailable(req.getName(), null);
        EmailTemplate t = new EmailTemplate();
        apply(t, req);
        return toResponse(repository.save(t));
    }

    @Transactional
    public EmailTemplateResponse update(Long id, EmailTemplateRequest req) {
        EmailTemplate t = find(id);
        requireNameAvailable(req.getName(), id);
        apply(t, req);
        return toResponse(repository.save(t));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    private void apply(EmailTemplate t, EmailTemplateRequest req) {
        t.setName(req.getName().trim());
        t.setSubject(req.getSubject() == null ? null : req.getSubject().trim());
        t.setBodyHtml(sanitizer.clean(req.getBodyHtml()));
    }

    /**
     * Checked in the service as well as by the unique index so the user gets a readable
     * 400 instead of a constraint-violation stack trace.
     */
    private void requireNameAvailable(String name, Long selfId) {
        repository.findByNameIgnoreCase(name.trim()).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new IllegalArgumentException("A template named \"" + name.trim() + "\" already exists.");
            }
        });
    }

    private EmailTemplate find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email template", id));
    }

    private EmailTemplateResponse toResponse(EmailTemplate t) {
        return new EmailTemplateResponse(t.getId(), t.getName(), t.getSubject(),
                t.getBodyHtml(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
