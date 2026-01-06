package org.parasol.ai.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StringUtilsTests {
	@ParameterizedTest(name = "[{index}] {arguments}")
	@CsvSource(delimiter = '|', useHeadersInDisplayName = true, textBlock = """
		STRING_TO_SEARCH | SEARCH_STRING | CONTAINS_IGNORE_CASE
		Hello World      | World         | true
		Hello World      | world         | true
		Hello World      | HELLO         | true
		Hello World      | yellow        | false
		""")
	void containsIgnoreCase(String str, String searchStr, boolean containsIgnoreCase) {
		assertThat(StringUtils.containsIgnoreCase(str, searchStr))
			.isEqualTo(containsIgnoreCase);
	}
}