# Consolidate the test profiles to cut application restarts

> **Handoff document.** Self-contained: everything an implementing agent needs, including the research behind
> each decision, so none of it has to be rediscovered. Repo:
> `/Users/edeandre/workspaces/demos/non-deterministic-no-problem` (Quarkus 3.40.1.1, Java 25).
> Follow `CLAUDE.md`: tabs, tab width 2, max line 180, US English.

## Context

A full `./mvnw verify` boots the Quarkus application **8 times**. Each boot re-augments the app, and that
dominates the suite's wall-clock time.

### The restart rule

`io.quarkus.test.junit.classloading.FacadeClassLoader.getQuarkusClassLoader` keys one classloader — and
therefore one application — on `profileKey + resourceKey`:

```java
private static String getProfileKey(Class<?> profile) {
    final String profileName = profile != null ? profile.getName() : NO_PROFILE;
    return KEY_PREFIX + profileName;
}
```

- `profileKey` is **just the `@TestProfile` class name**. Contents are irrelevant: two profile classes
  producing byte-identical config are still two boots; merging them into one class is exactly one boot.
- `resourceKey` comes from `TestResourceUtil.getReloadGroupIdentifier` over the test-resource set.

**Nothing else triggers a restart.** `AbstractJvmQuarkusTestExtension.isNewApplication` is only a classloader
identity check. `TestResourceManager.testResourcesRequireReload` is dead code on the `@QuarkusTest` path.
`QuarkusTestProfileAwareClassOrderer` groups by classloader — it minimizes boots but cannot reduce their
number. `@TestSecurity`, `@InjectMock` and `@Nested` do not cause restarts.

Sources: `~/.m2/repository/io/quarkus/quarkus-junit/3.40.1.1/quarkus-junit-3.40.1.1-sources.jar` and
`quarkus-test-common-3.40.1.1-sources.jar`. (`quarkus-junit5` is a relocation POM; the real artifact is
`quarkus-junit`.)

### Two independent levers

**1. `@WithPlaywright` poisons its own reload key.** It declares `String[] args() default {"--disable-gpu"}`.
`TestResourceManager.prepareTestResourceComparisonInfo` reflects every annotation attribute into a
`Map<String, Object>`, and `getReloadGroupIdentifier` joins the entries with `Object::toString`. The JDK
annotation proxy **clones array member values on every read**, guarded by `Array.getLength(result) != 0`, so
each read yields `[Ljava.lang.String;@<new identityHashCode>` and every test class gets a different key. That
is why the five Playwright classes each boot despite sharing `UiTestProfile`. An
**explicitly empty** array is not cloned. `args()` is the only non-empty array attribute (`selectors()`
defaults to `{}`, `browserContext()` is an annotation, `playwrightAdapter()` is a `Class`), and the scope is
`GLOBAL`, so no UUID is appended — array identity is the sole differentiator.

