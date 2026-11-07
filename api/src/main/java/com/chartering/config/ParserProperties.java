package com.chartering.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The email parser: reading incoming mail into cargoes and open positions.
 *
 * <p><b>The model is not in this application and is not in this repository.</b> It is an
 * HTTP endpoint — the sibling {@code chartering-ml} project serves a finetuned Qwen3-4B
 * through llama.cpp behind a JSON schema — and everything here is the address of that
 * endpoint and what to do when it does not answer. That split is not incidental: the model
 * wants a GPU, 3GB of weights and a CUDA runtime, none of which belongs in a container whose
 * whole virtue is that it runs anywhere.
 *
 * <p><b>Two routes, each its own switch, and both facts about the deployment.</b>
 * {@link #enabled} is the local model: the office GPU, the timers that read mail on their own,
 * and every person on the desk reading with it. {@link #cloudEnabled} is a hosted model read
 * with the caller's own key, which the browser holds and sends per request
 * ({@code ParserRoute}); the server never stores it, and nothing reads with it on a timer. The
 * office runs both, and a person picks one on the Settings tab; Render has no GPU and no route
 * to one, so it runs the cloud route alone. With neither on, the tab is absent from the
 * navigation and every endpoint behind it answers 404, the {@link AnalysisProperties} shape.
 *
 * <p><b>Two settings deliberately live elsewhere.</b> How often the sweep runs, and how many
 * messages it takes at a time, are in {@code app_settings} and on the Settings tab rather
 * than here — they are the knobs a user turns while watching the queue, and an environment
 * variable is a redeploy. Everything in this class is a fact about the deployment: where the
 * server is, how long to wait for it, what a runaway answer costs.
 */
@Component
@ConfigurationProperties(prefix = "chartering.parser")
@Data
public class ParserProperties {

    /**
     * The local model ({@code PARSER_ENABLED}). Off means no local reading and no timers here;
     * the cloud route below may still be offered.
     */
    private boolean enabled = false;

    /**
     * Whether a person may read with a hosted model under their own key
     * ({@code PARSER_CLOUD_ENABLED}). The key is never configuration: it arrives with each
     * request from the browser that holds it. What the cloud model is <em>called</em> — its
     * address and name — is the installation's and lives on the Settings tab
     * ({@code ParserSettings.cloudEndpoint}), because that answer is the same for the office and
     * the hosted instance, which share one {@code app_settings} table.
     */
    private boolean cloudEnabled = false;

    /**
     * The hosted chat-completions endpoint used under {@code CLOUD}, the default behind a
     * Settings-tab override. Groq's OpenAI-compatible API, because that is the key in hand;
     * anything speaking the same protocol with a Bearer key works the same way.
     */
    private String cloudUrl = "https://api.groq.com/openai/v1/chat/completions";

    /**
     * The hosted model's name, the default behind a Settings-tab override. Always sent.
     *
     * <p>Was {@code llama-3.3-70b-versatile}, which Groq no longer serves: a key issued in
     * November 2026 lists only the gpt-oss and qwen chat models, and the old default answered
     * 404 "model_not_found". gpt-oss-120b read a test circular's vessel, position, cargo and
     * signature completely through the paste endpoint (2026-11-08). A provider's model list
     * moves; {@code GET <base>/v1/models} with the key says what it serves today.
     */
    private String cloudModel = "openai/gpt-oss-120b";

    /**
     * The answer's token ceiling under {@code CLOUD}, apart from {@link #maxTokens}.
     *
     * <p>Lower because a hosted free tier counts the requested ceiling against its tokens a
     * minute before the model has written anything: a free tier's tokens a minute (8,000 for gpt-oss-120b on Groq),
     * less a 5,000-token prompt, does not leave room for 6,144 more, and the request is
     * refused as too large rather than answered short. 4,096 covers all but the longest
     * position lists in the corpus.
     */
    private int cloudMaxTokens = 4_096;

    /** Whether either route is part of this deployment. */
    public boolean isAnyEnabled() {
        return enabled || cloudEnabled;
    }


    /**
     * The chat-completions endpoint.
     *
     * <p>The compose default reaches the workstation through {@code host.docker.internal},
     * which is what {@code extra_hosts: host-gateway} in the compose file exists to make
     * resolvable — the same route the api already takes to a Postgres published on a host
     * port. The server itself is {@code docker compose -f serve/docker-compose.llamacpp.yml
     * up -d} in {@code chartering-ml}, on 8090.
     */
    private String url = "http://host.docker.internal:8090/v1/chat/completions";

    /**
     * The model name, sent only when set.
     *
     * <p>llama-server serves one model and ignores it. Ollama refuses the request without
     * one, because it can hold many — so pointing this at an Ollama instead is this variable
     * and the url, and nothing else. Note that Ollama scores markedly worse on the same
     * weights and schema (0.747 vessel F1 against 0.955); the chartering-ml README has the
     * measurement and the reason.
     */
    private String model = "";

    /**
     * How long to wait for a connection.
     *
     * <p>Short, because the failure this catches is the box being off, and a sweep of forty
     * emails must not spend forty timeouts discovering it. The read timeout below is the one
     * that has to be generous.
     */
    private int connectTimeoutMs = 5_000;

    /**
     * How long to wait for the answer.
     *
     * <p>Generously sized, and it has to be. A long position list is 7,600 tokens of prompt
     * and can be 1,500 of answer; at the 81 tokens/sec the CUDA build measures that is
     * comfortably inside this, but the same email on a machine that has fallen back to CPU
     * runs at 12 tokens/sec and takes minutes. Timing out at three minutes turns that into a
     * FAILED row that says so, rather than a request held open until something else gives up.
     */
    private int readTimeoutMs = 180_000;

    /**
     * How much of a body is sent.
     *
     * <p>The same cap the corpus is captured under, and the same reasoning: what runs past
     * it is a quoted chain, a disclaimer and a signature block rendered as text. It is also
     * a hard requirement rather than a tidiness one — the server is started with an 8,192
     * token context that the prompt and the answer share, and a 100KB Outlook thread would
     * not leave room for the answer.
     */
    private int maxBodyChars = 20_000;

    /**
     * The answer's token ceiling.
     *
     * <p>6,144 against a longest observed gold answer of 4,958. A request-level cap
     * overrides the server's own {@code num_predict}, so this number is the one that governs
     * — which is why it is written here rather than left to whatever the container was
     * started with.
     */
    private int maxTokens = 6_144;

    /**
     * How many times a failed parse is tried again.
     *
     * <p>Counted per message, across sweeps. Three is enough to ride out a workstation that
     * was asleep for two of them, and few enough that an email the model genuinely cannot
     * finish stops costing three minutes an hour forever. A message at the ceiling is not
     * lost — the Intake tab lists failures, and re-parsing one by hand resets it.
     */
    private int maxAttempts = 3;
}
