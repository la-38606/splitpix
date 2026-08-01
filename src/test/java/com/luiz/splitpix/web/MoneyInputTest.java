package com.luiz.splitpix.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.luiz.splitpix.common.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The form-field money parser. The one non-negotiable property: no input is
 * ever silently reinterpreted — "70.50" is R$ 70,50, never R$ 7.050,00.
 */
class MoneyInputTest {

	@Test
	void acceptsTheFormsPeopleActuallyType() {
		assertThat(MoneyInput.parseCents("420")).isEqualTo(42000L);
		assertThat(MoneyInput.parseCents("420,00")).isEqualTo(42000L);
		assertThat(MoneyInput.parseCents("70,5")).isEqualTo(7050L);
		assertThat(MoneyInput.parseCents("0,1")).isEqualTo(10L);
		assertThat(MoneyInput.parseCents("1.234,56")).isEqualTo(123456L);
		assertThat(MoneyInput.parseCents("1.234.567,89")).isEqualTo(123456789L);
		assertThat(MoneyInput.parseCents(" 12 ")).isEqualTo(1200L);
	}

	@Test
	void aLoneDotWithTwoDecimals_isADecimalPoint_notThousands() {
		// The bug this test exists for: "70.50" was read as R$ 7.050,00.
		assertThat(MoneyInput.parseCents("70.50")).isEqualTo(7050L);
		assertThat(MoneyInput.parseCents("0.5")).isEqualTo(50L);
	}

	@Test
	void dotGroupedThousands_withoutDecimals_areGrouping() {
		assertThat(MoneyInput.parseCents("1.234")).isEqualTo(123400L);
		assertThat(MoneyInput.parseCents("12.345.678")).isEqualTo(1234567800L);
	}

	@Test
	void ambiguousOrMalformedInput_isRejected_neverReinterpreted() {
		for (String input : new String[] {
				"", "  ", "abc", "1,2,3", "1.2.3", "1,234", "70.5.0",
				"12.34.56", "1.2345", "420,123", "-5", "+5", "R$ 10", "1e3", null }) {
			assertThatThrownBy(() -> MoneyInput.parseCents(input))
					.as("input: %s", input)
					.isInstanceOf(BadRequestException.class);
		}
	}

	@Test
	void amountsBeyondLong_areAValidationError_notA500() {
		assertThatThrownBy(() -> MoneyInput.parseCents("9".repeat(25)))
				.isInstanceOf(BadRequestException.class);
	}

}
