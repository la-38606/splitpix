package com.luiz.splitpix.participant;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/participants")
public class ParticipantController {

	private final ParticipantService participantService;

	public ParticipantController(ParticipantService participantService) {
		this.participantService = participantService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ParticipantResponse add(@PathVariable UUID groupId, @RequestParam String token,
			@Valid @RequestBody AddParticipantRequest request) {
		return ParticipantResponse.from(participantService.add(groupId, token, request));
	}

}
