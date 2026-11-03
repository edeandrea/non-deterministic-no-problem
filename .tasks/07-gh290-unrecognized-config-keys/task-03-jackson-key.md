# Task 03: Correct the Jackson Optimization Key and Keep It On

**Type:** Configuration Modification

## Goal

`quarkus.rest.jackson.optimization.enable-reflection-free-serializers: true` is set, build-time reflection-free
serializers are actually generated for the REST DTOs, and every JSON byte on the wire is unchanged — proven by the
existing wire-shape tests plus task 02's new ones.

## What to Do

`src/main/resources/application.yml:238-242` — one word:

```yaml
  # Jackson
  rest:
    jackson:
      optimization:
        enable-reflection-free-serializers: true   # was: -serialization
```

Leave `quarkus.jackson.serialization-inclusion: non-empty` (`:244-245`) alone.

Then **confirm the generation actually happened**, because a silently-skipped class would look exactly like success.
Build once and check `target/` for the generated classes — `*$quarkusjacksonserializer` and
`SerializedStrings$quarkusjacksonserializer`, e.g.:

```bash
find target -name '*quarkusjacksonserializer*' | sort
```

Expect serializers for the types reachable from JAX-RS return types: `ClaimDetails`, `ClaimImageMetadata`,
`CorrespondenceMessage`, `ClaimReviewState`, `SentimentAssessment`, and deserializers for `ReviewDecisionRequest`
(with its nested `Fields`) and `SentimentAssessmentRequest`. **Do not** expect one for `ProblemDetail`, `IntakeOutcome`,
`IntakeState` or `IntakeRun` — see Key Points. If a DTO you expected is missing, raise it; run the build with debug
logging for `io.quarkus.resteasy.reactive.jackson.deployment.processor` to see the "Skipping generation … unsupported
Jackson annotation" line.

Then run the full verification in Done When and **eyeball two values**, not just key sets:

- `CorrespondenceMessage.date` (an `Instant`) is still ISO-8601, not an epoch number.
- `ClaimDetails.incident_date` is still `"1955-01-02"` form (`ClaimResourceTests.nullIncidentTimeIsOmitted` pins the
  value, so this is belt and braces).

## Files/Areas

- `src/main/resources/application.yml` (one line)

## Key Points

- **The key has never worked.** The declared property is `…-serializers`
  (`io.quarkus.resteasy.reactive.jackson.deployment.processor.JacksonOptimizationConfig#enableReflectionFreeSerializers`,
  `@WithDefault("false")`). Checked across every `quarkus-rest-jackson-deployment` jar in the local `.m2` from 3.30.6
  through 999-SNAPSHOT: `-serializers` is the only spelling that has ever existed. So this is a behavior change, even
  though it reads like a typo fix. Treat it as one.
- **Registration is application-wide, not REST-only.** `GeneratedSerializersRegister implements
  io.quarkus.jackson.ObjectMapperCustomizer` and installs a `SimpleModule` on the CDI `ObjectMapper`. That is why the
  Flow round-trip tests are in the Done When list even though no Flow type should be affected: generation is seeded
  only from JAX-RS return types and POST/PUT/PATCH parameter types
  (`ResteasyReactiveJacksonProcessor.handleEndpointParams`, transitively through field types), and `IntakeOutcome`,
  `IntakeState`, `IntakeRun` and `ReviewDecision` are reachable from none of them. Verify, don't assume.
- **`ProblemDetail` is out of scope by construction.** Every mapper returns `Response` with the entity typed only at
  runtime, so there is no static return type to generate from. `application/problem+json` keeps going through
  reflective Jackson. Note this in task 06 rather than implying the optimization covers the whole API.
- **Why this is safe:** `SUPPORTED_JACKSON_ANNOTATIONS` covers every annotation the repo uses (`@JsonNaming`,
  `@JsonValue`, `@JsonCreator`, `@JsonIgnore`, `@JsonTypeInfo`, `@JsonSubTypes`); an unsupported one makes the
  generator skip the class and fall back to reflection rather than emit wrong output; `@JsonNaming`, `@JsonValue` and
  the global inclusion (`SerializationInclude.decode(object, serializerProvider)`) are all honored by the generated
  bytecode; and a `java.time` field with no `@JsonFormat` falls through to `writeFieldValue`, which delegates to the
  serializer provider, so JavaTimeModule behavior is preserved.
- **`ClaimCategory.fromValue`'s failure shape matters.** `ReviewExceptionMappings.unreadableBody` depends on an unknown
  category arriving as a `ValueInstantiationException` from the `@JsonCreator`. The generated **deserializer** for
  `ReviewDecisionRequest` is the piece that could move that; `ClaimReviewResourceTests`' 400 cases are the guard.
- **If something does regress,** the fallback decision is on record: the alternative considered and rejected was
  deleting the setting outright. Reverting this one line restores the status quo exactly — don't start relaxing
  assertions or adding Jackson annotations to work around a generator limitation. Raise it instead.

## Done When

- [ ] `application.yml` reads `enable-reflection-free-serializers: true`.
- [ ] `find target -name '*quarkusjacksonserializer*'` shows generated classes for the REST DTOs listed above.
- [ ] `./mvnw -B clean verify -Pollama` and `./mvnw -B clean verify -Pollama-openai` are both green.
- [ ] Specifically green, and specifically checked: `ClaimResourceTests` (`jsonFieldNames`,
      `categoryIsSerializedAsLabel`, `nullIncidentTimeIsOmitted`), `ClaimImageResourceTests.metadataJsonFieldNames`,
      `ClaimCorrespondenceResourceTests` (`jsonFieldNames`, `anEmailWithoutPhotosHasNoImagesKey`),
      `ClaimRetrievalHasNoIntakeBookkeepingTests`, `ClaimReviewResourceTests` (including task 02's new tests and the
      existing 400/409/422 cases), `IntakeJsonRoundTripTests`, `IntakeOutcomeRoundTripTests`,
      `ClaimExtractionValueRoundTripTests`, `ClaimReviewPanelPageTests`, `RoundcubeClaimIntakeE2ETests`.
- [ ] `CorrespondenceMessage.date` is ISO-8601 on the wire.
- [ ] The unrecognized-key list no longer contains
      `quarkus.rest.jackson.optimization.enable-reflection-free-serialization` (12 distinct keys → 11, all
      `aiscoring`).
