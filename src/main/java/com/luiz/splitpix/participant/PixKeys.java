package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.BadRequestException;
import java.util.Locale;

public final class PixKeys {

	/** Matches participants.pix_key_value VARCHAR(200). */
	private static final int MAX_LENGTH = 200;

	private PixKeys() {
	}

	/**
	 * Blank values count as absent. EMAIL keys are lowercased: Pix DICT treats
	 * email keys case-insensitively, so "Ana@X.com" and "ana@x.com" are the same
	 * key and must collide with unique_pix_key_per_group.
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
		if (pixKeyType == PixKeyType.EMAIL) {
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

}
