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
		List<ActivityItem> items = activityService.getActivity(groupId, token);
		// The list is the complete ledger, so the revision is simply the last
		// sequence number — no second query that could see a different state.
		long revision = items.isEmpty() ? 0 : items.getLast().sequence();
		return new ActivityResponse(groupId, revision, items);
	}

	public record ActivityResponse(UUID groupId, long ledgerRevision, List<ActivityItem> items) {
	}

}
