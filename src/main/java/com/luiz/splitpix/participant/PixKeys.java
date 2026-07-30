package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.BadRequestException;
import java.util.Locale;

public final class PixKeys {

	private PixKeys() {
	}

	/**
	 * Blank values count as absent. EMAIL keys are lowercased: Pix DICT treats
	 * email keys case-insensitively, so "Ana@X.com" and "ana@x.com" are the same
	 * key and must collide with unique_pix_key_per_group.
	 */
	public static String normalize(PixKeyType pixKeyType, String pixKeyValue) {
		if (pixKeyValue == null || pixKeyValue.isBlank()) {
			return null;
		}
		String value = pixKeyValue.strip();
		return pixKeyType == PixKeyType.EMAIL ? value.toLowerCase(Locale.ROOT) : value;
	}

	/** Type and value must be given together or not at all (schema constraint valid_pix_key_pair). */
	public static void validatePair(PixKeyType pixKeyType, String normalizedValue) {
		if ((pixKeyType == null) != (normalizedValue == null)) {
			throw new BadRequestException("INVALID_PIX_KEY_PAIR");
		}
	}

}
