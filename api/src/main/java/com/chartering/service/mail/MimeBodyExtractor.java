package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pulls the readable parts out of a MIME message: the text, the HTML, and the names of
 * whatever was attached.
 *
 * <p>A real message is a tree, not a body — {@code multipart/alternative} inside
 * {@code multipart/mixed}, forwarded messages nested inside that, inline images that look
 * like attachments but are not. This walks the whole tree and collects both readable forms
 * wherever it finds them, rather than trusting the first part it meets to be the message.
 *
 * <p><b>Attachments are named, not stored</b> (see the README). Recording the filenames
 * costs nothing and answers the question people actually have when looking at an old
 * message — "was the recap attached to this one?" — while storing the bytes would put every
 * PDF that has ever been mailed into the backup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MimeBodyExtractor {

    /** Enough of a tag stripper to derive readable text from an HTML-only message. */
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("(?is)<(script|style)\\b.*?</\\1\\s*>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00a0]+");

    private final MailboxProperties props;

    /**
     * What was found. Either body may be null; {@link #text()} is filled in from the HTML
     * when the message carried no plain part, so nothing is left unsearchable merely for
     * having been sent as HTML.
     */
    public record Body(String text, String html, boolean hasAttachments,
                       List<String> attachmentNames) {

        /** Comma-joined for storage; null when there were none. */
        public String attachmentNamesJoined() {
            return attachmentNames.isEmpty() ? null : String.join(", ", attachmentNames);
        }
    }

    public Body extract(Message message) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        List<String> attachments = new ArrayList<>();
        // walk() contains its own failures, so a body that will not parse is not a reason to
        // lose the message: the headers are the half that links it to a company, and they
        // are already read.
        walk(message, text, html, attachments);

        String plain = trim(text.toString());
        String markup = trim(html.toString());
        if (plain == null && markup != null) {
            plain = htmlToText(markup);
        }
        return new Body(plain, markup, !attachments.isEmpty(), attachments);
    }

    /** A one-line preview for the message list. */
    public String snippet(String text) {
        if (text == null || text.isBlank()) return null;
        String flat = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 297) + "...";
    }

    /**
     * Walks one part and everything under it. A failure is contained here, to the part that
     * raised it: one broken child of a multipart must not end the walk of its siblings, which
     * is how a single unregistered charset used to cost a whole message its HTML.
     */
    private void walk(Part part, StringBuilder text, StringBuilder html, List<String> attachments) {
        try {
            walkPart(part, text, html, attachments);
        } catch (MessagingException | IOException | RuntimeException e) {
            log.warn("Skipped a {} part of a message: {}", contentTypeOf(part), e.toString());
        }
    }

    private void walkPart(Part part, StringBuilder text, StringBuilder html, List<String> attachments)
            throws MessagingException, IOException {

        String filename = filenameOf(part);
        boolean attached = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (filename != null && !part.isMimeType("multipart/*"));

        if (attached) {
            attachments.add(filename != null ? filename : "(unnamed)");
            return;
        }

        if (part.isMimeType("text/plain")) {
            append(text, textOf(part));
        } else if (part.isMimeType("text/html")) {
            append(html, textOf(part));
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                // Each child is its own unit: a child that cannot be fetched is skipped, and
                // the ones after it are still read.
                try {
                    walk(mp.getBodyPart(i), text, html, attachments);
                } catch (MessagingException | RuntimeException e) {
                    log.warn("Skipped child {} of a multipart: {}", i, e.toString());
                }
            }
        } else if (part.isMimeType("message/rfc822")) {
            // A forwarded message. Its text is part of what the reader sees, and part of
            // what they will search for later, so it is walked rather than skipped.
            walk((Part) part.getContent(), text, html, attachments);
        }
        // Anything else (inline images, calendar invites) is left alone deliberately: it is
        // neither readable text nor a file the user asked us to keep.
    }

    private static String filenameOf(Part part) {
        try {
            return part.getFileName();
        } catch (MessagingException e) {
            return null;
        }
    }

    /** The media type alone ("text/plain"), so a warning stays one short line. */
    private static String contentTypeOf(Part part) {
        try {
            return new ContentType(part.getContentType()).getBaseType();
        } catch (MessagingException | RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * The text of a text/plain or text/html part. The normal route is {@code getContent()},
     * which decodes the transfer encoding and the charset together. When that throws, or hands
     * back something other than a String, the bytes are read here instead: they are usually
     * fine, and the failure is in a label on them (a charset name nobody registered, a
     * transfer encoding typed wrongly), not in the words. One warning per part either way.
     */
    private static String textOf(Part part) throws MessagingException, IOException {
        try {
            Object content = part.getContent();
            if (content instanceof String s) return s;
            log.warn("A {} part did not come back as text ({}); reading its bytes instead",
                    contentTypeOf(part), content.getClass().getName());
        } catch (MessagingException | IOException | RuntimeException e) {
            log.warn("A {} part did not decode ({}); reading its bytes instead",
                    contentTypeOf(part), e.toString());
        }
        return decodeBytes(part);
    }

    /**
     * Reads the part's bytes without asking the mail library to decode them, then decodes them
     * ourselves. Transfer-decoded first, since that is what the sender meant; if the transfer
     * encoding itself is the problem, the raw bytes on the wire are the next best thing.
     * Returns null when neither can be read, which leaves the part as nothing rather than
     * failing the whole message.
     */
    private static String decodeBytes(Part part) {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (MessagingException | IOException | RuntimeException e) {
            bytes = rawBytes(part);
        }
        if (bytes == null) return null;
        return decode(bytes, declaredCharset(part));
    }

    private static byte[] rawBytes(Part part) {
        try (InputStream in = rawStream(part)) {
            return in == null ? null : in.readAllBytes();
        } catch (MessagingException | IOException | RuntimeException e) {
            log.debug("No raw bytes for a {} part: {}", contentTypeOf(part), e.toString());
            return null;
        }
    }

    /**
     * The bytes as they sit in the message, transfer encoding and all. Only the two concrete
     * MIME classes expose this; anything else answers null.
     */
    private static InputStream rawStream(Part part) throws MessagingException {
        if (part instanceof MimeMessage m) return m.getRawInputStream();
        if (part instanceof MimeBodyPart b) return b.getRawInputStream();
        return null;
    }

    /** The charset the part declares, as a Java name, or null when it declares none or is junk. */
    private static String declaredCharset(Part part) {
        try {
            String charset = new ContentType(part.getContentType()).getParameter("charset");
            return charset == null ? null : MimeUtility.javaCharset(charset);
        } catch (MessagingException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The declared charset when the JVM has it; otherwise UTF-8 if the bytes are valid UTF-8
     * (most mail that names no charset it can read is UTF-8 anyway); otherwise ISO-8859-1,
     * which maps every byte to something and so never fails. The result is for search and
     * reading, so a stray character is preferable to losing the part.
     */
    static String decode(byte[] bytes, String javaCharset) {
        if (javaCharset != null) {
            try {
                if (Charset.isSupported(javaCharset)) {
                    return new String(bytes, Charset.forName(javaCharset));
                }
            } catch (IllegalArgumentException e) {
                // An illegal charset name: fall through to the guesses below.
            }
        }
        try {
            CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return strict.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Appends up to the configured ceiling and stops. Bodies are stored to be searched and
     * read; the tail of a thread that has been replied to forty times is neither more
     * searchable nor more readable for being kept in full, and the table is what pays.
     */
    private void append(StringBuilder sink, String value) {
        if (value == null || value.isEmpty()) return;
        int room = props.getMaxBodyChars() - sink.length();
        if (room <= 0) return;
        if (sink.length() > 0) sink.append("\n\n");
        sink.append(value, 0, Math.min(value.length(), room));
    }

    /** Readable text out of markup — for search and the preview line, not for display. */
    private String htmlToText(String html) {
        String out = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        out = TAG.matcher(out).replaceAll(" ");
        out = out.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return trim(WHITESPACE.matcher(out).replaceAll(" "));
    }

    private static String trim(String s) {
        if (s == null) return null;
        String out = s.trim();
        return out.isEmpty() ? null : out;
    }
}
