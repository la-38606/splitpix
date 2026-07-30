package com.luiz.splitpix.activity;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups/{groupId}/activity")
public class ActivityController {

	private final ActivityService activityService;

	public ActivityController(ActivityService activityService) {
		this.activityService = activityService;
	}

	@GetMapping
	public ActivityResponse get(@PathVariable UUID groupId, @RequestParam String token) {
		return new ActivityResponse(groupId, activityService.getActivity(groupId, token));
	}

	public record ActivityResponse(UUID groupId, List<ActivityItem> items) {
	}

}
