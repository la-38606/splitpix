package com.luiz.splitpix.group;

import com.luiz.splitpix.participant.PixKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
		@NotBlank @Size(max = 120) String groupName,
		@NotBlank @Size(max = 100) String creatorName,
		PixKeyType pixKeyType,
		@Size(max = 200) String pixKeyValue) {
}
