package com.chartering.service.feed;

import com.chartering.dto.FeedSourceRequest;
import com.chartering.dto.FeedSourceResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.FeedIntakeSubscription;
import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import com.chartering.repository.FeedIntakeSubscriptionRepository;
import com.chartering.repository.FeedItemRepository;
import com.chartering.repository.FeedSourceRepository;
import com.chartering.tenancy.TenantContext;
import com.chartering.tenancy.TenantDirectory;
import java.util.HashSet;
import java.util.Set;
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
    private final com.chartering.repository.IntakeItemRepository intakeItems;
    private final WebsiteReader websites;
    private final DtoMapper mapper;
    private final FeedIntakeSubscriptionRepository subscriptions;
    private final TenantDirectory tenants;

    @Transactional(readOnly = true)
    public List<FeedSourceResponse> list() {
        Map<Long, Long> counts = items.countBySource().stream()
                .collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
        Set<Long> readIn = new HashSet<>(subscriptions.findSourceIds());
        return sources.findAllByOrderByNameAsc().stream()
                .map(s -> mapper.toFeedSourceResponse(s, counts.getOrDefault(s.getId(), 0L), readIn.contains(s.getId())))
                .toList();
    }

    @Transactional
    public FeedSourceResponse create(FeedSourceRequest req) {
        FeedSource s = new FeedSource();
        apply(s, req);
        sources.save(s);
        if (req.getIntoIntake() != null) subscribe(s.getId(), req.getIntoIntake());
        return mapper.toFeedSourceResponse(s, 0, isReadIn(s.getId()));
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
        if (req.getIntoIntake() != null) subscribe(id, req.getIntoIntake());
        long count = items.countBySource().stream().filter(r -> id.equals(r[0])).mapToLong(r -> (Long) r[1]).sum();
        return mapper.toFeedSourceResponse(s, count, isReadIn(id));
    }

    /**
     * Whether the caller's desk reads this board into Intake. A desk administrator's switch:
     * the board is the installation's, what the desk does with its posts is the desk's.
     */
    @Transactional
    public FeedSourceResponse setReadIntoIntake(Long id, boolean on) {
        FeedSource s = sources.findById(id).orElseThrow(() -> new ResourceNotFoundException("Feed source", id));
        subscribe(id, on);
        long count = items.countBySource().stream().filter(r -> id.equals(r[0])).mapToLong(r -> (Long) r[1]).sum();
        return mapper.toFeedSourceResponse(s, count, on);
    }

    private void subscribe(Long sourceId, boolean on) {
        var existing = subscriptions.findByFeedSourceId(sourceId);
        if (on && existing.isEmpty()) {
            FeedIntakeSubscription sub = new FeedIntakeSubscription();
            sub.setFeedSourceId(sourceId);
            subscriptions.save(sub);
        } else if (!on) {
            existing.ifPresent(subscriptions::delete);
        }
    }

    private boolean isReadIn(Long sourceId) {
        return subscriptions.findByFeedSourceId(sourceId).isPresent();
    }

    /**
     * Remove a source, and everything it collected with it.
     *
     * <p><b>Refused while it still has questions waiting.</b> Deleting a source cascades to its
     * posts, and from there to the parse records and the review items raised off them — so a
     * board removed on a whim would take a queue of unanswered questions about ships and firms
     * with it, silently. That is a far worse loss than the posts themselves, which are copies
     * of a page anyone can reload. Disabling is the reversible way to stop a source, and it is
     * what the message points at.
     */
    /**
     * Not one transaction: the unanswered questions are counted on every desk, each in its own
     * session, because a source is global and the delete cascades through every desk's posts,
     * parse records and items. A transaction around it would pin every count to the caller's desk.
     */
    public void delete(Long id) {
        if (!sources.existsById(id)) throw new ResourceNotFoundException("Feed source", id);
        long pending = tenants.activeIds().stream()
                .mapToLong(desk -> TenantContext.callAs(desk, () -> intakeItems.countPendingFromSource(id)))
                .sum();
        if (pending > 0) {
            throw new IllegalArgumentException(
                    "This source still has " + pending + " unanswered question(s) in the Intake "
                            + "queue, and deleting it would take them with it. Answer them, or "
                            + "switch the source off instead — that keeps everything it has "
                            + "collected.");
        }
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
