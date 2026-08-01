package com.luiz.splitpix.web;

import com.luiz.splitpix.common.BadRequestException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Parses what a person types into an amount field into centavos.
 *
 * Accepted: "420" (integer reais), "420,00" / "70,5" / "1.234,56" (Brazilian,
 * comma decimal with optional dot grouping), "70.50" (a lone dot followed by
 * one or two digits is a decimal point), "1.234" (dot-grouped thousands).
 * Anything else — three decimals, mixed or repeated separators, signs, text —
 * is a validation error, never a reinterpretation: in a money field a rejected
 * amount is recoverable, a misread one is not.
 */
final class MoneyInput {

	private static final Pattern COMMA_DECIMAL =
			Pattern.compile("(\\d+|\\d{1,3}(\\.\\d{3})+),\\d{1,2}");
	private static final Pattern DOT_DECIMAL = Pattern.compile("\\d+\\.\\d{1,2}");
	private static final Pattern DOT_GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+");
	private static final Pattern PLAIN = Pattern.compile("\\d+");

	private MoneyInput() {
	}

	static long parseCents(String raw) {
		String value = raw == null ? "" : raw.strip().replace(" ", "");
		String canonical;
		if (COMMA_DECIMAL.matcher(value).matches()) {
			canonical = value.replace(".", "").replace(',', '.');
		}
		else if (DOT_DECIMAL.matcher(value).matches()) {
			canonical = value;
		}
		else if (DOT_GROUPED.matcher(value).matches()) {
			canonical = value.replace(".", "");
		}
		else if (PLAIN.matcher(value).matches()) {
			canonical = value;
		}
		else {
			throw new BadRequestException("VALIDATION_ERROR");
		}
		try {
			return new BigDecimal(canonical).movePointRight(2).longValueExact();
		}
		catch (ArithmeticException e) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
	}

}
