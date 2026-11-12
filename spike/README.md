# #221 decision-model spike

Throwaway code for [#221](https://github.com/edeandrea/non-deterministic-no-problem/issues/221). Never to be merged.
The findings are in [`RESULTS.md`](RESULTS.md).

| Path | What it is |
|---|---|
| `dataset/build_dataset.py` | Builds `dataset/emails.json`: 85 synthetic, hand-labeled emails, as `CorrespondenceText` builds the intake's input |
| `../src/test/java/org/parasol/spike/DecisionSpikeHarnessTests.java` | Runs the dataset through the LLM agents and the decision models; writes `results/<label>-<backend>.jsonl` |
| `analyze.py` | Scores the results: prints a Markdown summary, writes `results/summary.json` |
| `results/` | The raw results behind `RESULTS.md` |

## Rerun

Needs a local Ollama ≥ 0.35 with the models pulled (`ollama pull nimble tev1 clef`, plus the `claim-intake` models: `qwen3:4b`
for `-Pollama`, `granite4:micro` for `-Pollama-openai`). Run on the default test port (8081).

```bash
# Backends: llm (EmailClassifierAgent + IncidentDetailsAgent as is), llmdesc (classifier with EmailType's descriptions in the
# prompt), agent (the Quarkus @Decide agents, configured model), or any Ollama decision model name (nimble, tev1, clef, ...)
./mvnw -Pollama test -Dtest=DecisionSpikeHarnessTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dspike=true -Dspike.label=ollama -Dspike.backends=llm,llmdesc,nimble,tev1,clef,agent

# -Pollama-openai needs the API keys set to anything, as CI does
OPENAI_API_KEY=x COHERE_API_KEY=x GEMINI_API_KEY=x ./mvnw -Pollama-openai test -Dtest=DecisionSpikeHarnessTests \
  -Dsurefire.failIfNoSpecifiedTests=false -Dspike=true -Dspike.label=ollamaopenai -Dspike.backends=llm,nimble

python3 spike/analyze.py            # every label in results/
python3 spike/analyze.py ollama     # one label
```

Options: `-Dspike.only=<regex>` limits the emails by id; `-Dparasol.models.ollama.email-model-id=qwen3:30b-a3b` swaps the
`-Pollama` LLM.

### Hosted backends (not measured yet)

`DecisionRunner` builds a `TypeSafeDecisionModel` from `parasol.models.decision-base-url`. Hosted Jev speaks the same
System One protocol, so pointing it at `https://api.typesafe.ai` with model `jev-1.14.0` only needs the API key added to the
builder (`.apiKey(System.getenv("TYPESAFE_API_KEY"))`). The OpenAI Decisions API needs an `OpenAiDecisionModel` runner instead.
