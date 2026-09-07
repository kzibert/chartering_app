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
 * <p><b>Off by default, and off on the hosted deployment.</b> Render has no GPU and cannot
 * reach a workstation on a home network, so a hosted instance with this switched on would
 * spend a request every sweep proving it. Off, the tab is absent from the navigation and
 * every endpoint behind it answers 404 — the same shape as {@link AnalysisProperties}, and
 * for the same reason: the feature is genuinely not part of that deployment.
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

    /** Master switch. Off means the feature is not part of this deployment at all. */
    private boolean enabled = false;

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
