package com.chartering.service.parser;

import java.util.function.Supplier;

/**
 * Which model reads mail for the work on this thread: the deployment's own local model, or a
 * hosted one paid for with the key of the person who asked.
 *
 * <p><b>The key is the person's and lives in their browser, never here.</b> It arrives on each
 * Intake request as a header ({@code ParserRouteFilter}), sits on the request thread for as long
 * as the request does, and is carried into the sweep's worker for the length of a run that
 * request started — and nowhere else: not in {@code app_settings}, not in a log, not in a
 * response. That is what lets the hosted instance offer Intake at all without a key somebody
 * else pays for, and what made the old "administrators only under CLOUD" rule unnecessary: the
 * tokens spent are the spender's own.
 *
 * <p><b>Timers never see one.</b> A thread with nothing bound reads {@link #LOCAL}, so the
 * sweep, the reconcile pass and the board fetch always use the deployment's local model and run
 * only where there is one ({@link ParserAvailability#runsTimers()}). A clock has nobody's key.
 *
 * <p>Whether the deployment offers either route is {@link ParserAvailability}'s question; this
 * class only says which one the caller chose.
 */
public record ParserRoute(boolean cloud, String apiKey) {

    /** The deployment's own model, and what every thread with nothing bound reads. */
    public static final ParserRoute LOCAL = new ParserRoute(false, null);

    private static final ThreadLocal<ParserRoute> BOUND = new ThreadLocal<>();

    public ParserRoute {
        if (cloud && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("A cloud route needs the caller's API key");
        }
    }

    public static ParserRoute cloud(String apiKey) {
        return new ParserRoute(true, apiKey.strip());
    }

    /** The route on this thread, else {@link #LOCAL}. */
    public static ParserRoute current() {
        ParserRoute bound = BOUND.get();
        return bound != null ? bound : LOCAL;
    }

    public static <T> T callWith(ParserRoute route, Supplier<T> work) {
        ParserRoute previous = BOUND.get();
        BOUND.set(route);
        try {
            return work.get();
        } finally {
            if (previous == null) BOUND.remove();
            else BOUND.set(previous);
        }
    }

    public static void runWith(ParserRoute route, Runnable work) {
        callWith(route, () -> {
            work.run();
            return null;
        });
    }

    /**
     * {@code work}, ready to hand to another thread with this thread's route — how "Parse
     * everything" started from a browser holding a key keeps reading with that key on the
     * worker. Held in the closure for the length of the run and dropped with it.
     */
    public static Runnable carry(Runnable work) {
        ParserRoute route = current();
        return () -> runWith(route, work);
    }

    /** Never prints the key: a record's generated toString would. */
    @Override
    public String toString() {
        return cloud ? "ParserRoute[CLOUD]" : "ParserRoute[LOCAL]";
    }
}
