package com.chartering.service.ask;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts one document's text into the passages a question is answered from.
 *
 * <p><b>Split on the document's own structure first.</b> Paragraphs (blank lines) are the unit a
 * person reads, so a chunk boundary falls between them whenever it can. A paragraph too long for
 * one chunk is split on its lines, and only a single line too long for a chunk is cut inside, at
 * a character boundary. A passage cut mid-sentence retrieves worse than one cut at a paragraph,
 * and a figure split across two chunks ("28,000" / "MT") is answered from neither.
 *
 * <p><b>Every chunk carries the header.</b> The subject, sender, date, board name or vessel name
 * is prepended to each chunk, so a passage found on its own still says what it is about. The
 * header is counted against the size limit, so a chunk stays within {@link #MAX_CHARS} whatever
 * the header holds - except that the body always gets at least {@link #MIN_BODY_CHARS}, so a very
 * long header cannot make the body empty.
 *
 * <p><b>Nothing is dropped.</b> Joining the chunks' bodies back together gives the original text
 * with only blank lines and surrounding space removed. A cut that loses text is a figure the desk
 * can no longer find.
 */
public final class TextChunker {

    /** The most characters one chunk may hold, header included. */
    public static final int MAX_CHARS = 1_500;

    /** The least room the body is ever given, whatever the header takes. */
    static final int MIN_BODY_CHARS = 300;

    private TextChunker() {
    }

    /** A piece of body text, and whether it begins a new paragraph (so it is joined with a blank line). */
    private record Unit(String text, boolean newParagraph) {
    }

    /**
     * The document as chunks, each beginning with the header.
     *
     * <p>A body that is empty yields one chunk holding the header alone, so a message with no text
     * is still found by its subject and sender.
     */
    public static List<String> chunks(String header, String body) {
        String head = normalise(header);
        String text = normalise(body);
        if (text.isEmpty()) {
            return List.of(join(head, ""));
        }

        int budget = Math.max(MAX_CHARS - head.length() - 2, MIN_BODY_CHARS);
        List<Unit> units = units(text, budget);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Unit unit : units) {
            if (current.length() > 0) {
                String separator = unit.newParagraph() ? "\n\n" : "\n";
                if (current.length() + separator.length() + unit.text().length() <= budget) {
                    current.append(separator).append(unit.text());
                    continue;
                }
                chunks.add(join(head, current.toString()));
                current.setLength(0);
            }
            current.append(unit.text());
        }
        if (current.length() > 0) {
            chunks.add(join(head, current.toString()));
        }
        return chunks;
    }

    /**
     * The whole document as one chunk, header first.
     *
     * <p>For records whose text is a handful of lines (a cargo, a vessel position). Splitting one
     * would put "laycan" in one chunk and "load" in another, and a question about the cargo would
     * then be answered from half of it. A record this short fits, so it is never split.
     */
    public static String whole(String header, String body) {
        return join(normalise(header), normalise(body));
    }

    /**
     * The body in units no longer than {@code budget}: whole paragraphs where they fit, otherwise
     * their lines, otherwise character slices of a line.
     */
    private static List<Unit> units(String text, int budget) {
        List<Unit> units = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (p.length() <= budget) {
                units.add(new Unit(p, true));
                continue;
            }
            boolean first = true;
            for (String line : p.split("\n")) {
                String l = line.strip();
                if (l.isEmpty()) continue;
                int from = 0;
                while (from < l.length()) {
                    int to = Math.min(l.length(), from + budget);
                    // Never leave half of a surrogate pair at the end of a slice.
                    if (to < l.length() && Character.isHighSurrogate(l.charAt(to - 1))) {
                        to--;
                    }
                    units.add(new Unit(l.substring(from, to), first));
                    first = false;
                    from = to;
                }
            }
        }
        return units;
    }

    private static String join(String head, String body) {
        if (head.isEmpty()) return body;
        if (body.isEmpty()) return head;
        return head + "\n\n" + body;
    }

    /** Line endings as stored by the mailbox and the boards, and no outer space. */
    private static String normalise(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
