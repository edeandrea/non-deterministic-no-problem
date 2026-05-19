package ai.scoring.langfuse.rest.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluationRuleFilter {

	public enum TypeEnum {
		DATETIME("datetime"),
		STRING("string"),
		NUMBER("number"),
		STRINGOPTIONS("stringOptions"),
		CATEGORYOPTIONS("categoryOptions"),
		ARRAYOPTIONS("arrayOptions"),
		STRINGOBJECT("stringObject"),
		NUMBEROBJECT("numberObject"),
		BOOLEAN("boolean"),
		NULL("null");

		private final String value;

		TypeEnum(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}

		@JsonCreator
		public static TypeEnum fromString(String v) {
			for (TypeEnum b : values()) {
				if (b.value.equalsIgnoreCase(v)) {
					return b;
				}
			}
			throw new IllegalArgumentException("Unexpected value '" + v + "'");
		}
	}

	public enum StringOperator {
		EQUAL("="),
		CONTAINS("contains"),
		DOES_NOT_CONTAIN("does not contain"),
		STARTS_WITH("starts with"),
		ENDS_WITH("ends with");

		private final String value;

		StringOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	public enum NumberOperator {
		EQUAL("="),
		GREATER_THAN(">"),
		LESS_THAN("<"),
		GREATER_THAN_OR_EQUAL(">="),
		LESS_THAN_OR_EQUAL("<=");

		private final String value;

		NumberOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	public enum OptionsOperator {
		ANY_OF("any of"),
		NONE_OF("none of");

		private final String value;

		OptionsOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	public enum ArrayOptionsOperator {
		ANY_OF("any of"),
		NONE_OF("none of"),
		ALL_OF("all of");

		private final String value;

		ArrayOptionsOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	public enum BooleanOperator {
		EQUAL("="),
		NOT_EQUAL("<>");

		private final String value;

		BooleanOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	public enum NullOperator {
		IS_NULL("is null"),
		IS_NOT_NULL("is not null");

		private final String value;

		NullOperator(String value) {
			this.value = value;
		}

		@JsonValue
		public String value() {
			return value;
		}
	}

	private TypeEnum type;
	private String column;
	private String operator;
	private Object value;
	private String key;

	private EvaluationRuleFilter() {
	}

	@JsonCreator
	private EvaluationRuleFilter(
		@JsonProperty("type") TypeEnum type,
		@JsonProperty("column") String column,
		@JsonProperty("operator") String operator,
		@JsonProperty("value") Object value,
		@JsonProperty("key") String key
	) {
		this.type = type;
		this.column = column;
		this.operator = operator;
		this.value = value;
		this.key = key;
	}

	public static EvaluationRuleFilter stringFilter(String column, StringOperator operator, String value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.STRING;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter numberFilter(String column, NumberOperator operator, double value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.NUMBER;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter dateTimeFilter(String column, NumberOperator operator, String value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.DATETIME;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter stringOptionsFilter(String column, OptionsOperator operator, List<String> value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.STRINGOPTIONS;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter categoryOptionsFilter(String column, String key, OptionsOperator operator, List<String> value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.CATEGORYOPTIONS;
		filter.column = column;
		filter.key = key;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter arrayOptionsFilter(String column, ArrayOptionsOperator operator, List<String> value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.ARRAYOPTIONS;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter stringObjectFilter(String column, String key, StringOperator operator, String value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.STRINGOBJECT;
		filter.column = column;
		filter.key = key;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter numberObjectFilter(String column, String key, NumberOperator operator, double value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.NUMBEROBJECT;
		filter.column = column;
		filter.key = key;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter booleanFilter(String column, BooleanOperator operator, boolean value) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.BOOLEAN;
		filter.column = column;
		filter.operator = operator.value();
		filter.value = value;
		return filter;
	}

	public static EvaluationRuleFilter nullFilter(String column, NullOperator operator) {
		var filter = new EvaluationRuleFilter();
		filter.type = TypeEnum.NULL;
		filter.column = column;
		filter.operator = operator.value();
		return filter;
	}

	@JsonProperty("type")
	public TypeEnum getType() {
		return type;
	}

	@JsonProperty("column")
	public String getColumn() {
		return column;
	}

	@JsonProperty("operator")
	public String getOperator() {
		return operator;
	}

	@JsonProperty("value")
	public Object getValue() {
		return value;
	}

	@JsonProperty("key")
	public String getKey() {
		return key;
	}
}
