package com.chartering.service.mail;

import com.chartering.config.MailboxProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MimeBodyExtractorTest {

    private final MimeBodyExtractor extractor = new MimeBodyExtractor(new MailboxProperties());

    /**
     * Messages are built from raw RFC 822 text, because the failures this class has to survive
     * are failures of the wire format (a charset nobody registered, a transfer encoding typed
     * wrongly) and a message built through the API would never carry them. The text is written
     * with LF in the source and sent with CRLF, as a mail server delivers it. Bodies are ASCII
     * so the ISO-8859-1 round trip below cannot change them.
     */
    private static MimeMessage mime(String raw) throws Exception {
        String crlf = raw.replace("\n", "\r\n");
        return new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(crlf.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void shouldExtractPlainText_whenSinglePartTextPlain() throws Exception {
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: text/plain; charset=UTF-8
                Content-Transfer-Encoding: 7bit

                25,000 MT wheat, Chornomorsk to Spain Med.
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("25,000 MT wheat, Chornomorsk to Spain Med.");
        assertThat(body.html()).isNull();
        assertThat(body.hasAttachments()).isFalse();
    }

    @Test
    void shouldKeepTextAndHtml_whenMultipartAlternative() throws Exception {
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: multipart/alternative; boundary="BOUND"

                --BOUND
                Content-Type: text/plain; charset=UTF-8

                plain version of the offer
                --BOUND
                Content-Type: text/html; charset=UTF-8

                <html><body><p>html version of the offer</p></body></html>
                --BOUND--
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("plain version of the offer");
        assertThat(body.html()).contains("<p>html version of the offer</p>");
    }

    @Test
    void shouldDeriveText_whenHtmlOnly() throws Exception {
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: text/html; charset=UTF-8

                <html><head><style>p { color: red; }</style></head>
                <body><p>Wheat &amp; barley</p><script>var x = 1;</script></body></html>
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.html()).contains("<p>Wheat &amp; barley</p>");
        assertThat(body.text()).isEqualTo("Wheat & barley");
    }

    @Test
    void shouldExtractText_whenCharsetIsNotRegistered() throws Exception {
        // getContent() throws UnsupportedEncodingException here; the bytes are plain ASCII, so
        // they are still readable once the charset is set aside.
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: text/plain; charset="x-no-such-charset"
                Content-Transfer-Encoding: 7bit

                Odessa to Genoa, laycan 1-5 November.
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("Odessa to Genoa, laycan 1-5 November.");
    }

    @Test
    void shouldExtractText_whenTransferEncodingIsNotRecognised() throws Exception {
        // "8-bit" is a mistyped "8bit"; the decoder refuses it, but the bytes on the wire are
        // what the sender meant, so they are read raw.
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: text/plain; charset=UTF-8
                Content-Transfer-Encoding: 8-bit

                Vessel open Adriatic 10 November.
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("Vessel open Adriatic 10 November.");
    }

    @Test
    void shouldKeepSiblings_whenFirstChildHasUnknownCharset() throws Exception {
        // Before the fix the first child's failure ended the walk, and the HTML after it was
        // never visited.
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: multipart/mixed; boundary="MIX"

                --MIX
                Content-Type: text/plain; charset="x-no-such-charset"
                Content-Transfer-Encoding: 7bit

                first child text
                --MIX
                Content-Type: text/html; charset=UTF-8

                <p>second child html</p>
                --MIX--
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("first child text");
        assertThat(body.html()).contains("<p>second child html</p>");
    }

    @Test
    void shouldListAttachment_notBody_whenTextPartIsAttachment() throws Exception {
        MimeMessage message = mime("""
                From: broker@example.com
                Subject: Cargo
                Content-Type: multipart/mixed; boundary="MIX"

                --MIX
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename="x.txt"

                notes that are only in the attachment
                --MIX
                Content-Type: text/plain; charset=UTF-8

                the covering message
                --MIX--
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.attachmentNames()).containsExactly("x.txt");
        assertThat(body.hasAttachments()).isTrue();
        assertThat(body.text()).isEqualTo("the covering message");
    }

    @Test
    void shouldExtractInnerText_whenMessageIsForwardedInline() throws Exception {
        MimeMessage message = mime("""
                From: colleague@example.com
                Subject: Fwd: Cargo
                Content-Type: message/rfc822

                From: broker@example.com
                Subject: Cargo
                Content-Type: text/plain; charset=UTF-8

                forwarded cargo text
                """);

        MimeBodyExtractor.Body body = extractor.extract(message);

        assertThat(body.text()).isEqualTo("forwarded cargo text");
    }
}
