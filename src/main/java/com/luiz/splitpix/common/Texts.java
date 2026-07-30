package com.luiz.splitpix.common;

public final class Texts {

	private Texts() {
	}

	/**
	 * Strips a display/group name and rejects values with no visible character.
	 * Catches what @NotBlank misses: Character.isWhitespace excludes non-breaking
	 * spaces, so a U+00A0-only name passes bean validation but is visually blank.
	 */
	public static String cleanName(String raw) {
		String name = raw.strip();
		boolean hasVisible = name.codePoints()
				.anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
		if (!hasVisible) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
		return name;
	}

}
