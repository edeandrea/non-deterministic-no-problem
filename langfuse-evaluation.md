# Langfuse Evaluation Strategy: Concepts, Gaps, and Workarounds

## Three-Tier Evaluation Architecture

This document outlines a three-tier approach to evaluating AI application quality using Langfuse, identifies platform and language gaps, and proposes workarounds.

| Tier | When | What | Scope |
|------|------|------|-------|
| **1. Per-trace** | Real-time, as interactions occur | Score individual LLM responses for quality/relevance | Single trace or observation |
| **2. Session-level** | When a conversation ends | Score the full conversation for sentiment/satisfaction | All traces in a session |
| **3. Drift detection** | In CI/CD, after app changes | Compare new outputs against historical baselines to detect regressions | Dataset of historical input/output pairs |

---

## Tier 1: Per-Trace Scoring

> **Langfuse support: Fully native.**

Langfuse evaluation rules trigger automatically on ingested traces or observations. You define an evaluator (LLM-as-judge with a prompt template), create an evaluation rule mapping variables like `{{input}}` and `{{output}}`, and Langfuse scores asynchronously at ingest time. Supports filtering by trace name, tags, observation type, and sampling percentage.

**No gaps.** This is Langfuse's core strength.

---

## Tier 2: Session-Level Scoring

> **Langfuse support: Partial — storage and visualization only.**

### Gaps

