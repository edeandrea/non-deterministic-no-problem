package org.parasol.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.data.SpanData;

class TestSpansTests {
	@Test
	void exportsAreIgnoredUntilCaptureStarts() {
		try (var exporter = new TestSpans()) {
			assertThat(exporter.export(List.of(mock(SpanData.class))).isSuccess()).isTrue();
			assertThat(exporter.all()).isEmpty();
		}
	}

	@Test
	void captureAppendsBatchesAndReturnsIndependentSnapshots() {
		try (var exporter = new TestSpans()) {
			var first = mock(SpanData.class);
			var second = mock(SpanData.class);
			exporter.startCapture();
			exporter.export(List.of(first));
			var snapshot = exporter.all();
			exporter.export(List.of(second));

			assertThat(snapshot).containsExactly(first);
			assertThat(exporter.all()).containsExactly(first, second);
		}
	}

	@Test
	void resetClearsTheCurrentCaptureAndStartingAgainClearsItToo() {
		try (var exporter = new TestSpans()) {
			var span = mock(SpanData.class);
			exporter.startCapture();
			exporter.export(List.of(span));
			exporter.reset();
			assertThat(exporter.all()).isEmpty();
			exporter.export(List.of(span));
			assertThat(exporter.all()).containsExactly(span);
			exporter.startCapture();
			assertThat(exporter.all()).isEmpty();
		}
	}

	@Test
	void stoppingClearsCaptureAndIgnoresLaterExportsUntilRestarted() {
		try (var exporter = new TestSpans()) {
			var span = mock(SpanData.class);
			exporter.startCapture();
			exporter.export(List.of(span));
			exporter.stopCapture();
			exporter.export(List.of(span));
			assertThat(exporter.all()).isEmpty();

			exporter.startCapture();
			exporter.export(List.of(span));
			assertThat(exporter.all()).containsExactly(span);
			exporter.shutdown();
			exporter.export(List.of(span));
			assertThat(exporter.all()).isEmpty();
		}
	}

	@Test
	void concurrentExportsCannotRepopulateAStoppedCapture() throws Exception {
		try (var exporter = new TestSpans()) {
			var span = mock(SpanData.class);
			var start = new CountDownLatch(1);
			exporter.startCapture();
			try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
				var exports = IntStream.range(0, 32)
					.mapToObj(_ -> workers.submit(() -> {
						assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
						return exporter.export(List.of(span));
					}))
					.toList();
				var stop = workers.submit(() -> {
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					exporter.stopCapture();
					return true;
				});
				start.countDown();
				for (var export : exports) {
					assertThat(export.get(10, TimeUnit.SECONDS).isSuccess()).isTrue();
				}
				assertThat(stop.get(10, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(exporter.all()).isEmpty();
		}
	}
}
