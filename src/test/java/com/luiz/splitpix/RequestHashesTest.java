package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import com.luiz.splitpix.common.RequestHashes;
import org.junit.jupiter.api.Test;

/** The properties the idempotency-conflict check rests on. */
class RequestHashesTest {

	@Test
	void sameParts_hashEqually() {
		assertThat(RequestHashes.of("a", 1L, "b")).isEqualTo(RequestHashes.of("a", 1L, "b"));
	}

	@Test
	void adjacentFields_cannotShiftContentBetweenThemselves() {
		// The length prefix is the whole point: without it, ("ab","c") and
		// ("a","bc") would concatenate identically.
		assertThat(RequestHashes.of("ab", "c")).isNotEqualTo(RequestHashes.of("a", "bc"));
		assertThat(RequestHashes.of("1", "11")).isNotEqualTo(RequestHashes.of("11", "1"));
		assertThat(RequestHashes.of("a|b")).isNotEqualTo(RequestHashes.of("a", "b"));
	}

	@Test
	void nullIsNotTheStringNull() {
		assertThat(RequestHashes.of((Object) null)).isNotEqualTo(RequestHashes.of("null"));
	}

	@Test
	void fieldCountMatters() {
		assertThat(RequestHashes.of("a")).isNotEqualTo(RequestHashes.of("a", ""));
	}

}
