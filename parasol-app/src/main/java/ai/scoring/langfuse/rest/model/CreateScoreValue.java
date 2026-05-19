package ai.scoring.langfuse.rest.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public sealed interface CreateScoreValue permits CreateScoreValue.Numeric, CreateScoreValue.Text {

	record Numeric(@JsonValue double value) implements CreateScoreValue {
	}

	record Text(@JsonValue String value) implements CreateScoreValue {
	}

	static CreateScoreValue of(double value) {
		return new Numeric(value);
	}

	static CreateScoreValue of(String value) {
		return new Text(value);
	}

	@JsonCreator
	static CreateScoreValue fromJson(Object value) {
		if (value instanceof Number n) {
			return new Numeric(n.doubleValue());
		}

		return new Text(String.valueOf(value));
	}
}
