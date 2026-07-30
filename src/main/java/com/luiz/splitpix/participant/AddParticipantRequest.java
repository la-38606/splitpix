package com.luiz.splitpix.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddParticipantRequest(
		@NotBlank @Size(max = 100) String displayName,
		PixKeyType pixKeyType,
		@Size(max = 200) String pixKeyValue) {
}
