package com.chartering.service.mail;

import com.chartering.service.SettingsService.CirculationSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Properties;

/**
 * The mailbox's SMTP connection, built from the settings currently in force.
 *
 * <p>Extracted from {@code SmtpCircularSender} when replies gained the ability to go out
 * the same way. Both flows send through the user's own mailbox and both must obey a host or
 * port changed on the Settings tab, and neither is the right place to own the rule for how
 * a changed port implies a TLS mode.
 *
 * <p><b>Whose connection</b> is the caller's: the mailbox of the person on the thread
 * ({@link MailAccounts#current()}). The server's mailbox goes through the Spring-configured
 * sender exactly as before; a personal one gets a sender built from its own saved host, port
 * and login. Somebody with neither has no connection at all, and every answer here says so.
 */
@Component
@RequiredArgsConstructor
public class SmtpTransport {

    /** The Spring-configured sender: the server's mailbox, and the transport defaults for any other. */
    private final JavaMailSender mailSender;
    private final MailAccounts accounts;

    /** Whether the caller has a mailbox to send from at all. */
    public boolean hasMailbox() {
        return accounts.current().isPresent();
    }

    /** The mailbox the app authenticates as, for the config screen. Never the password. */
    public String username() {
        Optional<MailAccounts.Mailbox> mailbox = accounts.current();
        if (mailbox.isEmpty()) return null;
        if (!mailbox.get().environment()) return mailbox.get().username();
        JavaMailSenderImpl impl = asImpl();
        return impl == null ? null : impl.getUsername();
    }

    public boolean hasUsername() {
        return isSet(username());
    }

    /**
     * Whether a password is configured — the question the config screen actually asks. The
     * password itself is deliberately not reachable from here: nothing outside this class
     * needs it, and a getter for it would be a getter for it.
     */
    public boolean hasPassword() {
        Optional<MailAccounts.Mailbox> mailbox = accounts.current();
        if (mailbox.isEmpty()) return false;
        if (!mailbox.get().environment()) return isSet(mailbox.get().password());
        JavaMailSenderImpl impl = asImpl();
        return impl != null && isSet(impl.getPassword());
    }

    /**
     * A sender pointed at the host/port currently configured in Settings, carrying the
     * credentials and transport properties of the Spring-configured one.
     *
     * <p>A fresh instance rather than mutating the shared bean: the bean is a singleton, and
     * reconfiguring it in place would make the host depend on whoever sent last.
     *
     * <p>When the port is changed, the TLS mode moves with it by convention — 465 is
     * implicit SSL, anything else is STARTTLS — because the two are not independently
     * meaningful and a port change alone would otherwise just fail to connect. Leaving the
     * port at its configured value keeps whatever MAIL_SSL/MAIL_STARTTLS said, so an
     * environment tuned by hand is never second-guessed.
     */
    public JavaMailSenderImpl senderFor(CirculationSettings s) {
        JavaMailSenderImpl base = asImpl();
        Optional<MailAccounts.Mailbox> mailbox = accounts.current();
        if (base == null || mailbox.isEmpty()) {
            return null;
        }
        if (!mailbox.get().environment()) {
            return personalSender(base, mailbox.get());
        }
        boolean unchanged = s.smtpPort() == base.getPort()
                && s.smtpHost() != null && s.smtpHost().equalsIgnoreCase(base.getHost());
        if (unchanged) {
            return base;
        }

        JavaMailSenderImpl out = new JavaMailSenderImpl();
        out.setHost(s.smtpHost());
        out.setPort(s.smtpPort());
        out.setUsername(base.getUsername());
        out.setPassword(base.getPassword());
        out.setProtocol(base.getProtocol());
        out.setDefaultEncoding(base.getDefaultEncoding());

        Properties p = new Properties();
        p.putAll(base.getJavaMailProperties());
        if (s.smtpPort() != base.getPort()) {
            boolean implicitSsl = s.smtpPort() == 465;
            p.setProperty("mail.smtp.ssl.enable", String.valueOf(implicitSsl));
            p.setProperty("mail.smtp.starttls.enable", String.valueOf(!implicitSsl));
        }
        // Certificate trust names a host, so it has to follow the host it was set for.
        p.setProperty("mail.smtp.ssl.trust", s.smtpHost());
        out.setJavaMailProperties(p);
        return out;
    }

    /**
     * A personal mailbox's own connection: its host, port and login, with the transport's
     * timeouts and encoding. The TLS mode follows the port by the same rule a changed port
     * follows above - implicit on 465, STARTTLS otherwise - because nobody filling in a
     * mailbox form knows which one their provider wants, and the port already says.
     */
    private static JavaMailSenderImpl personalSender(JavaMailSenderImpl base, MailAccounts.Mailbox m) {
        JavaMailSenderImpl out = new JavaMailSenderImpl();
        out.setHost(m.smtpHost());
        out.setPort(m.smtpPort());
        out.setUsername(m.username());
        out.setPassword(m.password());
        out.setProtocol(base.getProtocol());
        out.setDefaultEncoding(base.getDefaultEncoding());
        Properties p = new Properties();
        p.putAll(base.getJavaMailProperties());
        boolean implicitSsl = m.smtpPort() == 465;
        p.setProperty("mail.smtp.auth", "true");
        p.setProperty("mail.smtp.ssl.enable", String.valueOf(implicitSsl));
        p.setProperty("mail.smtp.starttls.enable", String.valueOf(!implicitSsl));
        p.setProperty("mail.smtp.ssl.trust", m.smtpHost());
        out.setJavaMailProperties(p);
        return out;
    }

    private JavaMailSenderImpl asImpl() {
        return mailSender instanceof JavaMailSenderImpl impl ? impl : null;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
