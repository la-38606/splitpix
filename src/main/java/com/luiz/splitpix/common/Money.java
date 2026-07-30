package com.luiz.splitpix.common;

public final class Money {

	/**
	 * 10^12 centavos (R$ 10 bilhões), matching the schema CHECKs. Without a cap,
	 * absurd-but-valid amounts overflow the balance aggregate and poison every
	 * subsequent read of the group.
	 */
	public static final long MAX_AMOUNT_CENTS = 1_000_000_000_000L;

	private Money() {
	}

}
