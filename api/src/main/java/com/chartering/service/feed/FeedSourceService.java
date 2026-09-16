package com.chartering.service.feed;

import com.chartering.dto.FeedSourceRequest;
import com.chartering.dto.FeedSourceResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Adding and changing sources. Plain rows, so this works on every deployment — a source added
 * from a phone against the hosted instance is fetched by the office one on its next tick.
 */
@Service
@RequiredArgsConstructor
public class FeedSourceService {

    private final FeedSourceRepository sources;
    private final FeedItemRepository items;
    private final WebsiteReader websites;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public List<FeedSourceResponse> list() {
        Map<Long, Long> counts = items.countBySource().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        return sources.findAllByOrderByNameAsc().stream()
                .map(s -> mapper.toFeedSourceResponse(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    @Transactional
    public FeedSourceResponse create(FeedSourceRequest req) {
        FeedSource s = new FeedSource();
        apply(s, req);
        return mapper.toFeedSourceResponse(sources.save(s), 0);
    }

    @Transactional
    public FeedSourceResponse update(Long id, FeedSourceRequest req) {
        FeedSource s = sources.findById(id).orElseThrow(() -> new ResourceNotFoundException("Feed source", id));
        String previousUrl = s.getUrl();
        apply(s, req);
        if (!s.getUrl().equals(previousUrl)) {
            // Validators belong to the old address; sent to the new one they could answer 304 for a page never read.
            s.setEtag(null);
            s.setLastModified(null);
        }
        long count = items.countBySource().stream().filter(r -> id.equals(r[0])).mapToLong(r -> (Long) r[1]).sum();
        return mapper.toFeedSourceResponse(s, count);
    }

    @Transactional
    public void delete(Long id) {
        if (!sources.existsById(id)) throw new ResourceNotFoundException("Feed source", id);
        sources.deleteById(id);
    }

    private void apply(FeedSource s, FeedSourceRequest req) {
        String url;
        String parserKey = null;
        switch (req.getKind()) {
            case TELEGRAM -> url = TelegramReader.normaliseUrl(req.getUrl());
            case RSS -> url = httpUrl(req.getUrl());
            case WEBSITE -> {
                url = httpUrl(req.getUrl());
                parserKey = websites.find(req.getParserKey()).map(WebsiteParser::key).orElseThrow(() ->
                        new IllegalArgumentException("Choose which site parser reads this website."));
            }
            default -> throw new IllegalArgumentException("Unknown source kind " + req.getKind());
        }
        sources.findByUrl(url).filter(other -> !other.getId().equals(s.getId())).ifPresent(other -> {
            throw new IllegalArgumentException("That address is already a source: " + other.getName() + ".");
        });
        s.setKind(req.getKind());
        s.setUrl(url);
        s.setParserKey(parserKey);
        s.setName(req.getName() == null || req.getName().isBlank() ? defaultName(req.getKind(), url) : req.getName().strip());
        if (req.getEnabled() != null) s.setEnabled(req.getEnabled());
    }

    private static String httpUrl(String raw) {
        String s = raw.strip();
        if (!s.matches("(?i)^https?://.+")) s = "https://" + s;
        try {
            URI uri = URI.create(s);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            return s;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a web address: " + raw);
        }
    }

    private static String defaultName(FeedSourceKind kind, String url) {
        if (kind == FeedSourceKind.TELEGRAM) return "@" + url.substring(url.lastIndexOf('/') + 1);
        return FeedHttp.host(url);
    }
}