**Upstream: two issues, and only the second actually fixes it.**
[#56443](https://github.com/quarkusio/quarkus/issues/56443) introduced
`TestResourceManager$TestResourceComparisonInfoArrayValue` in 4.0.0.Beta1, overriding `equals`/`hashCode` with
deep comparison — but **not `toString()`**, which is what `getReloadGroupIdentifier` actually joins on, so the
per-class keys survived. [#57464](https://github.com/quarkusio/quarkus/issues/57464) fixed that
([PR #57465](https://github.com/quarkusio/quarkus/pull/57465), merged 2026-10-15, milestone **4.0.0.Beta2**,
released 2026-10-28).

**Neither is in 3.40.1.1.** Verified: its `quarkus-test-common` jar contains only
`TestResourceManager$TestResourceComparisonInfo`, no `…ArrayValue`. 3.40.1.1 shipped 2026-10-19, *after* the
fix merged, without picking it up. PR #57465 carries the `triage/backport-3.40` label, so a 3.40.x backport is
queued but **unreleased as of 2026-11-03**. That is what makes `args = {}` necessary today — and explicitly
temporary.

**2. Four profile classes** whose config differences are mostly reconcilable.

Target: **8 boots → 3** (plus the gated-off drift profile).

| Change | Boots |
|---|---|
| Today | 8 |
| `@WithPlaywright(args = {})` collapses 5 UI boots into 1 | 4 |
| `StubbedModelsTestProfile`'s config moves into `%test`, merging with the 22 bare-profile classes | **3** |

### Current layout

| Profile | Classes |
|---|---|
| `org.parasol.testing.StubbedModelsTestProfile` (`@ConnectWireMock`, `testResources()` = `ToxiproxyTestResource`) | 32 |
| `org.parasol.testing.LangfuseExportTestProfile extends StubbedModelsTestProfile` | 2 |
| `org.parasol.ui.UiTestProfile` (`@ConnectWireMock`) | 5 Playwright classes |
| `ai.scoring.it.DriftDetectionTest.DriftTestProfile` (`getConfigProfile()` → `"drift"`) | 1, gated off in CI |
| none (bare `%test`) | 22 |

## Decisions already taken (do not relitigate)

- Shared config lives in the `%test` block of `src/main/resources/application.yml`, so the big group carries
  **no `@TestProfile` at all**. `StubbedModelsTestProfile` disappears.
- `UiTestProfile` **stays** its own boot: real `parasol-chat` and real boot-time Easy RAG ingestion
  (`ClaimsDetailPageTests.pageLoads` talks to the live chat bot).
- `LangfuseExportTestProfile` **stays** its own boot.
- `generate-email` and `politeness` **stay on real models**, so `NotificationServiceTests.emailSendsWhenUserExists`
  keeps live-model coverage with no extra boot.
- **`quarkus.langchain4j.openai.api-key` keeps `${OPENAI_API_KEY}` with no default.** A missing key fails the
  boot fast, as today. **Do not add a `:changeme` fallback** — it was considered and rejected. Stub the
  *per-model* keys instead.

### No live coverage is lost

Verified across all 22 bare-profile classes: only `NotificationServiceTests.emailSendsWhenUserExists` makes a
live model call. Every other class either `@InjectMock`s the AI service (`ClaimWebsocketChatBotTests` mocks
`ClaimService`; the four email guardrail tests mock theirs; `IntakeCompleteToolInputGuardrailTests` calls the
guardrail directly) or never references an AI type. Both live tests are preserved:

| What | Boot | Why it survives |
|---|---|---|
| `emailSendsWhenUserExists` — real `generate-email` + `politeness` through all four output guardrails | default | the global `api-key` is left alone; only per-model keys are stubbed |
| `ClaimsDetailPageTests.pageLoads` — real `parasol-chat` + boot-time Easy RAG ingestion | UI | the live-chat boot is kept |

**What `emailSendsWhenUserExists` actually proves:** its `startsWith`/`endsWith`/`contains` assertions all
restate what the guardrails already enforce (`EmailEndsAppropriatelyOutputGuardrail.validate` does
`email.body().endsWith(this.expectedEnding)` or reprompts). The real signal is that **the call returns at
all** — that the guardrail loop converges against a live, non-deterministic model within the retry budget. It
fails as a guardrail max-retries **exception**, not an assertion diff. That is how a silently-stubbed model
would show up.

## Two corrections to the obvious design — both verified against sources

### C1. Multi-profile precedence runs the *opposite* way to intuition

`%test` **beats** `%ollama` and `%ollama-openai`, not the other way round.
`io.smallrye.config.ProfileConfigSourceInterceptor` reverses the profile list and takes the first hit:

```java
public ProfileConfigSourceInterceptor(final List<String> profiles) {
    List<String> reverseProfiles = new ArrayList<>(profiles);
    Collections.reverse(reverseProfiles);          // <-- reversed
    ...
}
```

Quarkus config-reference §6.6: *"Multiple profiles priority work in reverse order."* The pom sets
`quarkus.test.profile=ollama,test` and `=ollama-openai,test`, so **`test` is the last listed and therefore
wins**.

**Consequence: the indirection must be inverted from the intuitive design.** The *stub* is the constant and
the *real* value is what varies per Maven profile, so the indirection key must hold the **real** value,
`%test` writes stub literals, and `UiTestProfile` reads the indirection to get back to "real, whatever that
means on this leg". Hard-coding `openai` / `https://api.openai.com/v1` in `UiTestProfile` would break both CI
legs (the UI boot has Easy RAG ingestion ON, so it would 401 against real OpenAI with CI's `change-me` key).

### C2. Named OpenAI clients do **not** inherit the unnamed client's config

Each named config group carries its own `@WithDefault("https://api.openai.com/v1/")`
(`LangChain4jOpenAiConfig.OpenAiConfig.baseUrl`), and `OpenAiRecorder` resolves `defaultConfig()` vs
`namedConfig().get(name)` with no fallback. `application.yml` proves it by repeating the keys — `generate-email`
repeats `api-key` (line 129), and `%ollama-openai` repeats `base-url` for all four chat models (lines 344‑360).

So stubbing `quarkus.langchain4j.openai.base-url` **does not touch `generate-email` or `politeness`**. The
actual coupling that forces `NotificationServiceTests` onto its own boot today is the **explicit expression**
`api-key: "${quarkus.langchain4j.openai.api-key}"` at line 129: `StubbedModelsTestProfile` stubs the global
key to `changeme`, the expression propagates it, and the live call 401s.

**Therefore: leave the global `api-key` alone and stub per-model keys.** Do *not* give `generate-email` or
`politeness` explicit `base-url` entries in `%test` — that would beat `%ollama-openai` lines 350/354 and
silently move the demo leg onto real OpenAI.

The `embedding-model` segment **is** part of the unnamed client (`quarkus.langchain4j.openai.embedding-model.*`
configures the default embedding model, like `quarkus.langchain4j.ollama.embedding-model.model-id`), so it does
use the global `base-url` and must be stubbed there.

## Plan

### 1. `src/test/java/org/parasol/ui/PlaywrightTests.java` (line 19) — do this first, alone, and measure

```java
// args MUST stay empty. Quarkus 3.40 builds a test class's reload key from the reflected values of a
// test-resource annotation's attributes (TestResourceManager.prepareTestResourceComparisonInfo), and the JDK
// annotation proxy clones a non-empty array on every read, so the default {"--disable-gpu"} gives each
// subclass a different key and its own application boot. An empty array is not cloned
// (AnnotationInvocationHandler guards on Array.getLength(result) != 0), so all five share one boot.
// Fixed upstream by quarkusio/quarkus#57464 (PR #57465, 4.0.0.Beta2); the earlier #56443 fix was incomplete
// because it did not override toString(). Queued for a 3.40 backport - restore the flag once we are on a
// Quarkus that has it.
@WithPlaywright(args = {}, recordVideoDir = RECORD_DIR, slowMo = 500)
```

Independent of everything else. Measure before touching any profile so the two effects stay distinguishable.

### 2. `src/main/resources/application.yml`

**2a — root indirection, under the existing `parasol:` block (~line 297).** This is what `UiTestProfile` reads
to get back to real models; it must be owned by the Maven profiles, per C1.

```yaml
parasol:
  # What "the real model" means under the active config profile. %test stubs the models outright, and
  # UiTestProfile has to point its own back at the real ones - but it cannot hard-code openai /
  # https://api.openai.com/v1, which are wrong under -Pollama and -Pollama-openai. %test WINS over %ollama and
  # %ollama-openai (multiple profiles resolve right-to-left: ProfileConfigSourceInterceptor reverses the list),
  # so the un-stubbing has to come from a key the Maven profiles own.
  models:
    provider: openai
    base-url: https://api.openai.com/v1
```

**2b — `%ollama` (~line 309):** add `parasol.models.provider: ollama`.

**2c — `%ollama-openai` (~line 340):** add `parasol.models.base-url: http://localhost:11434/v1`, and point its
existing `openai.base-url` / per-model `base-url` entries at `${parasol.models.base-url}` (this also retires the
`${%ollama-openai.quarkus.langchain4j.openai.base-url}` self-references at lines 346/350/354/358).

**2d — `%test` (~line 401):** absorb everything `StubbedModelsTestProfile.getConfigOverrides()` does.

- `quarkus.hibernate-orm.statistics: true`
- `quarkus.otel.bsp.schedule.delay: 100ms`
- providers pinned to `openai` for `parasol-chat.chat-model`, `claim-intake.chat-model`, `embedding-model`.
  **Load-bearing, not redundant:** `%ollama` would otherwise route them around the stubs into a real model.
- `quarkus.langchain4j.easy-rag.ingestion-strategy: OFF`
- `quarkus.langchain4j.openai.base-url: "${parasol.test.wiremock-base-url}"` — the unnamed client, which is also
  the default embedding model
- `parasol-chat` and `claim-intake`: `api-key: changeme` **and** `base-url: "${parasol.test.wiremock-base-url}"`
  (named clients do not inherit, per C2); `claim-intake.chat-model.model-name: claim-intake-model`
- `session-sentiment.api-key: changeme`, `judge.api-key: changeme`
- `parasol.test.wiremock-base-url: "http://localhost:${quarkus.wiremock.devservices.port}/v1"`, kept **inside**
  `%test` so it is never an active key in the production jar

**Explicitly NOT in `%test`:** `quarkus.langchain4j.openai.api-key` (stays `${OPENAI_API_KEY}`, per the
decision above) and any key for `generate-email` or `politeness`.

Comment the block with the C1 precedence warning — it is counter-intuitive and now load-bearing.

### 3. New `src/test/java/org/parasol/testing/GlobalTestResources.java`

```java
@QuarkusTestResource(WireMockServerConnector.class)
@QuarkusTestResource(ToxiproxyTestResource.class)
public final class GlobalTestResources {
	private GlobalTestResources() {}
}
```

`restrictToAnnotatedClass` defaults to `false` → `TestResourceScope.GLOBAL` → Quarkus applies both to *every*
test class in the module, so every class's reload key is identical and the boot count equals the number of
distinct `@TestProfile` classes.

**`@ConnectWireMock` cannot be used here.** It is a meta-annotation, and `getUniqueTestResourceClassEntries`
finds those **only** via `collectMetaAnnotations` (the test class, its superclasses, its enclosing classes, and
the profile class). The Jandex path explicitly skips annotation types:

```java
if (targetClass.isAnnotation()) {
    // meta-annotations have already been handled in collectMetaAnnotations
    return false;
}
```

With no profile class for the big group there is nowhere to hang it, so register `WireMockServerConnector`
directly. Filed upstream as [quarkusio/quarkus#58084](https://github.com/quarkusio/quarkus/issues/58084) — if that is fixed, this reverts to
`@ConnectWireMock` on the holder. That is safe: `init(ConnectWireMock)` only runs when `entry.getConfigAnnotation() != null`, and the
`restClient` field already defaults to `""`, so `start()` returns `emptyMap()`. No REST client is redirected in
this project.

**Global resources do not restart per boot.** `testResourcesRequireReload` returns `false` when neither set is
`RESTRICTED_TO_CLASS` and the sets are equal, so the `TestResourceManager` is kept across all three restarts —
**one Toxiproxy container for the whole run**, better than today. Playwright is unaffected (meta-annotation,
only the 5 UI classes).

### 4. Delete `src/test/java/org/parasol/testing/StubbedModelsTestProfile.java`

### 5. Move `MODEL_NAME` to `src/test/java/org/parasol/intake/ChatStubs.java`

Its only consumers are `ChatStubs` and `ClaimsMailboxAgentTests`. Add a comment that it must match
`quarkus.langchain4j.openai.claim-intake.chat-model.model-name` in `%test`. `claimIntakeOnWireMock()` has no
callers left — delete it.

### 6. The 32 classes: delete `@TestProfile(StubbedModelsTestProfile.class)` and both imports

`grep -rl "StubbedModelsTestProfile" src/test` gives the set, including `IntakeFlowTestBase`. Remove the
now-unused `io.quarkus.test.junit.TestProfile` import from each.

### 7. `src/test/java/org/parasol/ui/UiTestProfile.java`

Remove `@ConnectWireMock` and the `StubbedModelsTestProfile` import. Keep `QuinoaTestProfiles.EnableAndRunTests`
and the Roundcube overrides; add:

| key | value |
|---|---|
| `quarkus.langchain4j.parasol-chat.chat-model.provider` | `${parasol.models.provider}` |
| `quarkus.langchain4j.embedding-model.provider` | `${parasol.models.provider}` |
| `quarkus.langchain4j.openai.base-url` | `${parasol.models.base-url}` |
| `quarkus.langchain4j.openai.parasol-chat.base-url` | `${parasol.models.base-url}` |
| `quarkus.langchain4j.openai.parasol-chat.api-key` | `${quarkus.langchain4j.openai.api-key}` |
| `quarkus.langchain4j.easy-rag.ingestion-strategy` | `ON` |

`ON` is the real default (`EasyRagConfig`: `enum IngestionStrategy { ON, OFF, MANUAL }`, `@WithDefault("ON")`)
— not `AUTO` or `ENABLED`. `claim-intake` needs nothing: `%test` already pins it exactly as
`claimIntakeOnWireMock()` did. Profile overrides outrank `application.yml`.

### 8. `src/test/java/org/parasol/testing/LangfuseExportTestProfile.java`

`extends StubbedModelsTestProfile` → `implements QuarkusTestProfile`; drop the `Stream.concat(super…)` and
return its own entries directly. It needs nothing from the old superclass — the resources are global now and
the config base comes from `%test`. Keep `WIREMOCK_URL` for its `session-sentiment`/`judge` base-urls. Check
whether `initialize-on-startup: true` is actually needed by `LangfuseSessionScoringServiceTests`; if not, leave
it off.

### 9. Javadoc on files describing the old arrangement

`ToxiproxyTestResource` (lines 15‑16, "registered by `StubbedModelsTestProfile`…" → global, started once for the
whole run) and `IntakeFlowTestBase` (lines 28‑29).

### 10. `CLAUDE.md`

- Testing: "four profiles … eight boots in a full `verify`" → three boots; the `StubbedModelsTestProfile`
  bullet becomes "the `%test` block of `application.yml`".
- "`@ConnectWireMock` sits on the profile classes, never on a test class" → `GlobalTestResources`, **and why**
  `@ConnectWireMock` can't go there (meta-annotations are only read off the class hierarchy / profile).
- "The Playwright classes still boot once each" → fixed by `args = {}`; link #57464/#57465 (not #56443 alone,
  which was incomplete) and note the pending 3.40 backport.
- "A WireMock-backed profile must also pin the provider" → the pin now lives in `%test`, reversed by
  `UiTestProfile` via `${parasol.models.provider}`. **Add the C1 precedence rule as a first-class gotcha.**
- **Add the C2 non-inheritance rule** to the Models section: named OpenAI clients do not inherit the unnamed
  client's `base-url`/`api-key`; that is why every named block repeats them, and why stubbing the unnamed
  `base-url` leaves `generate-email` and `politeness` live.
- "Easy RAG in WireMock-backed profiles", "Easy RAG with stub keys", the Toxiproxy sentence in "Inbox watcher
  tests", the "they share `StubbedModelsTestProfile`" sentences, and the "no test may `@InjectMock` an intake
  agent" warning — which now binds the whole default group (~54 classes), not 32.
- Configuration-profiles table, `test` row. Note `DriftTestProfile` replaces the profile entirely, so `%test`
  is not active there and none of this applies to it.

### 11. `README.md`

Only if the optional provider de-duplication (below) is taken: the two bullets at lines 360‑366.

### Optional, separate commit: de-duplicate the provider selection

Add `provider: "${parasol.models.provider}"` to the root `quarkus.langchain4j` chat-model blocks and delete the
five `provider: ollama` entries from `%ollama`. Safe — only the OpenAI extension is on the classpath unless
`-Pollama` adds `quarkus-langchain4j-ollama`.

## Verify

Each boot logs one `… started in …` line. There are no `*IT.java` classes, so `verify` ≡ `test` for counting.

```bash
./mvnw -B clean verify 2>&1 | tee /tmp/boots-before.log   # baseline on main
grep -c "powered by Quarkus" /tmp/boots-before.log        # expect 8

./mvnw -B clean verify 2>&1 | tee /tmp/boots-after.log    # after
grep -c "powered by Quarkus" /tmp/boots-after.log         # expect 3
grep -n  "powered by Quarkus" /tmp/boots-after.log        # confirm where the 2 restarts land
```

1. **Playwright step alone:** `./mvnw -B test -Dtest='org.parasol.ui.*Tests' 2>&1 | grep -c "powered by Quarkus"`
   → `1` (was `5`).
2. **Toxiproxy starts once:** `grep -c "ghcr.io/shopify/toxiproxy" /tmp/boots-after.log` must not grow with the
   boot count.
3. **The precedence reasoning — the whole risk.** Run both CI legs against a local Ollama on 11434.
   `log-requests: true` is already on, so grep the UI boot's region for `localhost:11434` on the embeddings and
   `parasol-chat` calls (proves the indirection reached `UiTestProfile`) and the default-group region for the
   WireMock port.
4. `emailSendsWhenUserExists` still makes a **live** call and passes (default profile with a real key, and the
   `-Pollama` leg). `ClaimsDetailPageTests.pageLoads` still reaches the real chat bot.
5. **Wall clock:** `[INFO] Total time` before/after. That is the actual deliverable.
6. Run the full suite **3×** for the ordering risks below.

## Mailbox isolation — already solved, with one real gap

**A per-class fresh GreenMail already exists.** `GreenMailMailbox.purge()` hits the REST API's
`POST /api/mail/purge`, which empties **every** mailbox, and every mail-touching class already calls it in
`@BeforeEach`: `IntakeFlowTestBase` (via `IntakeScenario.reset()`, which also `deleteUser`s the claims
address), `NotificationServiceTests`, `IntakeReplySenderTests`, `GreenMailMailboxTests`, and `ClaimsMailboxTests`
(`deleteUser`). Purging in `@BeforeEach` rather than `@AfterEach` is deliberate, so another class's mail cannot
leak into a "no email sent" assertion.

**Merging the boots changes nothing here**, because GreenMail is a Compose Dev Service started once per `mvnw`
run — it is already shared across all 8 of today's boots, not recreated per boot. `NotificationServiceTests` is
*already* exposed to intake mail today; the boot boundary only separates them in time. Do **not** build a
`@TestTransaction` analogue for mail: mail is not transactional, and the isolation it would provide is already
bought by the global purge.

**The one thing purge-before-each cannot defend against** is mail that arrives *during* your test from another
class's async work — an intake reply handed to the mailer on a virtual thread after `IntakeScenario.cleanUp()`
cancelled its run. That risk exists today between intake classes; merging only adds a new victim, and only one:

**`NotificationServiceTests.assertNoEmailSent()` is the single global negative assertion in the suite.** It
asserts `mailbox.allMessages()` is empty across every mailbox. Every other mail assertion is positive ("a
message arrived at this address") and is therefore immune.

**Fix it by scoping the assertion, not by purging harder.** Notification emails are
`From: noreply@parasol.com` (`NotificationService.MESSAGE_FROM`); intake replies are
`From: claims@parasol.com` (`IntakeReplySender` sets `this.config.address()`). The two senders are disjoint, so:

```java
private void assertNoEmailSent() {
    assertThat(this.mailbox.allMessages())
        .filteredOn(message -> NotificationService.MESSAGE_FROM.equals(message.from()))
        .isEmpty();
}
```

This is immune to every intake reply by construction, needs no cross-class coordination, and states the intent
more precisely than the global check did — "the notification service sent nothing", rather than "nothing at all
happened anywhere". Make this change as part of step 6.

**Optional hygiene:** the purge convention is currently per-class opt-in and a new class can forget it. It could
be formalized as a JUnit `BeforeEachCallback` extension (or a shared base class) so every mail-touching class
gets it automatically. Worth doing for discoverability, but it is not what makes the merge safe — the scoped
assertion is.

## Risks

- **R1 — precedence is inverted from intuition (C1).** Any future key added to `%test` silently overrides both
  Maven profiles. Needs a loud comment in `%test` and a CLAUDE.md gotcha.
- **R2 — `ClaimWebsocketChatBotTests` now shares a boot with `DriftDetectionChatRouteExceptionHandlerTests`.**
  The first `@InjectMock`s `ClaimService`; the second drives the *real* `ClaimService.chat` through the same
  `/_chat/routes` websocket against WireMock. **The single most likely new failure** — if the route's resolved
  bean is cached anywhere, whichever runs second gets the wrong one. Alphabetical ordering happens to put
  `ai.scoring.drift.…` first, which is the safer order; do not rely on it. Watch this in the first full run.
- **R3 — `--disable-gpu` removed.** Only provable in CI. If the UI tests go flaky on `ubuntu-latest`, revert
  `args = {}` and accept 5 UI boots; the rest of the consolidation is independent.
- **R4 — shared database generation.** The Dev Services are already shared across today's 8 boots, so this is
  narrower than it looks: what changes is that the 22 bare classes no longer get a freshly `drop-and-create`d
  schema before the 32 intake classes. `IntakeScenario.deleteCreatedData()` is unconditional
  (`ClaimCorrespondence.deleteAll(); ClaimIntakeState.deleteAll(); Claim.delete("id > 6")`) and now runs in the
  same generation as `ClaimCorrespondenceResourceTests` / `ClaimImageResourceTests` — both safe today since they
  manage their own fixtures per test, but any new bare test persisting correspondence across methods gets wiped.
  Verified safe: the claim-number sequence (no test asserts an absolute generated number), `ClaimsListPageTests`
  (own boot, own `drop-and-create`), `ClaimImageSeederTests`.
- **R5 — Easy RAG OFF for the 22 bare classes.** None uses RAG. Side effect: `easy-rag-embeddings.json` is now
  written **only** by the UI boot, so the "delete it when switching between the default and Ollama profiles"
  warning becomes more load-bearing, not less.
- **R6 — test-only config in the production jar.** Already true (`%test` is at line 401). The new keys are
  inert: SmallRye expands expressions lazily and nothing reads a `%test.`-prefixed property when `test` is not
  active, so `${quarkus.wiremock.devservices.port}` is never evaluated in prod. Keeping
  `parasol.test.wiremock-base-url` *inside* `%test` means it is not even an active key there.
- **R7 — `%test` loses to system properties where `getConfigOverrides()` won.** Checked every system property
  the build sets; none collides with a model key. A developer passing `-Dquarkus.langchain4j.openai.base-url=…`
  now wins, which is arguably an improvement.

### Follow-up once this lands

Add to section 1 ("Workarounds waiting on an upstream fix") of
[#286](https://github.com/edeandrea/non-deterministic-no-problem/issues/286), which already collects this kind
of item and carries the rule that **"fixed" means released, not merged**:

> - [ ] **Drop `args = {}` from `@WithPlaywright`** (`src/test/java/org/parasol/ui/PlaywrightTests.java`) and
>   restore the `--disable-gpu` default once the project moves to a Quarkus carrying the fix for
>   [quarkus#57464](https://github.com/quarkusio/quarkus/issues/57464)
>   ([PR #57465](https://github.com/quarkusio/quarkus/pull/57465), milestone 4.0.0.Beta2). **Watch the
>   `triage/backport-3.40` label on that PR** — it is queued for 3.40 but was not in 3.40.1.1, so the trigger is
>   the first 3.40.x release that contains `TestResourceManager$TestResourceComparisonInfoArrayValue` with a
>   `toString()` override. Note #56443 alone is *not* enough: it shipped the class in 4.0.0.Beta1 without the
>   `toString()`, which is the bit `getReloadGroupIdentifier` uses. The signal is that the five Playwright
>   classes **still share one boot** after restoring the default — verify with the boot count, not by assuming.
