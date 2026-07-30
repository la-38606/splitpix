package com.luiz.splitpix.group;

import com.luiz.splitpix.participant.ParticipantResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {

	private final GroupService groupService;

	public GroupController(GroupService groupService) {
		this.groupService = groupService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CreateGroupResult create(@Valid @RequestBody CreateGroupRequest request) {
		return groupService.create(request);
	}

	@GetMapping("/{groupId}")
	public GroupResponse get(@PathVariable UUID groupId, @RequestParam String token) {
		GroupView view = groupService.getView(groupId, token);
		return new GroupResponse(view.group().id(), view.group().name(),
				view.participants().stream().map(ParticipantResponse::from).toList());
	}

}
