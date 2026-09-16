package com.chartering.service.feed;

import com.chartering.model.FeedSource;
import com.chartering.model.FeedSourceKind;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Hands a {@link FeedSourceKind#WEBSITE} source to the parser its {@code parser_key} names. */
@Component
public class WebsiteReader implements FeedReader {

    private final Map<String, WebsiteParser> parsers;

    public WebsiteReader(List<WebsiteParser> parsers) {
        this.parsers = parsers.stream()
                .collect(Collectors.toMap(WebsiteParser::key, Function.identity()));
    }

    @Override
    public FeedSourceKind kind() {
        return FeedSourceKind.WEBSITE;
    }

    @Override
    public ReadResult read(FeedSource source, Predicate<String> alreadySeen) {
        WebsiteParser parser = find(source.getParserKey()).orElseThrow(() ->
                new FeedFetchException("No site parser called '" + source.getParserKey()
                        + "' exists in this version of the app."));
        return parser.read(source, alreadySeen);
    }

    public Optional<WebsiteParser> find(String key) {
        return Optional.ofNullable(key == null ? null : parsers.get(key));
    }

    public List<WebsiteParser> all() {
        return parsers.values().stream().sorted(Comparator.comparing(WebsiteParser::label)).toList();
    }
}
