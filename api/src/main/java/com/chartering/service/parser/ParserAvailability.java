package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether Intake can read for the work on this thread, and whether its timers run here.
 *
 * <p>Two questions that look alike and are not. {@link #isAvailable()} and {@link #require()}
 * are about the work in hand: a route it can read with exists here — the deployment's local
 * model, or a hosted one under the key the caller sent ({@link ParserRoute}).
 * {@link #runsTimers()} is about the deployment alone: whether this process reads anything on
 * its own account, which it does only with a local model, because a clock has nobody's key.
 *
 * <p><b>Anybody holding a key may read with the cloud.</b> It used to be administrators only,
 * because the key was the desk's, stored here and billed to whoever paid for it; an ordinary
 * user pressing Parse could spend the day's allowance for everyone. The key is now the caller's
 * own and lives in their browser, so what they spend is theirs, and Intake is open to them as
 * it is to everybody on a desk with a local model.
 *
 * <p>The sweep's worker reaches gated code too — a sweep raising a company question compares
 * the signature through {@code IntakePasteService.compare} — and it carries the route of the
 * request that started it ({@link ParserRoute#carry}), or the local one on a timer, so the
 * answer there is the same as it was for the request.
 */
@Component
@RequiredArgsConstructor
public class ParserAvailability {

    private final ParserProperties props;

    /** Whether the work on this thread has a model to read with here. */
    public boolean isAvailable() {
        return ParserRoute.current().cloud() ? props.isCloudEnabled() : props.isEnabled();
    }

    /**
     * Refuses the call with a 404 when {@link #isAvailable()} is false, saying which reason.
     *
     * <p>Named rather than generic because the causes have different cures: a deployment with
     * no parser at all is a deployment decision, while one offering only the cloud model wants
     * the caller to save their key on the Settings tab.
     */
    public void require() {
        if (isAvailable()) return;
        if (props.isCloudEnabled()) {
            throw new FeatureDisabledException(
                    "Intake on this deployment reads with a cloud model under your own key — add it on Settings.");
        }
        throw new FeatureDisabledException(
                "The email parser is not enabled on this deployment (PARSER_ENABLED).");
    }

    /**
     * Whether this process reads mail and boards on its own, without anybody asking.
     *
     * <p>Not about the caller, so it is safe on a timer thread with no request behind it.
     */
    public boolean runsTimers() {
        return props.isEnabled();
    }

    /** Whether this deployment offers a local model and a cloud one: what the UI offers to pick. */
    public boolean localOffered() {
        return props.isEnabled();
    }

    public boolean cloudOffered() {
        return props.isCloudEnabled();
    }
}
