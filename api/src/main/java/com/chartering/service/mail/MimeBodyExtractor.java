package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
        try {
            walk(message, text, html, attachments);
        } catch (MessagingException | IOException e) {
            // A body that will not parse is not a reason to lose the message: the headers
            // are the half that links it to a company, and they are already read.
            log.warn("Could not read the body of a message: {}", e.toString());
        }

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

    private void walk(Part part, StringBuilder text, StringBuilder html, List<String> attachments)
            throws MessagingException, IOException {

        String filename = filenameOf(part);
        boolean attached = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                || (filename != null && !part.isMimeType("multipart/*"));

        if (attached) {
            attachments.add(filename != null ? filename : "(unnamed)");
            return;
        }

        if (part.isMimeType("text/plain")) {
            append(text, asString(part));
        } else if (part.isMimeType("text/html")) {
            append(html, asString(part));
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                walk(child, text, html, attachments);
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

    private static String asString(Part part) throws MessagingException, IOException {
        Object content = part.getContent();
        return content instanceof String s ? s : String.valueOf(content);
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
