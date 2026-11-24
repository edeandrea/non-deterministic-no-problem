## Decision: AI services record their own inputs and outputs on their span

I ran a spike replaying the recorded `langchain4j.aiservices.ClaimService.chat` dataset through the app, using the baseline comparison from quarkiverse/quarkus-langchain4j#3227. It worked, but it showed a gap that matters here too.

**A recorded dataset item can't be replayed as-is.** `LangfuseSessionScoringService` builds items from the *generation*, so an item's input is the model's whole prompt as one string: the system message, the rendered user message and the RAG excerpt. To replay it through `ClaimService.chat(ClaimBotQuery)`, I had to parse the claim id, inception date, claim summary and question back out of the rendered prompt with regexes. That works, but it breaks as soon as the prompt template changes. And if the system message or retrieved context were part of what's being tested, replaying with them baked into the input would be wrong.

**Decision:** every AI service records its own inputs (the method arguments) and output (the return value) on its `langchain4j.aiservices.*` span, unconditionally. This is no longer "only if task 02 keeps datasets for AI services". It applies to the chat's `ClaimService` as well as the intake's AI services, so a dataset item can carry the service call itself and be replayed by calling the service.

- **Where:** in `ai.scoring` for now, next to the other OTel processors. Task 01 found that quarkus-langchain4j's AI service events (`STARTED` with `InvocationContext.methodArguments()`, `COMPLETED` with `result()`) run with the AI service span current, so an observer can write both onto that span. It may belong upstream later, but it doesn't need to wait for that.
- **What it changes downstream:** task 06 builds dataset items from the AI service span's recorded input/output instead of the generation's. The rendered prompt stays available as metadata, as already planned for LLM-backed agents.
- **Agents:** an AI service that's also an agent is recorded once, by the agent listener (task 04), not twice.
