package org.parasol.intake.observability;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.parasol.intake.StartedRuns.StartedRun;
import org.parasol.intake.TestSpans;
import org.parasol.intake.workflow.ClaimIntakeFlow;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;

/**
 * Sorts exported spans by what they are and whether they carry the right conversation id, so a test can assert it and
 * print the counts.
 * <ul>
 *   <li><b>Trace membership:</b> a run's trace is the one holding its own Flow spans ({@code flow.workflow.instance.id}).
 *   Every other trace is "other".</li>
 *   <li><b>Run identity:</b> a span in a run's trace should carry that run's id (from the run's input, never from a span).
 *   A span in another trace should carry none of the runs' ids.</li>
 * </ul>
 */
final class SpanCensus {
	private static final List<AttributeKey<String>> AI_ATTRIBUTES = List.of(
		GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME,
		GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL,
		GenAiIncubatingAttributes.GEN_AI_SYSTEM,
		AttributeKey.stringKey("gen_ai.provider.name"));
	private static final List<AttributeKey<String>> URL_ATTRIBUTES = List.of(AttributeKey.stringKey("url.full"), AttributeKey.stringKey("http.url"));
	private static final List<AttributeKey<String>> DB_ATTRIBUTES = List.of(AttributeKey.stringKey("db.system"), AttributeKey.stringKey("db.system.name"));
	private static final int MAX_EXAMPLES = 12;

	enum Kind {
		/** The intake run's own {@code workflow.*} / {@code task.execute} spans */
		FLOW_RUN,
		/** The Flow spans of the agent sub-workflows Flow generates for the agentic system */
		FLOW_SUB_WORKFLOW,
		/** AI service, chat model and model HTTP spans not under a sub-workflow */
		AI_FROM_STEP,
		/** AI service, chat model and model HTTP spans under a sub-workflow */
		AI_UNDER_SUB_WORKFLOW,
		JDBC_PARENTED,
		JDBC_ROOT,
		/** The IMAP and SMTP {@code CLIENT} spans of the mailbox and the reply sender */
		MAIL,
		OTHER
	}

	enum Status {
		/** In its run's trace, with that run's id */
		OWN,
		/** No id */
		MISSING,
		/** In a run's trace, with another run's id */
		FOREIGN,
		/** Outside a run's trace, with a run's id */
		OUTSIDE_RUN_TRACE,
		/** An id of an earlier batch's run */
		STALE,
		/** An id no run had */
		UNKNOWN
	}

	record Entry(SpanData span, Kind kind, boolean inRunTrace, Status status) {
		String describe() {
			return "%s [%s, %s, trace %s, parent %s, id %s]".formatted(this.span.getName(), this.kind, this.status, this.span.getTraceId(),
				this.span.getParentSpanContext().isValid() ? this.span.getParentSpanId() : "none", TestSpans.conversationIdOf(this.span).orElse("none"));
		}
	}

	private final List<Entry> entries;
	private final Map<String, Set<String>> tracesByRun;
	private final Map<String, Set<String>> idsByTrace;

	private SpanCensus(List<Entry> entries, Map<String, Set<String>> tracesByRun, Map<String, Set<String>> idsByTrace) {
		this.entries = entries;
		this.tracesByRun = tracesByRun;
		this.idsByTrace = idsByTrace;
	}

	/**
	 * Takes the census of some spans.
	 *
	 * @param spans The spans
	 * @param runs The runs they may belong to
	 * @param staleIds Ids of earlier runs, which none of these spans should carry
	 */
	static SpanCensus of(Collection<SpanData> spans, Collection<StartedRun> runs, Set<String> staleIds) {
		var spanById = spans.stream().collect(Collectors.toMap(SpanData::getSpanId, Function.identity(), (first, _) -> first));
		var idByRun = runs.stream().collect(Collectors.toMap(StartedRun::runId, StartedRun::conversationId));
		var runIds = Set.copyOf(idByRun.values());
		var tracesByRun = spans.stream()
			.filter(span -> isRunWorkflow(span) && idByRun.containsKey(span.getAttributes().get(TestSpans.INSTANCE_ID)))
			.collect(groupingBy(span -> span.getAttributes().get(TestSpans.INSTANCE_ID), TreeMap::new, Collectors.mapping(SpanData::getTraceId, toSet())));
		var idByTrace = new TreeMap<String, String>();
		tracesByRun.forEach((runId, traces) -> traces.forEach(trace -> idByTrace.put(trace, idByRun.get(runId))));

		var entries = spans.stream()
			.map(span -> {
				var expected = Optional.ofNullable(idByTrace.get(span.getTraceId()));
				var actual = TestSpans.conversationIdOf(span);
				return new Entry(span, kindOf(span, spanById), expected.isPresent(), statusOf(expected, actual, runIds, staleIds));
			})
			.toList();
		var idsByTrace = spans.stream()
			.filter(span -> TestSpans.conversationIdOf(span).isPresent())
			.collect(groupingBy(SpanData::getTraceId, TreeMap::new, Collectors.mapping(span -> TestSpans.conversationIdOf(span).orElseThrow(), toSet())));

		return new SpanCensus(entries, tracesByRun, idsByTrace);
	}

