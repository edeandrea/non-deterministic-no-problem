# App Architecture

![architecture](images/arch.png)

# Langfuse integration

Langfuse's own `LANGFUSE_INIT_*` environment variables cover only basic bootstrapping (org, project,
user, API keys). They do **not** cover evaluators, so at one point these had to be created by hand in
the Langfuse UI (see https://github.com/orgs/langfuse/discussions/8241).

That is no longer the case here. `ai.scoring.langfuse.init.LangfuseEvaluationInitializer` provisions
everything on application startup through the `LangfuseOperations` layer of the
[quarkus-langfuse](https://github.com/quarkiverse/quarkus-langfuse) extension, gated on
`quarkus.aiscoring.langfuse.evaluation.initialize-on-startup` (default `true`). On a `StartupEvent`
it creates-or-reuses:

- a score config for session sentiment
- a Cohere model definition
- a Google AI Studio (Gemini) LLM connection, using `GEMINI_API_KEY`
- a score config for continuous evaluation
- the `Continuous Evaluation Evaluator` LLM-as-a-Judge evaluator
- an evaluation rule binding that evaluator to incoming traces (100% sampling, excluding `SPAN` and
  `EVENT` observation types)

Each step is idempotent — existing entities are looked up by name and reused. That is now the
extension's job: `createIfAbsent` (and `upsert` for the LLM connection) replaced the hand-written
lookup-then-create code this app used to carry. Failures are logged as warnings rather than aborting
startup.

For the evaluation gaps that genuinely *can't* be solved through Langfuse today (session-level
scoring, experiment orchestration from Java) and the workarounds implemented in this project, see
[langfuse-evaluation.md](langfuse-evaluation.md).

# Using Ollama
If you would like to use [Ollama](https://ollama.com/) instead, first install/run Ollama on your machine. Then do one of the following:

## Building the app
When building the app, run `./mvnw clean package -DskipTests -Pollama` (or `quarkus build --clean --no-tests -Dollama`)

## Running dev mode
When running dev mode, run `./mvnw quarkus:dev -Pollama` (or `quarkus dev -Dollama`).

## Running tests
When running tests, run `./mvnw verify -Pollama` (or `quarkus build --tests -Dollama`)

## Running the app outside dev mode
If you want to run the app outside dev mode, first build the app as described above, then run `java -Dquarkus.profile=ollama,prod -jar target/quarkus-app/quarkus-run.jar`

# Using Ollama via the OpenAI endpoint
If you would like to use [Ollama](https://ollama.com/) instead but using the OpenAI endpoint, first install/run Ollama on your machine. Then do one of the following:

## Building the app
When building the app, run `./mvnw clean package -DskipTests -Pollama-openai` (or `quarkus build --clean --no-tests -Dollama-openai`)

## Running dev mode
When running dev mode, run `./mvnw quarkus:dev -Pollama-openai` (or `quarkus dev -Dollama-openai`).

## Running tests
When running tests, run `./mvnw verify -Pollama-openai` (or `quarkus build --tests -Dollama-openai`)

## Running the app outside dev mode
If you want to run the app outside dev mode, first build the app as described above, then run `java -Dquarkus.profile=ollama-openai,prod -jar target/quarkus-app/quarkus-run.jar`
