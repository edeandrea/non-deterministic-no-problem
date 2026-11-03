# Task 04: Move the Ollama Block into `%ollama`

**Type:** Configuration Modification

## Goal

`quarkus.langchain4j.ollama.*` lives under `'%ollama'`, the three Ollama model ids live at root under
`parasol.models.ollama.*` where both Ollama profiles can read them, and the 21 warnings are gone from the default and
`-Pollama-openai` builds — with every model each profile actually uses unchanged.

## What to Do

### 1. Add the model ids at root

In the root `parasol:` map, extend the existing `models:` node (`src/main/resources/application.yml:307-314`), which
already carries the `provider` / `base-url` indirection and a comment explaining exactly this kind of move:

```yaml
parasol:
  models:
    provider: openai
    base-url: https://api.openai.com/v1
    # The Ollama model ids live here, not under quarkus.langchain4j.ollama.*, because %ollama-openai needs two of
    # them and that config root only exists when the Ollama extension is on the classpath (-Pollama). A key outside
    # the `quarkus.` namespace is also never reported as an unrecognized configuration key - see #290
    ollama:
      chat-model-id: granite4:micro
      # Not granite4:micro like the others: EmailEndsAppropriatelyOutputGuardrail needs EMAIL_ENDING reproduced
      # verbatim, and granite4:micro (like llama3.2, ministral-3:3b and qwen2.5:3b) reflows it - it collapses the
      # three disclaimer lines onto one - so the guardrail reprompts until max-retries. qwen3:4b reproduces it
      email-model-id: qwen3:4b
      embedding-model-id: snowflake-arctic-embed
```

Move the existing comments from `application.yml:171-173` and `:177-178` with the values they explain; don't leave them
orphaned at the old site or duplicate them.

### 2. Move the block into `%ollama`

Move the whole `quarkus.langchain4j.ollama:` node (`application.yml:161-204`, all 21 leaf keys: `parasol-chat`,
`generate-email`, `politeness`, `claim-intake`, `session-sentiment`, `judge`, `embedding-model`) under the existing
`'%ollama':` → `quarkus:` → `langchain4j:` map (`:317-349`), and interpolate each `model-id`:

- `parasol-chat`, `politeness`, `session-sentiment`, `judge` → `${parasol.models.ollama.chat-model-id}`
- `generate-email`, `claim-intake` → `${parasol.models.ollama.email-model-id}`
- `embedding-model` → `${parasol.models.ollama.embedding-model-id}`

Everything else about the block is unchanged: the `timeout: 600s` values, the per-model `temperature`s
(`0.3` for the four chat models, `0` for `session-sentiment` and `judge`), and `model-options.think: false` on
`generate-email` and `claim-intake`.

### 3. Repoint `%ollama-openai`

Five lines, `application.yml:362, 366, 370, 374, 376`. Replace
`"${quarkus.langchain4j.ollama.parasol-chat.chat-model.model-id}"` with
`"${parasol.models.ollama.chat-model-id}"` (four chat models) and
`"${quarkus.langchain4j.ollama.embedding-model.model-id}"` with `"${parasol.models.ollama.embedding-model-id}"`
(the embedding model). Keep the comment that explains why all four chat models interpolate the **chat** id rather than
their own — update it to name the new key.

## Files/Areas

- `src/main/resources/application.yml` (root `parasol.models`, the `%ollama` block, the `%ollama-openai` block)

## Key Points

- **This is why the 21 keys warn:** the block is unprofiled, so it is live on every build, while
  `quarkus-langchain4j-ollama` is only a dependency under the `ollama` Maven profile (`pom.xml:410`). Under `-Pollama`
  the extension is present and these never warned; under the default profile and `-Pollama-openai` all 21 do.
- **A straight move would break `%ollama-openai`**, which is the whole reason for step 1. `%ollama-openai` does *not*
  bring the Ollama extension in, so it cannot read `quarkus.langchain4j.ollama.*` once that node is profile-scoped.
- **`model-options.think: false` stays Ollama-native-only.** There is no equivalent on the OpenAI-compatible endpoint,
  which is why `%ollama-openai` points all four chat models at the *chat* id (`granite4:micro`) rather than at
  `generate-email`'s `qwen3:4b` — a thinking qwen3 spends ~6k tokens per email. Both facts are documented in
  `CLAUDE.md` → *Models* and must survive this change.
- **No interaction with `%test`.** `%test` wins over `%ollama` (multiple profiles resolve right-to-left —
  `CLAUDE.md` → *Testing*, C1), but it sets no Ollama keys and pins every provider to `openai` anyway. The Ollama
  models aren't reached in tests either way.
- **A broken interpolation is a boot failure, not a test failure.** Smoke both dev modes before the full build (see
  Done When) — it is much faster feedback than a `verify`.
- **Don't touch `%ollama`'s existing contents** beyond adding the moved node: the `api-key: changeme` stubs, the five
  `provider: ollama` pins, `parasol.models.provider: ollama` and the `aiscoring` node (which task 05 moves) all stay
  as they are.
- Keep the `# Ollama` section comment meaningful at whichever site it ends up.

## Done When

- [ ] `grep -n "quarkus.langchain4j.ollama\|^    ollama:" src/main/resources/application.yml` shows the block only
      inside `'%ollama'`, and no `${quarkus.langchain4j.ollama...}` interpolation remains anywhere.
- [ ] `./mvnw -Pollama quarkus:dev` boots, logs no `Unrecognized configuration key "quarkus.langchain4j.ollama…"`, and
      the Dev UI / boot log shows the expected model ids (`granite4:micro` for chat, `qwen3:4b` for `generate-email`
      and `claim-intake`, `snowflake-arctic-embed` for embeddings).
- [ ] `./mvnw -Pollama-openai quarkus:dev` boots with all five `model-name` interpolations resolved (no literal
      `${…}` in the log, no startup config error).
- [ ] `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai` are both green.
- [ ] A default-profile run (`./mvnw -B clean verify`, or at minimum `./mvnw -B clean test-compile` plus one boot)
      reports no `quarkus.langchain4j.ollama.*` key — this is the leg where the 21 were visible.
- [ ] The unrecognized-key list is down to the 11 `aiscoring` keys on every profile.
