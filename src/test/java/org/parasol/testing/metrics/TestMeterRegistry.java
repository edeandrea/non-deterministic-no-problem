package org.parasol.testing.metrics;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * An in-memory meter registry, so tests can read meters. Quarkus adds every {@code MeterRegistry} bean to the global
 * composite, and a composite reads its first child, the OpenTelemetry bridge, which records nothing while the SDK is off. Inject this
 * one to read a meter.
 */
class TestMeterRegistry {
	@Produces
	@Singleton
	SimpleMeterRegistry simpleMeterRegistry() {
		return new SimpleMeterRegistry();
	}
}
