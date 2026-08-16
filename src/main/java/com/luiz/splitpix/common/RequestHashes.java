package com.luiz.splitpix.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes the meaningful content of an idempotent write so a replayed key can be
 * checked against the request it originally applied (ADR 0003).
 *
 * Without it, using the back button to edit a submitted form and resubmitting
 * sends the same key with different content, and the server silently returns
 * the first record — the user's correction is lost while the response says the
 * write succeeded.
 */
public final class RequestHashes {

	private RequestHashes() {
	}

	/** SHA-256 over the parts, length-prefixed so no two field sets can collide. */
	public static String of(Object... parts) {
		StringBuilder canonical = new StringBuilder();
		for (Object part : parts) {
			// NUL is unreachable from real field values, so null can never
			// collide with the four-character string "null".
			String value = part == null ? "\u0000" : String.valueOf(part);
			canonical.append(value.length()).append(':').append(value).append('|');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}
	}

}
