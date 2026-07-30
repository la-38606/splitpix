package com.luiz.splitpix.participant;

/**
 * Supported Pix key types. CPF is deliberately excluded (design doc 10.2):
 * storing a national identifier behind an invite-token access model is a
 * privacy liability with no upside.
 */
public enum PixKeyType {
	EMAIL,
	PHONE,
	RANDOM
}
