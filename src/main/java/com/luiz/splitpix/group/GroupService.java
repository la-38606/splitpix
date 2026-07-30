package com.luiz.splitpix.group;

import com.luiz.splitpix.common.ForbiddenException;
import com.luiz.splitpix.common.NotFoundException;
import com.luiz.splitpix.common.Texts;
import com.luiz.splitpix.participant.ParticipantRepository;
import com.luiz.splitpix.participant.PixKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final GroupRepository groupRepository;
	private final ParticipantRepository participantRepository;

	public GroupService(GroupRepository groupRepository, ParticipantRepository participantRepository) {
		this.groupRepository = groupRepository;
		this.participantRepository = participantRepository;
	}

	@Transactional
	public CreateGroupResult create(CreateGroupRequest request) {
		String groupName = Texts.cleanName(request.groupName());
		String creatorName = Texts.cleanName(request.creatorName());
		String pixKeyValue = PixKeys.normalize(request.pixKeyType(), request.pixKeyValue());
		PixKeys.validatePair(request.pixKeyType(), pixKeyValue);

		UUID groupId = UUID.randomUUID();
		UUID creatorId = UUID.randomUUID();
		String inviteToken = newInviteToken();

		groupRepository.insert(groupId, groupName, inviteToken);
		participantRepository.insert(creatorId, groupId, creatorName, request.pixKeyType(), pixKeyValue);

		return new CreateGroupResult(groupId, inviteToken, creatorId);
	}

	/** Group plus participants in one transaction: one token check, one consistent snapshot. */
	@Transactional(readOnly = true)
	public GroupView getView(UUID groupId, String inviteToken) {
		Group group = requireGroup(groupId, inviteToken);
		return new GroupView(group, participantRepository.findByGroupId(groupId));
	}

	/**
	 * Loads the group and validates the invite token — the access check every
	 * group-scoped operation goes through (constant-time comparison).
	 */
	public Group requireGroup(UUID groupId, String inviteToken) {
		Group group = groupRepository.findById(groupId)
				.orElseThrow(() -> new NotFoundException("GROUP_NOT_FOUND"));
		byte[] expected = group.inviteToken().getBytes(StandardCharsets.UTF_8);
		byte[] provided = (inviteToken == null ? "" : inviteToken).getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, provided)) {
			throw new ForbiddenException("INVALID_INVITE_TOKEN");
		}
		return group;
	}

	/** 256 bits from SecureRandom, base64url — above the 128-bit floor set in addendum 35.6. */
	private static String newInviteToken() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
