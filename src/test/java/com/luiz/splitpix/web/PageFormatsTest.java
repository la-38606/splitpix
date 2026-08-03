package com.luiz.splitpix.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageFormatsTest {

	private final PageFormats formats = new PageFormats();

	@Test
	void amounts_useBrazilianGroupingAndComma() {
		assertThat(formats.amount(0)).isEqualTo("0,00");
		assertThat(formats.amount(5000)).isEqualTo("50,00");
		assertThat(formats.amount(123456789)).isEqualTo("1.234.567,89");
	}

	@Test
	void negativeAmounts_carryTheSign() {
		// The sign is data, not decoration: a debtor rendered positive reads
		// the direction of the debt backwards.
		assertThat(formats.amount(-5000)).isEqualTo("−50,00");
		assertThat(formats.amount(-123456)).isEqualTo("−1.234,56");
	}

	@Test
	void maskedKeys_neverLeakTheMiddle() {
		assertThat(formats.maskedKey("luiz@example.com")).isEqualTo("l•••@example.com");
		assertThat(formats.maskedKey("+5511999998888")).isEqualTo("•••8888");
		assertThat(formats.maskedKey("abc")).isEqualTo("•••");
		assertThat(formats.maskedKey(null)).isEmpty();
	}

}
