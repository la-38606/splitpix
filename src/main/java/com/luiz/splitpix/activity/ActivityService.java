package com.luiz.splitpix.activity;

import com.luiz.splitpix.group.GroupService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

	private final GroupService groupService;
	private final ActivityRepository activityRepository;

	public ActivityService(GroupService groupService, ActivityRepository activityRepository) {
		this.groupService = groupService;
		this.activityRepository = activityRepository;
	}

	@Transactional(readOnly = true)
	public List<ActivityItem> getActivity(UUID groupId, String inviteToken) {
		groupService.requireGroup(groupId, inviteToken);
		return activityRepository.findByGroupId(groupId);
	}

}
