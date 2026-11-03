package org.parasol.testing;

import org.parasol.testing.mail.ToxiproxyTestResource;

import io.quarkiverse.wiremock.devservice.WireMockServerConnector;
import io.quarkus.test.common.QuarkusTestResource;

/**
 * Registers WireMock and Toxiproxy once, globally, for every test class in the module (the default
 * {@code restrictToAnnotatedClass = false} makes each entry {@code GLOBAL} scope), so every class's test-resource
 * reload key is identical and the number of application boots equals the number of distinct {@code @TestProfile}
 * classes, not the number of test-resource combinations.
 * <p>
 *   {@code @ConnectWireMock} can't be used here: it's a meta-annotation, and
 *   {@code TestResourceManager.getUniqueTestResourceClassEntries} only discovers meta-annotations by reflecting
 *   over a test class's own superclass chain, enclosing-class chain, and {@code @TestProfile} class
 *   ({@code collectMetaAnnotations}) - never over an unrelated holder class. The Jandex-indexed path that would
 *   otherwise see it explicitly skips annotation types ({@code keepTestResourceAnnotation}). With no profile class
 *   for the default test group there is nowhere to hang {@code @ConnectWireMock}, so {@link WireMockServerConnector}
 *   is registered directly. Filed upstream as
 *   <a href="https://github.com/quarkusio/quarkus/issues/58084">quarkusio/quarkus#58084</a> - revert to
 *   {@code @ConnectWireMock} on this holder once that's fixed. Safe either way:
 *   {@code WireMockServerConnector.init(ConnectWireMock)} only runs when the entry carries a config annotation,
 *   and this project never sets {@code @ConnectWireMock}'s {@code restClient()} attribute.
 * </p>
 */
@QuarkusTestResource(WireMockServerConnector.class)
@QuarkusTestResource(ToxiproxyTestResource.class)
public final class GlobalTestResources {
	private GlobalTestResources() {
	}
}
