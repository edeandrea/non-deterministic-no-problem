# App Architecture

![architecture](images/arch.png)

# Langfuse integration

Things that can't yet be done programmatically:
- Configuring evaluators (see https://github.com/orgs/langfuse/discussions/8241)

What you CAN auto-configure via LANGFUSE_INIT_* env vars:          
  - Online Evaluators (LLM-as-a-Judge) — must be set up manually in the LangFuse UI                           
Workaround if you want fully automated scoring:
- Build an external evaluation pipeline — compute scores in your code and push them to LangFuse via POST `/api/public/scores`. This is what ai-scorer essentially does today, just targeting LangFuse's API instead of its own PostgreSQL. 

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