	private static Status statusOf(Optional<String> expected, Optional<String> actual, Set<String> runIds, Set<String> staleIds) {
		if (actual.isEmpty()) {
			return Status.MISSING;
		}

		var id = actual.get();

		if (staleIds.contains(id)) {
			return Status.STALE;
		}

		if (!runIds.contains(id)) {
			return Status.UNKNOWN;
		}

		return expected.map(own -> own.equals(id) ? Status.OWN : Status.FOREIGN).orElse(Status.OUTSIDE_RUN_TRACE);
	}

	private static Kind kindOf(SpanData span, Map<String, SpanData> spanById) {
		if (isFlow(span)) {
			return isRunWorkflow(span) ? Kind.FLOW_RUN : Kind.FLOW_SUB_WORKFLOW;
		}

		if (isAi(span)) {
			return isUnderSubWorkflow(span, spanById) ? Kind.AI_UNDER_SUB_WORKFLOW : Kind.AI_FROM_STEP;
		}

		if (DB_ATTRIBUTES.stream().anyMatch(key -> span.getAttributes().get(key) != null)) {
			return span.getParentSpanContext().isValid() ? Kind.JDBC_PARENTED : Kind.JDBC_ROOT;
		}

		if (isMail(span)) {
			return Kind.MAIL;
		}

		return Kind.OTHER;
	}

	private static boolean isMail(SpanData span) {
		return (span.getKind() == SpanKind.CLIENT) && Set.of("imap", "smtp").contains(span.getAttributes().get(TestSpans.MESSAGING_SYSTEM));
	}

	private static boolean isFlow(SpanData span) {
		return span.getAttributes().get(TestSpans.WORKFLOW_NAME) != null;
	}

	private static boolean isRunWorkflow(SpanData span) {
		return ClaimIntakeFlow.NAME.equals(span.getAttributes().get(TestSpans.WORKFLOW_NAME));
	}

	private static boolean isAi(SpanData span) {
		return span.getName().startsWith("langchain4j")
			|| AI_ATTRIBUTES.stream().anyMatch(key -> span.getAttributes().get(key) != null)
			|| ((span.getKind() == SpanKind.CLIENT) && URL_ATTRIBUTES.stream()
				.map(key -> span.getAttributes().get(key))
				.anyMatch(url -> (url != null) && url.contains("/chat/completions")));
	}

	private static boolean isUnderSubWorkflow(SpanData span, Map<String, SpanData> spanById) {
		return Stream.iterate(
				Optional.ofNullable(spanById.get(span.getParentSpanId())),
				Optional::isPresent,
				current -> current.map(ancestor -> spanById.get(ancestor.getParentSpanId()))
			)
			.limit(200)
			.flatMap(Optional::stream)
			.anyMatch(ancestor -> isFlow(ancestor) && !isRunWorkflow(ancestor));
	}

	SpanCensus filter(Predicate<SpanData> predicate) {
		return new SpanCensus(this.entries.stream().filter(entry -> predicate.test(entry.span())).toList(), this.tracesByRun, this.idsByTrace);
	}

	List<Entry> entries() {
		return this.entries;
	}

	long count(Kind kind) {
		return this.entries.stream().filter(entry -> entry.kind() == kind).count();
	}

	/**
	 * Spans in a run's trace that don't carry exactly that run's id.
	 */
	List<Entry> wrongInRunTraces() {
		return this.entries.stream().filter(entry -> entry.inRunTrace() && (entry.status() != Status.OWN)).toList();
	}

