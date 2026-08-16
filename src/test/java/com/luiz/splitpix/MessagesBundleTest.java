package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/**
 * Section 16 says tests assert on codes, never on message text — which leaves
 * the bundle itself unguarded: a swapped or duplicated message is invisible to
 * every other test while telling users something false. This checks the bundle
 * as data (no Spring context needed).
 */
class MessagesBundleTest {

	/** Every code the application can emit. Adding a code means adding it here. */
	private static final List<String> ERROR_CODES = List.of(
			"GROUP_NOT_FOUND",
			"INVALID_INVITE_TOKEN",
			"DUPLICATE_PIX_KEY",
			"INVALID_PIX_KEY_PAIR",
			"INVALID_PIX_KEY_FORMAT",
			"VALIDATION_ERROR",
			"INVALID_REQUEST",
			"METHOD_NOT_ALLOWED",
			"UNSUPPORTED_MEDIA_TYPE",
			"RESOURCE_NOT_FOUND",
			"IDEMPOTENCY_KEY_REQUIRED",
			"INVALID_EXPENSE_TOTAL",
			"INVALID_EXPENSE_ALLOCATION",
			"INVALID_SHARE_AMOUNT",
			"PARTICIPANT_NOT_IN_GROUP",
			"DUPLICATE_SHARE_PARTICIPANT",
			"INVALID_SETTLEMENT_AMOUNT",
			"INVALID_SETTLEMENT_PARTICIPANTS",
			"SETTLEMENT_EXCEEDS_DEBT",
			"IDEMPOTENCY_CONFLICT",
			"UNSUPPORTED_OPTIMIZATION_SIZE",
			"NO_FEASIBLE_SETTLEMENT_PLAN",
			"INVALID_SETTLEMENT_CONSTRAINT",
			"CONSTRAINT_VIOLATION",
			"INTERNAL_ERROR");

	private final ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.of("pt", "BR"));

	@Test
	void everyErrorCodeResolvesToANonBlankMessage() {
		for (String code : ERROR_CODES) {
			assertThat(bundle.containsKey("error." + code)).as(code).isTrue();
			assertThat(bundle.getString("error." + code)).as(code).isNotBlank();
		}
	}

	@Test
	void messagesAreDistinct_soNoCodeCarriesAnotherCodesText() {
		Map<String, String> seen = new HashMap<>();
		for (String code : ERROR_CODES) {
			String message = bundle.getString("error." + code);
			assertThat(seen).as("%s duplicates the message of %s", code, seen.get(message))
					.doesNotContainKey(message);
			seen.put(message, code);
		}
	}

	@Test
	void bundleHasNoErrorKeysBeyondTheDeclaredCodes() {
		List<String> declared = ERROR_CODES.stream().map(code -> "error." + code).toList();
		assertThat(java.util.Collections.list(bundle.getKeys()).stream()
				.filter(key -> key.startsWith("error."))
				.toList())
				.as("an unreachable key is dead weight; a missing one fails at runtime")
				.containsExactlyInAnyOrderElementsOf(declared);
	}

	@Test
	void messagesArePortugueseAndCorrectlyEncoded() {
		for (String code : ERROR_CODES) {
			assertThat(bundle.getString("error." + code)).as(code).doesNotContain("Ã", "�");
		}
		// Spot-check that accented characters survive the properties encoding.
		assertThat(bundle.getString("error.GROUP_NOT_FOUND")).isEqualTo("Grupo não encontrado.");
	}

}
