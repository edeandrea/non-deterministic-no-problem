TITLE: Spike: compare a LangChain4j decision model (Jev) with the LLM email classifier

## Summary

After the agentic email claim intake (#216) lands, run a time-boxed spike to find out whether a **decision model** (TypeSafe AI's Jev, or a local System One model on Ollama) could replace the LLM for the intake's typed decisions: email classification, category mapping and the completeness check. The output is a measured recommendation, not production code.

## Background

- LangChain4j **1.21.0** added an experimental `DecisionModel` API (https://docs.langchain4j.dev/tutorials/decision-models). It asks typed questions instead of generating text: yes/no, choice (with probabilities and margin) and scale. Decision Services (`@Decide` methods returning `boolean`, an enum, `Choice<E>`, `Scale<E>` or a record) are the declarative layer.
- The provider is `langchain4j-typesafe` (beta). It works against TypeSafe's API, or any server implementing the System One API, including Ollama ≥0.35 with the `nimble`/`tev1` models (text only, 2,048-token prompt, 2–26 options).
- Quarkus support is `quarkus-langchain4j-typesafe` (https://docs.quarkiverse.io/quarkus-langchain4j/dev/typesafe-decision-model.html). It provides `@Inject [@ModelName] DecisionModel` and automatic registration of CDI `DecisionModelListener` beans. It doesn't document `@Decide` Decision Services, Dev Services or spans/`gen_ai.*` attributes.
- **Not released yet (as of 2026-10-02):** the extension was merged to quarkus-langchain4j `main` in quarkiverse/quarkus-langchain4j#2909. The latest release, 1.14.1, is on langchain4j 1.20.2 and doesn't include it. The `/dev/` docs page's `1.14.1` dependency snippet is misleading.
- **Why a spike and not part of #216:** the API is experimental, the extension is unreleased, and the only independent evaluation (https://github.com/klauswg/jev-guard, 100 synthetic samples) found 50% accuracy against a 68% rules-only baseline, with confidence that didn't predict correctness.
- **#216 already allows a cheap swap:** classification is one agent whose typed enum drives the `@ActivationCondition` routing. A non-AI agent calling a `DecisionModel` could write the same enum, so the routing wouldn't change. Extraction, summary, sentiment, the status answer and all replies stay with the LLM agents.

## Prerequisites

- #216 is implemented, so the intake's classifier and completeness check exist to compare against.
- A quarkus-langchain4j release that contains `quarkus-langchain4j-typesafe` (check Maven Central first).

## Questions to answer

1. **Accuracy:** on the same labelled emails, how do a decision model and the current LLM agents compare for:
   - classification (new claim / follow-up / not a claim)
   - category (single vehicle / multiple vehicle / theft / other)
   - per-item completeness (incident description, date, location, category stated?)
2. **Calibration:** does the decision model's confidence/margin predict correctness well enough to use as a "needs a human" signal?
3. **Latency and cost:** p50/p95 latency and cost per email for each option.
4. **Local option:** how do Ollama `nimble`/`tev1` compare with hosted Jev, and do real claim emails fit the 2,048-token prompt limit?
5. **Prompt injection:** how do both options handle emails that try to steer the decision?
6. **Integration:**
   - Does a non-AI agent that injects a `DecisionModel` work inside the #216 workflow, writing the enum to the agentic scope?
   - Do declarative `@Decide` Decision Services work in Quarkus?
   - Does Quarkus agentic support `DecisionRouterPlanner` (`@PlannerAgent`/`@PlannerSupplier`)?
7. **Observability:** can a `DecisionModelListener` produce spans with `gen_ai.*` attributes so the calls appear in Langfuse and keep the intake's one-trace-per-email, per-claim `gen_ai.conversation.id` grouping? How does Langfuse display them, and should the tier-1 judge score them?
8. **Testing:** WireMock stubs for `/v1/systemone`, and how a decision-model option fits the `%ollama` / `%ollama-openai` CI matrix.

## Approach

- Throwaway code on a local branch in a separate git worktree, never merged, using the latest stable versions available at the time.
- 50–100 labelled sample emails, covering every classification and category, incomplete claims and prompt-injection attempts. Store the dataset so the comparison can be repeated.
- Put both implementations behind the same interface (e.g. a classification strategy), and run them on the same dataset.
- Record every answer with evidence (test names, numbers, versions) in a results document, then summarise it here.

## Out of scope

- Production changes to the intake. Any adoption is a separate follow-up issue based on the spike's recommendation.
- Generated text (summary, sentiment, replies): decision models can't produce it.
- Legal sign-off. Any production use of TypeSafe's hosted API would first need a data-processing review (claim emails contain personal data; zero data retention is enterprise-only).

## Done when

- Every question above is answered with evidence.
- A recommendation is recorded: adopt (for which decisions, and with which backend), don't adopt, or revisit later.
- Throwaway code is not merged or pushed.

## Tasks

- [ ] Confirm a quarkus-langchain4j release includes `quarkus-langchain4j-typesafe`, and pin the latest stable versions
- [ ] Build the labelled email dataset (including incomplete and prompt-injection samples)
- [ ] Implement the decision-model classifier and completeness check behind the same interfaces as the #216 agents
- [ ] Run the comparison (accuracy, calibration, latency, cost) for hosted Jev and local Ollama models
- [ ] Check integration: non-AI agent inside the workflow, `@Decide` Decision Services, `DecisionRouterPlanner`
- [ ] Check observability: a `DecisionModelListener` span with `gen_ai.*` attributes in Langfuse
- [ ] Write up the results and the recommendation
