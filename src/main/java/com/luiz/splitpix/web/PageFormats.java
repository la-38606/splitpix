package com.luiz.splitpix.web;

import org.springframework.stereotype.Component;

/** Formatting helpers for the templates, referenced as {@code @pageFormats}. */
@Component("pageFormats")
public class PageFormats {

	/** Centavos as Brazilian currency, without the R$ prefix (templates place it). */
	public String amount(long cents) {
		long absolute = Math.abs(cents);
		String reais = String.valueOf(absolute / 100);
		StringBuilder grouped = new StringBuilder();
		for (int i = 0; i < reais.length(); i++) {
			if (i > 0 && (reais.length() - i) % 3 == 0) {
				grouped.append('.');
			}
			grouped.append(reais.charAt(i));
		}
		return "%s%s,%02d".formatted(cents < 0 ? "−" : "", grouped, absolute % 100);
	}

	/**
	 * Partially masks a Pix key. Section 22 keeps keys inside their group; the
	 * participant list only needs to show which key is on file, so the full
	 * value appears exclusively on the payment instruction.
	 */
	public String maskedKey(String key) {
		if (key == null || key.isBlank()) {
			return "";
		}
		int at = key.indexOf('@');
		if (at > 0) {
			return key.charAt(0) + "•••" + key.substring(at);
		}
		if (key.length() <= 4) {
			return "•••";
		}
		return "•••" + key.substring(key.length() - 4);
	}

}
