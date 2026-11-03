# FILED — [quarkusio/quarkus#58084](https://github.com/quarkusio/quarkus/issues/58084)

Filed 2026-11-03. This file is the working draft; the filed body additionally carries the template's
environment fields (`uname -a`, `java -version`, Quarkus version, build tool) and folds *Real-world impact*
and *Possible fix* into **Additional information** to match the issue template's field order.

---

## Title

Meta-annotated test resources are assigned `GLOBAL` scope but only discovered on the test class's own hierarchy

---

## Describe the bug

A test resource declared **directly** with `@QuarkusTestResource(X.class)` on any class under the test
classes directory is discovered through the Jandex index and applies to **every** test class in the module
(that is what `restrictToAnnotatedClass = false`, the default, means).

A test resource declared through a **meta-annotation** — an annotation that itself carries
`@QuarkusTestResource` / `@WithTestResource`, such as `@ConnectWireMock` from quarkus-wiremock or
`@WithPlaywright` from quarkus-playwright — is discovered only by reflection over the test class's own
superclass chain, enclosing-class chain, and `@TestProfile` class. It can therefore never be registered
globally, even though the entry it produces is explicitly given `TestResourceScope.GLOBAL`.

So the scope recorded for the entry and the scope it actually has disagree. Placing a meta-annotation on a
plain holder class in the test folder — the idiomatic way to register a global test resource once — silently
does nothing: no error, no warning, the resource simply never starts.

## Expected behavior

A meta-annotated test resource on a class in the test classes directory is discovered the same way a direct
`@QuarkusTestResource` is, and honors `restrictToAnnotatedClass` / `TestResourceScope` identically. In
particular, `@ConnectWireMock` on a non-test holder class registers WireMock globally.

## Actual behavior

The annotation is ignored unless it sits on the test class itself, one of its superclasses, one of its
enclosing classes, or its `@TestProfile` class.

## Root cause

`TestResourceManager.getUniqueTestResourceClassEntries` has two discovery paths:

```java
// handle meta-annotations: in this case we must rely on reflection because meta-annotations are not indexed
// because they are not in the user's test folder but come from test extensions
collectMetaAnnotations(testClassFromTCCL, Class::getSuperclass, uniqueEntries);
collectMetaAnnotations(testClassFromTCCL, Class::getEnclosingClass, uniqueEntries);
if (afterMetaAnnotationAction != null) {
    afterMetaAnnotationAction.accept(uniqueEntries);   // the @TestProfile class
}
for (AnnotationInstance annotation : findTestResourceInstancesOfClass(testClass,
        TestClassIndexer.readIndex(testClassLocation, testClass))) {
    uniqueEntries.add(TestResourceClassEntryHandler.produceEntry(annotation));
}
```

1. `collectMetaAnnotations` only ever walks the one test class's own hierarchy, so a meta-annotation anywhere
   else is invisible.
2. `findTestResourceInstancesOfClass` queries the index for the `@WithTestResource` and `@QuarkusTestResource`
   `DotName`s only — direct usages. It never resolves meta-annotations, and it deliberately skips the one place
   they do appear in the index, the annotation type itself:

```java
private static boolean keepTestResourceAnnotation(AnnotationInstance annotation, ClassInfo targetClass,
        Set<String> currentTestClassHierarchy) {
    if (targetClass.isAnnotation()) {
        // meta-annotations have already been handled in collectMetaAnnotations
        return false;
    }
    ...
```

Meanwhile the entry built for a meta-annotation is given `GLOBAL` scope unconditionally:

```java
addTestResourceEntry(quarkusTestResource.value(), quarkusTestResource.initArgs(), originalAnnotation,
        quarkusTestResource.parallel(),
        quarkusTestResource.restrictToAnnotatedClass() ? RESTRICTED_TO_CLASS : GLOBAL,
        uniqueEntries);
```

The comment in path 1 explains the reflection as "meta-annotations are not indexed because they are not in the
user's test folder but come from test extensions". That is true of the annotation **type** — `ConnectWireMock`
lives in an extension jar, so Jandex cannot see the `@QuarkusTestResource` on it — but it is not true of the
annotation's **usages**. `@ConnectWireMock` on a class in `target/test-classes` *is* in the index as an
`AnnotationInstance`; the only missing step is resolving its annotation type via the TCCL to notice the
meta-annotation, which is exactly what `collectMetaAnnotations` already does.

## How to Reproduce

Self-contained, no extension needed, and **no application boot** — this calls the public discovery entry point
`TestResourceManager.testResourceComparisonInfo(...)` directly, so it runs as a plain JUnit test in
milliseconds. Two identical plain holder classes differ only in how the resource is declared.

