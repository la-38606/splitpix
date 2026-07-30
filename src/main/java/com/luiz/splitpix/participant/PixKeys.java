package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.BadRequestException;

public final class PixKeys {

	private PixKeys() {
	}

	/** Blank values count as absent. */
	public static String normalize(String pixKeyValue) {
		return pixKeyValue == null || pixKeyValue.isBlank() ? null : pixKeyValue.trim();
	}

	/** Type and value must be given together or not at all (schema constraint valid_pix_key_pair). */
	public static void validatePair(PixKeyType pixKeyType, String normalizedValue) {
		if ((pixKeyType == null) != (normalizedValue == null)) {
			throw new BadRequestException("INVALID_PIX_KEY_PAIR");
		}
	}

}
