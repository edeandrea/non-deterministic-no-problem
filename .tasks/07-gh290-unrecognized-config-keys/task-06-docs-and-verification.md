# Task 06: Documentation, the Re-rendered Diagram, Verification and the PR

**Type:** Documentation and Verification

## Goal

Every document that named an affected key is correct, `CLAUDE.md` records the *invariant* rather than a list of
expected warnings, the sequence diagram is re-rendered, both CI legs are green with zero unrecognized-key warnings,
and the PR is ready.

## What to Do

### 1. `CLAUDE.md` — the new invariant

Add, near the *Configuration profiles* section, a short statement of what is now true and the three structural rules
that keep it true:

- A clean boot logs **no** `Unrecognized configuration key` warnings. One appearing means a typo or a missing
  extension — treat it as a real finding, not noise. There is no suppression switch; `quarkus-core` declares only
  `quarkus.config.fixed-at-build-time` and `quarkus.config.sources.system-only`, and muting `io.quarkus.config` would
  hide the next real typo.
- **Application config lives outside the `quarkus.` namespace** — `parasol.*` and `aiscoring.*`. Quarkus builds its
  known-key model for `quarkus.` from extension metadata (`META-INF/quarkus-config-doc/quarkus-config-model.json` in
  each `*-deployment` jar), so an application `@ConfigMapping` under `quarkus.` is always "unrecognized", however well
  it works. Don't put a new app config root there.
- **A key belonging to a profile-scoped extension lives in that profile's block.** `quarkus.langchain4j.ollama.*` sits
  under `%ollama` because `quarkus-langchain4j-ollama` is only a dependency under the `ollama` Maven profile; at root
  level it warned 21 times on every other build. `%ollama-openai` reads the two model ids it needs through
  `parasol.models.ollama.*`, not the extension's own root.

### 2. `CLAUDE.md` — the existing references

- The `quarkus.aiscoring.*` mentions: `:429`, `:430`, `:438`, `:479`, `:650`, `:651`, `:840`, `:1178`, `:1224`,
  `:1284`. (Re-grep — earlier tasks will have shifted these.)
- The *Models* section: the `%ollama` / `%ollama-openai` rows and the surrounding prose now describe model ids coming
  from `parasol.models.ollama.*`, and the note that `%ollama-openai` interpolates from `parasol-chat`'s id needs to
  name the new key. Keep the two load-bearing facts intact: `model-options.think: false` is Ollama-native-only, and
  the OpenAI-compatible leg deliberately uses the chat id for all four chat models.
- The *CI* section: it currently says the Dev Service flag "is the **only** CI-specific config". Task 01 made it
  `ollama`-leg-only, so adjust the sentence to stay true.
- The *Testing* section: mention the new `ClaimReviewResourceTests` raw-key tests alongside the existing
  `jsonFieldNames` / `metadataJsonFieldNames` list, and record that the Jackson reflection-free serializers are on —
  including that they register application-wide via `ObjectMapperCustomizer` but are only generated from JAX-RS return
  and POST/PUT/PATCH parameter types, and that `ProblemDetail` is therefore **not** covered.

### 3. The other documents

- `README.md:82-84` and `:279` — the four `quarkus.aiscoring.*` lines.
- `langfuse-evaluation.md:58` — the prefix. The table at `:60-65` lists bare relative names, so it needs no change.
- `.tasks/github-issues/issue-10-unrecognized-config-keys.md` — add a superseded-by note at the top with the date and
  the decisions, following the pattern used on `issue-06-grafana-dashboard.md`. Leave the body intact below it.
- `.tasks/06-gh218-grafana-ai-dashboard/PLAN.md` → *Caveats & Problems* hands the micrometer keys to this issue. A
  one-line "done in #290" annotation there closes the loop; optional, but cheap.

### 4. Re-render the diagram

```bash
./docs/render-diagrams.sh
```

Needs a pinned PlantUML and graphviz `dot`. Commit the regenerated `docs/continuous-scoring-sequence.png`. Check the
other PNGs did not change; if they did, that is a renderer-version difference worth mentioning rather than silently
committing.

### 5. Verification

```bash
./mvnw -B clean verify -Pollama -Dquarkus.langchain4j.ollama.devservices.enabled=false
./mvnw -B clean verify -Pollama-openai
```

Then, after each:

```bash
grep -rhoE 'Unrecognized configuration key "[^"]+"' target/surefire-reports target/failsafe-reports \
  | sed -E 's/.*key "([^"]+)".*/\1/' | sort -u
```

**Expect no output.** That is the issue's done-when, and the whole point of the exercise. Record the before/after
counts and the test totals in `PLAN.md` → Outcomes, the way #218's plan does.

Also worth one default-profile run (`./mvnw -B clean verify`) if credentials allow, since that is the leg where all 35
were visible — but it needs a real `OPENAI_API_KEY`, so it may be a manual check for the user rather than part of this
task.

### 6. The PR

Per the branch/PR decision recorded in `PLAN.md`. Title carries **no** closing keyword; `Closes #290` goes in the body
only. End the commit messages with the `Assisted-By` line. Draft the PR body and any GitHub comments and **wait for
the user's review of the exact text** before posting anything.

## Files/Areas

- `CLAUDE.md`, `README.md`, `langfuse-evaluation.md`
- `docs/continuous-scoring-sequence.puml` (already fixed in task 05) and its regenerated `.png`
- `.tasks/github-issues/issue-10-unrecognized-config-keys.md`
- `.tasks/06-gh218-grafana-ai-dashboard/PLAN.md` (one-line annotation, optional)
- `.tasks/07-gh290-unrecognized-config-keys/PLAN.md` (Outcomes)

## Key Points

- **Record the invariant, not an allowlist.** The issue originally asked "is it worth silencing the profile-specific
  ones, or is the warning list simply something to read with knowledge of which entries are expected?" — the answer
  this work gives is neither: there are no expected entries left, which is strictly more useful than a documented
  list, because it makes the *next* typo visible for free.
- **Don't claim the Jackson optimization covers the whole API.** `ProblemDetail` is returned as a `Response` entity
  with no static type, so no serializer is generated for it and `application/problem+json` stays on reflective
  Jackson.
- US English throughout, tabs where the file uses tabs, max line length 180 (`.editorconfig`;
  `insert_final_newline = false`).
- No issue-closing keyword in any commit **first line** or in the PR **title** — global rule.

## Done When

- [ ] `CLAUDE.md` states the zero-warnings invariant and the three rules behind it.
- [ ] Every `quarkus.aiscoring.*` reference in `CLAUDE.md`, `README.md` and `langfuse-evaluation.md` is updated.
- [ ] `CLAUDE.md`'s *Models*, *CI* and *Testing* sections reflect tasks 01, 03 and 04.
- [ ] `issue-10-unrecognized-config-keys.md` carries a superseded note.
- [ ] `docs/continuous-scoring-sequence.png` is re-rendered and committed; no unrelated PNG changed.
- [ ] Both CI legs are green and the unrecognized-key grep returns **zero** hits on each.
- [ ] Before/after counts and test totals are recorded in `PLAN.md` → Outcomes.
- [ ] A repo-wide grep for `quarkus.aiscoring`, `enable-reflection-free-serialization`, `micrometer.export.otlp` and
      `quarkus.dev-ui.cors` finds hits only in deliberately-preserved historical records.
- [ ] PR body and any GitHub comments drafted and shown to the user; nothing posted without approval.