```java
public class MetaResource implements QuarkusTestResourceLifecycleManager {
    @Override public Map<String, String> start() { return Map.of(); }
    @Override public void stop() { }
}

public class DirectResource implements QuarkusTestResourceLifecycleManager {
    @Override public Map<String, String> start() { return Map.of(); }
    @Override public void stop() { }
}

// Exactly the shape of @ConnectWireMock / @WithPlaywright
@QuarkusTestResource(MetaResource.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WithMetaResource { }

// A plain holder in the test folder: not a test class, not a superclass of one, not a @TestProfile
@WithMetaResource
public final class MetaHolder { }

// The same thing, declared directly rather than through a meta-annotation
@QuarkusTestResource(DirectResource.class)
public final class DirectHolder { }

public class DiscoveryProbeTest {
    @Test
    void whatIsDiscovered() {
        var location = PathTestHelper.getTestClassesLocation(DiscoveryProbeTest.class);
        var infos = TestResourceManager.testResourceComparisonInfo(DiscoveryProbeTest.class, location, List.of());

        infos.forEach(i -> System.out.printf("  %s  scope=%s%n", i.testResourceLifecycleManagerClass(), i.scope()));
    }
}
```

Actual output on 3.40.1.1:

```
PROBE discovered 1 test resource(s):
PROBE   scratch.metaprobe.DirectResource  scope=GLOBAL
PROBE DirectResource discovered? true
PROBE MetaResource   discovered? false
```

`DirectResource` is discovered with `scope=GLOBAL` and applies to a test class that never references it.
`MetaResource` is not discovered at all, even though `@WithMetaResource` is the meta-annotation form of the
very same declaration, and even though the entry it would produce is also `GLOBAL`.

`testResourceComparisonInfo` and the resource-starting path both delegate to the same private
`getUniqueTestResourceClassEntries`, differing only in the consumer that adds the `@TestProfile` class's
meta-annotations — which does not apply to a holder class either way.

## Real-world impact

In Quarkus 3.40, the number of application boots in a test run is the number of distinct
`(@TestProfile class, test-resource reload key)` pairs — `FacadeClassLoader.getQuarkusClassLoader` keys one
classloader, and therefore one application, on `profileKey + resourceKey`. Collapsing `@TestProfile` classes is
therefore the main lever for cutting boot count in a large suite.

Doing that means moving shared configuration out of profile classes and into a config profile, which removes
the profile class — and with it the only place a meta-annotated test resource can be hung so that every test
class ends up with an identical reload key. Keeping one profile class purely as an annotation carrier defeats
the point, and putting `@ConnectWireMock` on all N test classes is both noisy and, for anything with an array
attribute, actively harmful.

The workaround is to bypass the convenience annotation and register the underlying manager directly:

```java
@QuarkusTestResource(WireMockServerConnector.class)
public final class GlobalTestResources { }
```

This works, but it reaches past the extension's public API into an implementation class, and it silently drops
whatever the meta-annotation's attributes configure. For `@ConnectWireMock` that is `restClient()`:
`WireMockServerConnector.init(ConnectWireMock)` is only invoked when `entry.getConfigAnnotation() != null`
(`TestResourceManager:159`), so with the direct registration the field keeps its `""` default and no REST
client is redirected. Harmless if you do not use that attribute; a silent loss of configuration if you do.

## Possible fix

In `findTestResourceInstancesOfClass`, in addition to the two direct `DotName`s, iterate the index's annotation
instances whose target is a class, resolve each distinct annotation `DotName` once via the TCCL (cached), and
where the resolved type carries `@QuarkusTestResource` / `@WithTestResource` (or their `List` / `Repeatable`
forms), produce an entry through the existing path. Scope handling is then uniform, because
`keepTestResourceAnnotation` already implements it.

The reflective load per distinct annotation type is the only new cost, and it is bounded by the number of
distinct annotations in the test index, not by the number of classes.

Happy to open a PR if maintainers agree this should behave uniformly — it is a change to discovery semantics,
so it seemed worth agreeing on the behavior first.

## Quarkus version or git rev

Reproduced on **3.40.1.1** (output above). The same four methods (`getUniqueTestResourceClassEntries`,
`collectMetaAnnotations`, `findTestResourceInstancesOfClass`, `keepTestResourceAnnotation`) are present
unchanged in **4.0.0.Beta1**, so the behavior is expected to be the same on `main` — though I have only
executed the reproducer against 3.40.1.1.

## Build tool

Maven

## Additional information

Unrelated to, but adjacent to, #57464 / #57465 (array-valued test-resource meta-annotations producing one
classloader per test class), which came up while working on the same test-suite consolidation.
