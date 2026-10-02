# Documentation accuracy review

Scope: prose docs + diagrams. EXCLUDED: conversation-export.md, .tasks/, AGENTS.md.

User decisions:
1. images/arch.png "Input Guardrails" box is stale/dead -> remove
2. docs/*.puml -> rewrite to current architecture
3. dead ai-interactions + milvus config -> remove

## Tasks — ALL COMPLETE
- [x] 01 remove dead config (application.yml ai-interactions, milvus; pom commented deps)
- [x] 02 verified architecture baseline -> FINDINGS.md (342 lines)
- [x] 03 rewrite docs/*.puml (rescoring-* deleted, continuous-scoring-* rewritten)
- [x] 04 replace images/arch.png with docs/application-flow.puml; images/ dir gone
- [x] 05 correct CLAUDE.md (6 corrections + 5 follow-up defect fixes)
- [x] 06 rewrite README.md (64 -> 194 lines)
- [x] 07 replace src/main/webui/README.md (111 -> 105 lines)
- [x] 08 final consistency pass (2 review rounds, all defects closed)

## Outcome
PNGs rendered with PlantUML 1.2026.0 jar from /tmp (no global install), all three
sources clean under -failfast2. Build compiles (./mvnw -q -o compile -DskipTests exit 0).

Review round 1 found 2 BLOCKER + 3 MINOR (Ollama profile claims, stale fields() value).
Review round 2 confirmed 5/6 fixed, found 2 new CLAUDE.md defects (unscoped
GEMINI_API_KEY claim + stale profile table row). Both fixed and verified.

KEY LESSON: %ollama and %ollama-openai are NOT equivalent. %ollama stubs 3 api-keys
and disables score-session + initialize-on-startup; %ollama-openai does NEITHER.
Neither redirects session-sentiment/judge away from Cohere. During ./mvnw verify the
pom forces quarkus.test.profile=<profile>,test so %test disables the initializer -
which is why CI passes with only a stubbed OPENAI_API_KEY.

NOT DONE (deliberate): PNG regeneration is manual - no plantuml on PATH.
Pre-existing issue left alone: committed Langfuse keys in %langfuse-ocp.
package.json metadata still points at rh-aiservices-bu/parasol-insurance (flagged, not changed).

## Confirmed defects (from orchestrator exploration)
- arch.png shows "Input Guardrails"; zero InputGuardrail impls in src/main/java
- docs/rescoring-*.puml model RESCORE mode (deleted); continuous-scoring-*.puml
  model separate Scoring Service + Interaction/Scoring DB (never existed now)
- application.yml ~221-231 quarkus.rest-client.ai-interactions -> localhost:8888,
  zero @RegisterRestClient in project
- milvus.dimension in base + %ollama + %ollama-openai; milvus dep commented out in pom
- CLAUDE.md: @DriftDetectionTest uses @EnabledIfConfig NOT @EnabledIfApplicationProperty,
  plus 3 more conditions (quarkus.profile=drift, OPENAI_API_KEY, COHERE_API_KEY)
- CLAUDE.md: claims condition classes in src/test/java/io/quarkus/test/junit/ - DOES NOT EXIST
- CLAUDE.md: SessionSentimentGuardrail + EvaluatorResultOutputGuardrail undocumented
- CLAUDE.md: omits evaluation rule "environment NONE_OF" filter (stops judge self-scoring)
- webui/README.md: PatternFly seed boilerplate; documents storybook scripts not in package.json
- README.md: no project overview/prereqs/quickstart
