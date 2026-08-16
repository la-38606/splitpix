package com.luiz.splitpix.common;

/**
 * Base for exceptions that map to an HTTP error response. The {@code code} is a
 * stable, machine-readable English identifier (docs/design.md section 4.9); the user-facing
 * message is resolved from the pt-BR message bundle, never stored here.
 */
public abstract class ApiException extends RuntimeException {

	private final String code;

	protected ApiException(String code) {
		super(code);
		this.code = code;
	}

	public String code() {
		return code;
	}

}
