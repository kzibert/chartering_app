package com.chartering.service.feed;

import com.chartering.dto.FeedTopicRequest;
import com.chartering.dto.FeedTopicResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.FeedTopic;
import com.chartering.repository.FeedTopicRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The topics the desk wants summaries for, and which of them the next Summarise runs. */
@Service
@RequiredArgsConstructor
public class FeedTopicService {

    private final FeedTopicRepository topics;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public List<FeedTopicResponse> list() {
        return topics.findAllByOrderBySortOrderAscIdAsc().stream().map(mapper::toFeedTopicResponse).toList();
    }

    @Transactional
    public FeedTopicResponse create(FeedTopicRequest req) {
        FeedTopic t = new FeedTopic();
        t.setSortOrder(topics.findAllByOrderBySortOrderAscIdAsc().stream()
                .mapToInt(FeedTopic::getSortOrder).max().orElse(-1) + 1);
        apply(t, req);
        return mapper.toFeedTopicResponse(topics.save(t));
    }

    @Transactional
    public FeedTopicResponse update(Long id, FeedTopicRequest req) {
        FeedTopic t = topics.findById(id).orElseThrow(() -> new ResourceNotFoundException("Feed topic", id));
        apply(t, req);
        return mapper.toFeedTopicResponse(t);
    }

    @Transactional
    public void delete(Long id) {
        if (!topics.existsById(id)) throw new ResourceNotFoundException("Feed topic", id);
        topics.deleteById(id);
    }

    @Transactional
    public List<FeedTopicResponse> select(List<Long> selectedIds) {
        Set<Long> wanted = new HashSet<>(selectedIds);
        List<FeedTopic> all = topics.findAllByOrderBySortOrderAscIdAsc();
        all.forEach(t -> t.setSelected(wanted.contains(t.getId())));
        return all.stream().map(mapper::toFeedTopicResponse).toList();
    }

    private void apply(FeedTopic t, FeedTopicRequest req) {
        String name = req.getName().strip();
        topics.findByNameIgnoreCase(name).filter(other -> !other.getId().equals(t.getId())).ifPresent(other -> {
            throw new IllegalArgumentException("There is already a topic called " + other.getName() + ".");
        });
        t.setName(name);
        if (req.getKeywords() != null) {
            t.setKeywords(String.join(", ", req.getKeywords().stream()
                    .map(String::strip).filter(k -> !k.isEmpty()).distinct().toList()));
        }
        if (req.getSelected() != null) t.setSelected(req.getSelected());
        if (req.getSortOrder() != null) t.setSortOrder(req.getSortOrder());
    }
}