- **Sessions are not a supported evaluation target** *(Platform gap)*
  Langfuse evaluation rules can only target observations, traces, or experiments. There is no automatic trigger when a session ends, and no way to define an evaluator that receives all traces from a session as a group. This is a Langfuse platform limitation regardless of language.
  **Roadmap status:** Confirmed on roadmap by Langfuse maintainer in [discussion #12507](https://github.com/orgs/langfuse/discussions/12507) ("yes this is on our roadmap") but with no specific timeline. Multiple community discussions request this: [#13960](https://github.com/orgs/langfuse/discussions/13960) (session-level LLM-as-judge, unanswered), [#4208](https://github.com/orgs/langfuse/discussions/4208) (multi-turn/session experiments, 16 votes — addressed via cookbook/workaround, not native feature), [#11219](https://github.com/orgs/langfuse/discussions/11219) (aggregated session scores — maintainer confirmed session-level LLM-as-judge is on roadmap, linked to [#6777](https://github.com/orgs/langfuse/discussions/6777)). Also tangentially related: issue [#12873](https://github.com/langfuse/langfuse/issues/12873) (RFC for session-boundary drift monitoring, different scope).

- **No cross-item evaluation** *(Platform gap)*
  Langfuse evaluators operate on one item at a time. There's no mechanism to pass multiple traces/items into a single evaluator invocation for holistic assessment. This applies to all SDKs and the REST API equally.
  **Roadmap status:** No dedicated issue or discussion found. Partially overlaps with the session-level evaluation requests above, since session evaluation inherently requires cross-item context.

### Workaround

Custom application code that:

1. Listens for a session-end event
2. Fetches all traces for the session via the Langfuse API
3. Passes them to an LLM judge for evaluation
4. Posts the score back to Langfuse via the Score API

This is the only viable approach — Langfuse cannot orchestrate session-level evaluation in any language.

---

## Tier 3: Drift Detection in CI/CD

> **Langfuse support: Partial — datasets and experiment results storage, but not orchestration.**

### Two Modes of Drift Detection

#### 3a. Regression Testing (same inputs, detect output drift)

Re-run the application against previously recorded inputs from Langfuse datasets. Compare the new outputs against the stored historical outputs to detect drift — whether caused by model updates, infrastructure changes, or unintended side effects.

This is an integration-style test: the full application is exercised end-to-end via its external interfaces (REST, WebSocket, etc.), driven by dataset items instead of a human.

#### 3b. Change Validation (new/modified inputs, compare against historical baselines)

After an intentional change (prompt, business logic, RAG retrieval, data pipeline), run the application with the changed inputs and compare outputs against previous outputs for similar operations. The goal is not to check "did it change?" (it should have) but "did it regress?" — an LLM judge determines whether new outputs are improvements, equivalent, or regressions relative to the baseline.

#### Evaluation Strategy by Mode

| Mode | What changed | Evaluation strategy |
|------|-------------|-------------------|
| **3a. Regression** | Nothing intentional — model update, infra, etc. | Semantic similarity (fast, local, no LLM call) |
| **3b. Change validation** | Prompt, business logic, data retrieval, RAG | LLM judge: "regression, equivalent, or improvement?" |

In both modes, Langfuse dataset items serve as the baseline. The stored `expectedOutput` is the historical output. The evaluation strategy differs, but the infrastructure is the same.

### What Langfuse Provides

- **Dataset storage:** Items with `input` and `expectedOutput` (historical baselines), versioned and organized by dataset name.
- **Experiment result tracking:** The `dataset-run-items` API links traces to dataset items under a named run. Results are visible in the Langfuse comparison UI.
- **Experiment-level evaluators:** Evaluators with `target=experiment` can automatically score each item, with access to `{{ground_truth}}` (the expected output). Langfuse can handle per-item judging for experiments.
- **CI/CD GitHub Action:** `langfuse/experiment-action` automates the flow — fetches datasets, runs a task function, evaluates, gates on threshold, posts PR comments.

### Gaps

- **No Java SDK for `run_experiment`** *(Language gap — Java only)*
  The experiment SDK (`run_experiment`, `RunnerContext`, `RegressionError`) exists for Python and JS/TS only. Java applications must orchestrate experiments manually via the REST API. If the application were built in Python or JS/TS, this would not be a gap.
  **Roadmap status:** A Java SDK exists ([discussion #4589](https://github.com/orgs/langfuse/discussions/4589), marked ✅ Done) but it covers tracing and prompt management only — not experiment orchestration. The Langfuse team recommends OpenTelemetry for tracing and the Java SDK for prompt management. Dataset run creation is not exposed in the Java SDK ([discussion #8184](https://github.com/orgs/langfuse/discussions/8184) — must use REST API directly). No feature request exists for adding `run_experiment` to the Java SDK.

- **No "trigger experiment" REST API** *(Platform gap)*
  The REST API lets you record experiment results (create run items, link traces), but cannot trigger an experiment execution. Orchestration must happen in application code. This affects all languages equally — even Python/JS apps use their SDK to orchestrate locally, not a server-side trigger.
  **Roadmap status:** Active community interest. [Discussion #9131](https://github.com/orgs/langfuse/discussions/9131) (6 votes) requested programmatic prompt experiment triggers — Langfuse responded by publishing the `langfuse/experiment-action` GitHub Action (May 2026). [Discussion #4587](https://github.com/orgs/langfuse/discussions/4587) (4 votes) requested SDK/API experiment runs — maintainer stated "there is no near-term plan to extend the in-ui prompt experiments to the SDKs." Issue [#11576](https://github.com/langfuse/langfuse/issues/11576) (closed, stale) confirmed webhook-based model. The GitHub Action partially addresses this gap for Python/JS users, but no server-side trigger API is planned.

- **CI/CD action requires Python or JS** *(Language gap — Java only)*
  The `langfuse/experiment-action` expects a Python or JS experiment script. It cannot directly execute a Java test or invoke a Java application natively. If the application were built in Python or JS/TS, the action would work out of the box.
  **Roadmap status:** No open issues or discussions found requesting non-Python/JS support for the experiment action.

- **Live evaluators cannot access dataset items** *(Platform gap)*
  For comparing a new trace against historical dataset items during live production evaluation, Langfuse evaluators have no access to dataset items. This is a recognized missing feature and applies regardless of language.
  **Roadmap status:** Open feature request in GitHub Discussions: [#11735](https://github.com/orgs/langfuse/discussions/11735) — "Support linking Ground Truth from Datasets in Live Tracing Evaluators & Registering external evaluators in UI." Langfuse team suggested a workaround of attaching ground truth directly to trace metadata so it can be used in LLM-as-a-Judge evaluators, but the underlying feature has not been shipped.

### Workarounds

#### Option A — Python/JS experiment script as integration test harness

1. Start the application in CI
2. A Python/JS experiment script fetches dataset items from Langfuse
3. The script calls the running application's endpoints with each item's input
4. The application processes end-to-end (business logic, RAG, LLM, guardrails)
5. The script evaluates outputs against expected outputs
6. Raises `RegressionError` if below threshold, failing the CI job
7. Posts PR comment with results via the Langfuse GitHub Action

> **Benefit:** Full Langfuse experiment infrastructure — comparison UI, PR comments, regression detection, metadata propagation (commit SHA, branch).
>
> **Tradeoff:** Introduces a Python/JS dependency in a Java CI pipeline.

#### Option B — Pure Java with a scorer/evaluation framework

1. Fetch dataset items via Langfuse REST API
2. Run the application against each input using a test framework
3. Evaluate using semantic similarity (local, fast) or LLM judge (for intentional changes)
4. Assert on threshold, fail the build if below
5. Post results back to Langfuse via `dataset-run-items` and Score APIs

> **Benefit:** Everything stays in the application's native language and tooling.
>
> **Tradeoff:** Must build the orchestration, result posting, and CI reporting yourself.

#### Option C — Hybrid

Use Option B for execution but create dataset run items in Langfuse so results appear in the experiment comparison UI.

---

## Langfuse Prompt Templating Limitations

- **No loop/iteration syntax** *(Platform gap)*
  Langfuse prompt templates use `{{variable}}` mustache-style substitution only. There is no loop construct for iterating over arrays. To pass a list of items, you must either pre-render the formatted text before passing it as a single variable, or pass raw JSON and instruct the LLM to parse it.
  **Roadmap status:** No issues found requesting loop syntax. [#12810](https://github.com/langfuse/langfuse/issues/12810) (open) requests JSON bulk input for Playground variables, which is adjacent but not about template iteration.

- **Top-level JSON schema must be an object** *(Platform gap)*
  Langfuse rejects schemas where the root type is `array`. Arrays must be wrapped in an object property.
  **Roadmap status:** [#10157](https://github.com/langfuse/langfuse/issues/10157) (open) — "Cannot add structured output of type `array`" directly confirms this gap. Still unresolved.

- **Prompt config field**
  The "Arbitrary JSON configuration" field is a metadata sidecar for model parameters, tool/function-calling definitions (LLM tool-calling schemas, not programming functions), and response format schemas. It is versioned with the prompt but has no effect on template rendering.

---

## Summary: Langfuse's Role by Tier

| Capability | Tier 1 (Per-trace) | Tier 2 (Session) | Tier 3a (Regression) | Tier 3b (Change validation) |
|---|---|---|---|---|
| Automatic trigger | Yes | No | No | No |
| Evaluator execution | Yes | No | Partial | Partial |
| Dataset storage | N/A | Yes | Yes | Yes |
| Score storage | Yes | Yes | Yes | Yes |
| Comparison UI | Yes | Yes | Yes | Yes |
| Orchestration | Yes | Custom code | Custom code or Python/JS harness | Custom code or Python/JS harness |

---

## Gap Classification

| Gap | Type | Java only? | Roadmap status |
|-----|------|-----------|----------------|
| No session evaluation target | Platform | No — all languages | **On roadmap** per maintainer ([#12507](https://github.com/orgs/langfuse/discussions/12507)), no timeline. Multiple discussions: [#4208](https://github.com/orgs/langfuse/discussions/4208) (16 votes), [#13960](https://github.com/orgs/langfuse/discussions/13960), [#11219](https://github.com/orgs/langfuse/discussions/11219) |
| No cross-item evaluation | Platform | No — all languages | No dedicated issue; overlaps with session evaluation requests |
| No "trigger experiment" API | Platform | No — all languages | No server-side API planned. GitHub Action ([#9131](https://github.com/orgs/langfuse/discussions/9131)) partially addresses for Python/JS. Maintainer stated no near-term SDK plans ([#4587](https://github.com/orgs/langfuse/discussions/4587)) |
| Live evaluators can't access dataset items | Platform | No — all languages | **Open discussion** [#11735](https://github.com/orgs/langfuse/discussions/11735); workaround suggested, feature not shipped |
| No loop syntax in prompt templates | Platform | No — all languages | No open issue. Adjacent request [#12810](https://github.com/langfuse/langfuse/issues/12810) for JSON bulk input |
| Root JSON schema must be object | Platform | No — all languages | **Open issue** [#10157](https://github.com/langfuse/langfuse/issues/10157) |
| No Java experiment SDK | Language | Yes — Python/JS have full support | Java SDK exists ([#4589](https://github.com/orgs/langfuse/discussions/4589)) but covers tracing/prompts only, not experiments. Dataset runs not exposed ([#8184](https://github.com/orgs/langfuse/discussions/8184)) |
| CI/CD action requires Python/JS | Language | Yes — Python/JS work natively | No open issues or discussions |
