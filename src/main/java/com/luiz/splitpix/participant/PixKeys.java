package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.BadRequestException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PixKeys {

	/** Matches participants.pix_key_value VARCHAR(200). */
	private static final int MAX_LENGTH = 200;

	// Pragmatic shapes, not DICT verification: enough to catch a key pasted
	// into the wrong field, a missing country code, or a CPF smuggled under
	// RANDOM (an EVP key is a UUID, so bare digits never match).
	private static final Pattern EMAIL_SHAPE =
			Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
	/** E.164: leading +, 8-15 digits, no zero country code. */
	private static final Pattern PHONE_SHAPE = Pattern.compile("\\+[1-9]\\d{7,14}");
	private static final Pattern RANDOM_SHAPE =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private PixKeys() {
	}

	/**
	 * Blank values count as absent. EMAIL keys are lowercased: Pix DICT treats
	 * email keys case-insensitively, so "Ana@X.com" and "ana@x.com" are the same
	 * key and must collide with unique_pix_key_per_group. RANDOM (EVP) keys are
	 * UUIDs and get the same treatment.
	 *
	 * The length is re-checked after normalization because lowercasing can grow
	 * a string (U+0130 becomes two code points), which would otherwise reach the
	 * column limit and surface as a 409 instead of a validation error.
	 */
	public static String normalize(PixKeyType pixKeyType, String pixKeyValue) {
		if (pixKeyValue == null || pixKeyValue.isBlank()) {
			return null;
		}
		String value = pixKeyValue.strip();
		if (pixKeyType == PixKeyType.EMAIL || pixKeyType == PixKeyType.RANDOM) {
			value = value.toLowerCase(Locale.ROOT);
		}
		if (value.length() > MAX_LENGTH) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
		return value;
	}

	/** Type and value must be given together or not at all (schema constraint valid_pix_key_pair). */
	public static void validatePair(PixKeyType pixKeyType, String normalizedValue) {
		if ((pixKeyType == null) != (normalizedValue == null)) {
			throw new BadRequestException("INVALID_PIX_KEY_PAIR");
		}
	}

	/** Call after {@link #validatePair}; a null pair is valid and skips the check. */
	public static void validateFormat(PixKeyType pixKeyType, String normalizedValue) {
		if (pixKeyType == null) {
			return;
		}
		boolean valid = switch (pixKeyType) {
			case EMAIL -> EMAIL_SHAPE.matcher(normalizedValue).matches();
			case PHONE -> PHONE_SHAPE.matcher(normalizedValue).matches();
			case RANDOM -> RANDOM_SHAPE.matcher(normalizedValue).matches();
		};
		if (!valid) {
			throw new BadRequestException("INVALID_PIX_KEY_FORMAT");
		}
	}

}
