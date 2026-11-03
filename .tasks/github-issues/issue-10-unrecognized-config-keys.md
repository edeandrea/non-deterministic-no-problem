TITLE: Triage the unrecognized configuration keys reported at startup

> **Superseded 2026-11-03.** All 36 keys (the 35 found here plus a 36th CI-only one) were resolved to zero: the two
> dead keys deleted, the CI Ollama Dev Service flag scoped to the `ollama` leg, the Jackson key's spelling corrected
> (and kept on), the Ollama block moved into `%ollama` behind `parasol.models.ollama.*`, and `quarkus.aiscoring.*`
> renamed to `aiscoring.*` (outside the `quarkus.` namespace, so it's never checked). See
> `.tasks/07-gh290-unrecognized-config-keys/PLAN.md` for the full decision record, including two indentation/ambiguity
> bugs the move itself introduced and fixed along the way. The body below is left as filed, for history.

## Summary

Every application boot logs a batch of `Unrecognized configuration key "…" was provided; it will be ignored`
warnings — **35 distinct keys** across the surefire reports of a full `verify`. Most are benign, but at least two are
real: one setting is silently not applied because of a one-word typo, and another names an extension that isn't on
the classpath at all.

Because the warning fires for so many keys that are genuinely fine, the two real ones are invisible in the noise.
This issue is to triage the whole set, fix what's broken, and decide what to do about the rest.

Found while planning #218 (the Grafana dashboard), which turned up the micrometer key; the wider sweep came out of
checking whether it was alone. It is not part of #218, which stays a pure deletion.

## The real problems

### 1. `quarkus.rest.jackson.optimization.enable-reflection-free-serialization` is a typo

Set at `src/main/resources/application.yml:242`. The key that `quarkus-rest-jackson` 3.40.1.1 actually declares is:

```
quarkus.rest.jackson.optimization.enable-reflection-free-serializers
```

`-serialization` versus `-serializers`. Confirmed against
`META-INF/quarkus-config-doc/quarkus-config-model.json` in `quarkus-rest-jackson-deployment-3.40.1.1.jar`, which
declares the `-serializers` spelling and nothing else matching `reflection-free`.

**So the optimization has never been applied.** Worth checking what the correct spelling actually changes here before
simply turning it on — reflection-free serializers have known limitations with some Jackson features, and the DTOs use
`@JsonNaming`, `@JsonValue` and a global `serialization-inclusion`.

### 2. `quarkus.micrometer.export.otlp.*` belongs to an extension that isn't on the classpath

Set at `src/main/resources/application.yml:35` (`%drift`) and `:433` (`%test`):

```yaml
micrometer:
  export:
    otlp:
      enabled: false
```

`quarkus.micrometer.export.otlp` is the config root of **`quarkus-micrometer-registry-otlp`**, which is not a
dependency — `target/quarkus-app/lib/main/` has no `registry-otlp` jar. `quarkus-micrometer` 3.40.1.1's own config
model declares only `quarkus.micrometer.export.json.*` and `quarkus.micrometer.export.prometheus.*`.

The bridge actually in use, `quarkus-micrometer-opentelemetry`, has **no runtime config of its own**: it force-sets
`quarkus.otel.metrics.enabled` and `quarkus.otel.logs.enabled` at priority 1, and activates only when
`quarkus.otel.metrics.enabled` is true and the OTel metrics exporter isn't `none`. The real off switch in both
profiles is `quarkus.otel.exporter.otlp.enabled: false`, which both already set — so removing these keys changes no
behavior.

Note `%test` sets it to `"${%test.quarkus.observability.enabled}"`, a self-referencing interpolation that is also
pointless once the key is gone.

## The false positives (no action needed, but they are what hides the real ones)

| Count | Keys | Why they warn |
|---|---|---|
| 21 | `quarkus.langchain4j.ollama.*` | Only active under `%ollama`. A test run on another profile doesn't have the Ollama extension's config root, so every key warns. |
| 11 | `quarkus.aiscoring.*` | The app's own `@ConfigMapping` roots (`ScoringConfig`, `LangfuseConfig`). They work, but they sit under the `quarkus.` namespace, which Quarkus builds its known-key model from extension metadata — so an application mapping there is always "unrecognized". |
| 1 | `quarkus.dev-ui.cors.enabled` | Valid, but declared in `quarkus-devui-deployment`, which is dev-mode only. The warning comes from test runs, where that module isn't present. |

## Questions to settle

1. **The Jackson key:** correct the spelling, or drop the setting? If corrected, does anything in the REST layer
   change — do `ClaimResourceTests.jsonFieldNames` and the other JSON-shape tests still pass?
2. **The `quarkus.aiscoring.*` namespace:** leave it (and accept 11 warnings forever), or move it to `parasol.aiscoring.*`
   to match `parasol.intake.*` and `parasol.claims-department.*`? A rename touches `application.yml`, both
   `@ConfigMapping` interfaces, `src/main/kubernetes/dependencies.yml`'s `parasol-app-config` ConfigMap and the docs.
3. **The remaining noise:** is it worth silencing the profile-specific ones, or is the warning list simply something to
   read with knowledge of which entries are expected? If it stays noisy, that fact belongs in `CLAUDE.md` so the next
   person doesn't re-investigate it.

## Done when

- [ ] Every one of the 35 keys is accounted for as fixed, removed, or a documented expected warning
- [ ] The Jackson optimization key is either correct or gone, with the decision recorded
- [ ] The dead `quarkus.micrometer.export.otlp.*` keys are removed from `%drift` and `%test`
- [ ] A decision on the `quarkus.aiscoring.*` namespace is recorded
- [ ] `CLAUDE.md` says which startup warnings are expected, if any remain
- [ ] `./mvnw -B clean verify` is green under both CI profiles

Related to #218
