package com.luiz.splitpix.common;

public final class Texts {

	private Texts() {
	}

	/**
	 * Strips a display/group/expense name and rejects values with no visible
	 * character. Catches what @NotBlank misses: Character.isWhitespace excludes
	 * non-breaking spaces, so a U+00A0-only name passes bean validation while
	 * being visually blank. Control and format characters (ANSI escapes, bidi
	 * overrides, zero-width joiners) are rejected outright — they render as
	 * nothing, or actively lie, in the terminal output of demo.sh and in any UI.
	 */
	public static String cleanName(String raw) {
		String name = raw.strip();
		boolean hasVisible = false;
		for (int i = 0; i < name.length();) {
			int cp = name.codePointAt(i);
			i += Character.charCount(cp);
			int type = Character.getType(cp);
			if (type == Character.CONTROL || type == Character.FORMAT
					|| type == Character.PRIVATE_USE || type == Character.UNASSIGNED) {
				throw new BadRequestException("VALIDATION_ERROR");
			}
			if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
				hasVisible = true;
			}
		}
		if (!hasVisible) {
			throw new BadRequestException("VALIDATION_ERROR");
		}
		return name;
	}

}
