package com.chartering.service.parser;

import com.chartering.config.ParserProperties;
import com.chartering.exception.FeatureDisabledException;
import com.chartering.model.UserRole;
import com.chartering.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who may use Intake on this deployment, and whether its timers run here.
 *
 * <p>Two questions that look alike and are not. {@link #isAvailable()} and {@link #require()}
 * are about a person: the feature exists here and the caller may use it. {@link #runsTimers()}
 * is about the deployment alone: whether this process reads anything on its own account. The
 * second is why a cloud deployment is not simply "the local one with a different URL" — the
 * office instance and the hosted one share one database, so a timer that is harmless on one is
 * a second reader of the same queue on the other.
 *
 * <p><b>Under {@code CLOUD} the feature is open to administrators only.</b> Every read is
 * billed per token against a key somebody pays for, and the free tiers are counted in tokens a
 * day, so an ordinary user pressing Parse on a Monday could spend the day's allowance for the
 * whole desk. Administrators are the people who answer for the key. For anybody else the
 * feature behaves exactly as if {@code PARSER_ENABLED} were off: the status says so and every
 * gated endpoint answers 404. That is the same reasoning the codebase gives for 404 over 403
 * elsewhere — a 403 would confirm that the thing exists here and that a permission is what is
 * missing, which is information about another person's deployment that a non-administrator has
 * no reason to have.
 *
 * <p><b>No timers under {@code CLOUD}.</b> The sweep, the reconcile pass and the board fetch
 * for Intake all run on a clock. Under a paid or quota-limited model a clock is spending on
 * nobody's behalf, so a cloud deployment reads only when an administrator asks. Read the
 * sweep's "Parse now" and "Parse everything" as the only way in.
 */
@Component
@RequiredArgsConstructor
public class ParserAvailability {

    private final ParserProperties props;

    /** Whether this caller may use Intake here, now. */
    public boolean isAvailable() {
        return props.isEnabled() && (!props.isCloud() || callerMayUse());
    }

    /**
     * Refuses the call with a 404 when {@link #isAvailable()} is false, saying which reason.
     *
     * <p>The message is named rather than generic because the two causes have different cures:
     * a switch that is off is a deployment decision, while an ordinary account on a cloud desk
     * is simply not who this is for.
     */
    public void require() {
        if (!props.isEnabled()) {
            throw new FeatureDisabledException(
                    "The email parser is not enabled on this deployment (PARSER_ENABLED).");
        }
        if (props.isCloud() && !callerMayUse()) {
            throw new FeatureDisabledException(
                    "Intake on this deployment reads with a cloud model and is open to administrators only.");
        }
    }

    /**
     * Whether this process reads mail and boards on its own, without anybody asking.
     *
     * <p>Not about the caller, so it is safe on a timer thread with no request behind it.
     */
    public boolean runsTimers() {
        return props.isEnabled() && !props.isCloud();
    }

    /**
     * Read from the request's security context, not from a parameter: the gate sits in services
     * that are called from several controllers, and threading the principal through each of
     * them would be the same check written down forty times.
     *
     * <p>An administrator on a request; or no caller at all, which is the sweep's own worker.
     *
     * <p>The worker reaches gated code too - a sweep raising a company question compares the
     * signature through {@code IntakePasteService.compare} - and it runs with no login on the
     * thread. Refusing it there failed every email with a signature block in it. It is safe to
     * let through: every HTTP request is authenticated ({@code anyRequest().hasRole("USER")} in
     * {@code SecurityConfig}), so a thread with no authentication is never a request, and under
     * {@code CLOUD} there is no timer, so the worker only runs because an administrator's request
     * started it. An authentication of any other shape is a request and is refused.
     */
    private static boolean callerMayUse() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return true;
        return AuthenticatedUser.current()
                .map(user -> user.role().atLeast(UserRole.TENANT_ADMIN))
                .orElse(false);
    }
}