	/**
	 * Spans outside every run's trace that carry an id: a run's (it left its trace), an earlier run's, or one no run had.
	 * Flow's parentless JDBC persistence statements run on the starter's thread inside {@code runIn}, so they carry the
	 * starting run's id as separate root traces; only foreign, stale, or unknown ids on them, or ids on other spans outside
	 * run traces, are problems.
	 */
	List<Entry> idsOutsideRunTraces() {
		return this.entries.stream()
			.filter(entry -> !entry.inRunTrace() && (entry.status() != Status.MISSING))
			.filter(entry -> !((entry.kind() == Kind.JDBC_ROOT) && (entry.status() == Status.OUTSIDE_RUN_TRACE)))
			.toList();
	}

	/**
	 * Spans outside every run's trace that have no id, other than JDBC roots (statements with no span around them, such as
	 * Flow's persistence or the starter's lookups before the run), mail spans (the failure handler's work and the starter's may
	 * legitimately start outside a run's trace; they show in the report, and a mail span with an id outside its run's trace is
	 * still a problem) and unclassified ones.
	 */
	List<Entry> lostOutsideRunTraces() {
		return this.entries.stream()
			.filter(entry -> !entry.inRunTrace() && (entry.status() == Status.MISSING))
			.filter(entry -> (entry.kind() != Kind.JDBC_ROOT) && (entry.kind() != Kind.MAIL) && (entry.kind() != Kind.OTHER))
			.toList();
	}

	/**
	 * Runs whose Flow spans aren't all in one trace (or that have none).
	 */
	Map<String, Set<String>> runsNotInOneTrace(Collection<StartedRun> runs) {
		return runs.stream()
			.filter(run -> this.tracesByRun.getOrDefault(run.runId(), Set.of()).size() != 1)
			.collect(Collectors.toMap(StartedRun::runId, run -> this.tracesByRun.getOrDefault(run.runId(), Set.of())));
	}

	/**
	 * Traces whose spans carry more than one id.
	 */
	Map<String, Set<String>> mixedTraces() {
		return this.idsByTrace.entrySet().stream()
			.filter(entry -> entry.getValue().size() > 1)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	String render(String title) {
		var report = new StringBuilder("%n=== Span census: %s ===%n%d spans, %d traces (%d of them runs' traces)%n".formatted(title, this.entries.size(),
			this.entries.stream().map(entry -> entry.span().getTraceId()).distinct().count(),
			this.entries.stream().filter(Entry::inRunTrace).map(entry -> entry.span().getTraceId()).distinct().count()));
		var statuses = Arrays.stream(Status.values()).map(Enum::name).collect(Collectors.joining(" "));
		report.append("%-22s | in a run's trace: %s | other traces: %s%n".formatted("kind", statuses, statuses));

		for (var kind : Kind.values()) {
			report.append("%-22s | %s | %s%n".formatted(kind, counts(kind, true), counts(kind, false)));
		}

		report.append("Span names by kind:%n".formatted());
		this.entries.stream()
			.collect(groupingBy(Entry::kind, TreeMap::new, groupingBy(entry -> entry.span().getName().replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<uuid>"), TreeMap::new, counting())))
			.forEach((kind, names) -> report.append("  %s: %s%n".formatted(kind, names)));

		var problems = new ArrayList<Entry>();
		problems.addAll(wrongInRunTraces());
		problems.addAll(idsOutsideRunTraces());
		problems.addAll(lostOutsideRunTraces());
		report.append("Gaps: %d (missing/foreign/stale in runs' traces: %d, ids outside runs' traces: %d, Flow/AI/parented JDBC lost outside runs' traces: %d); mixed traces: %d%n"
			.formatted(problems.size(), wrongInRunTraces().size(), idsOutsideRunTraces().size(), lostOutsideRunTraces().size(), mixedTraces().size()));
		problems.stream().limit(MAX_EXAMPLES).forEach(entry -> report.append("  e.g. %s%n".formatted(entry.describe())));

		return report.toString();
	}

	private String counts(Kind kind, boolean inRunTrace) {
		return Arrays.stream(Status.values())
			.map(status -> this.entries.stream().filter(entry -> (entry.kind() == kind) && (entry.inRunTrace() == inRunTrace) && (entry.status() == status)).count())
			.map("%4d"::formatted)
			.collect(Collectors.joining(" "));
	}
}
