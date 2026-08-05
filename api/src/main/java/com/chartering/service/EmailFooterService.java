package com.chartering.service;

import com.chartering.dto.EmailFooterRequest;
import com.chartering.dto.EmailFooterResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.model.EmailFooter;
import com.chartering.repository.EmailFooterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EmailFooterService {

    private final EmailFooterRepository repository;
    private final HtmlSanitizer sanitizer;

    @Transactional(readOnly = true)
    public List<EmailFooterResponse> list() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public EmailFooterResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public Optional<EmailFooter> findDefault() {
        return repository.findByDefaultFooterIsTrue();
    }

    @Transactional
    public EmailFooterResponse create(EmailFooterRequest req) {
        requireNameAvailable(req.getName(), null);
        // Demote first — see clearAllDefaults: the index won't tolerate two defaults, even
        // transiently, so the new row must not be flushed while an old default still stands.
        if (req.isDefaultFooter()) {
            repository.clearAllDefaults();
        }
        EmailFooter f = new EmailFooter();
        apply(f, req);
        return toResponse(repository.save(f));
    }

    @Transactional
    public EmailFooterResponse update(Long id, EmailFooterRequest req) {
        EmailFooter f = find(id);
        requireNameAvailable(req.getName(), id);
        if (req.isDefaultFooter()) {
            repository.clearDefaultExcept(id);
        }
        apply(f, req);
        return toResponse(repository.save(f));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    private void apply(EmailFooter f, EmailFooterRequest req) {
        f.setName(req.getName().trim());
        f.setHtml(sanitizer.clean(req.getHtml()));
        f.setDefaultFooter(req.isDefaultFooter());
    }

    private void requireNameAvailable(String name, Long selfId) {
        repository.findByNameIgnoreCase(name.trim()).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new IllegalArgumentException("A footer named \"" + name.trim() + "\" already exists.");
            }
        });
    }

    private EmailFooter find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email footer", id));
    }

    private EmailFooterResponse toResponse(EmailFooter f) {
        return new EmailFooterResponse(f.getId(), f.getName(), f.getHtml(),
                f.isDefaultFooter(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
