# AI Roadmap

Plan to grow the app's LLM side beyond extraction: retrieval, a tool-using assistant, observability, and a Python service. Written 2026-10-07.

## Starting point
- Hand-written OpenAI-compatible clients against llama-server (`EmailParserClient`, `FeedLlmClient`), selectable via `ModelEndpoint` settings.
- Structured output through a JSON schema; extraction prompt pinned to the training prompt (SHA-256 test).
- PostgreSQL on Neon (`pg_trgm` already used; pgvector available).
- Spring Boot 3.4.1 / Java 21 (compatible with Spring AI 1.0); Actuator already in the POM.
- `chartering-ml` scoring harness: base vs few-shot vs fine-tuned on one evaluation set, with a score history.

## Order
1 → 2 → 4 → 3 → 5. Retrieval first; observability before the agent so its cost and behaviour are measured from day one. Rough total: 7–9 weeks of evenings.

---

## 1. Spring AI + pgvector + retrieved few-shot (~2 weeks)
**Covers:** LLM framework, embeddings, vector store.
- [ ] Add Spring AI, pointed at llama-server (OpenAI-compatible API).
- [ ] Migrate `FeedLlmClient` to Spring AI first. Leave `EmailParserClient` as is (its prompt is pinned to training).
- [ ] Enable the `vector` extension on Neon (Flyway migration) and add an embeddings table.
- [ ] Local embedding model via llama.cpp `--embedding`; no data leaves the machine.
- [ ] Embed the labelled email corpus.
- [ ] For each incoming email, retrieve the k most similar labelled examples and use them as few-shot examples.
- [ ] Score retrieved few-shot vs fixed few-shot vs fine-tuned in the `chartering-ml` harness.

**Outcome to measure:** extraction accuracy with retrieved vs fixed few-shot examples.

## 2. Q&A over the market (~1–2 weeks)
**Covers:** full RAG (retrieve → answer with citations).
- [ ] "Ask the desk" panel: e.g. "Which Supramax open in ECSA next 10 days?", "What did broker X offer last week?"
- [ ] Hybrid retrieval: `pg_trgm` keyword search + vector similarity; try with and without a reranker.
- [ ] Answers cite their source emails / records.
- [ ] Eval set of 30–50 hand-written questions: retrieval recall@k and groundedness (answer supported by cited sources).

**Outcome to measure:** recall@k and groundedness on the eval questions.

## 3. Broker assistant agent + cloud LLM (~2 weeks)
**Covers:** direct provider API (Claude or OpenAI), tool / function calling, agents.
- [ ] Add a cloud provider behind `ModelEndpoint`, switchable at runtime like today.
- [ ] Tools over existing services: `searchCargoes`, `searchPositions`, `runMatch`, `draftCircular`.
- [ ] Guardrails: read-only tools run freely; anything that sends mail goes through the existing human-review step.
- [ ] Privacy: by default, only public board posts or anonymized data go to the cloud model; private mail stays on the local model.
- [ ] Scenario eval: 15–20 scripted broker tasks, scored on task success and correct tool calls.

**Outcome to measure:** task success rate and tool-call accuracy; cost per task.

## 4. LLM observability (~1 week)
**Covers:** LLM monitoring and evaluation in production; Prometheus / Grafana.
- [ ] Persist one row per LLM call: model, prompt version, prompt/completion tokens, latency, outcome (the `Completion` record already carries tokens and duration).
- [ ] Micrometer metrics via Actuator.
- [ ] Prometheus + Grafana in Docker Compose; dashboard for cost, latency, error rate, review-queue rejection rate.
- [ ] Optional: self-hosted Langfuse for traces.
- [ ] Track the review-queue reject rate as a live quality signal next to the offline scores.

**Outcome to measure:** per-model latency and cost; reject rate over time.

## 5. Python as a running service (~1 week)
**Covers:** a deployable Python service (still a personal project).
- [ ] FastAPI service in `chartering-ml` serving embeddings (and the reranker), run in Docker Compose.
- [ ] Java calls it instead of the in-process / llama.cpp embedding path; compare latency.

**Outcome to measure:** embedding / rerank latency vs the previous path.

---

## When a phase ships
Record the measured numbers here, and update the project description used for the CV (`Job Applications/CV/Builder/projects/chartering-app.md`): remove "Not done: RAG, embeddings, vector search" and add the new features and skills.
