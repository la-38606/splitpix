package com.luiz.splitpix.group;

import com.luiz.splitpix.participant.PixKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
		@NotBlank @Size(max = 120) String groupName,
		@NotBlank @Size(max = 100) String creatorName,
		PixKeyType pixKeyType,
		@Size(max = 200) String pixKeyValue) {

	// Canonicalize before validation runs: @Size must see what will be stored,
	// not raw input (a 120-char name with a trailing newline is valid).
	public CreateGroupRequest {
		groupName = groupName == null ? null : groupName.strip();
		creatorName = creatorName == null ? null : creatorName.strip();
		pixKeyValue = pixKeyValue == null ? null : pixKeyValue.strip();
	}

}
