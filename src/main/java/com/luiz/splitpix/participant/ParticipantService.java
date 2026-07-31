package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.ConflictException;
import com.luiz.splitpix.common.Texts;
import com.luiz.splitpix.group.GroupRepository;
import com.luiz.splitpix.group.GroupService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipantService {

	private final GroupService groupService;
	private final GroupRepository groupRepository;
	private final ParticipantRepository participantRepository;

	public ParticipantService(GroupService groupService, GroupRepository groupRepository,
			ParticipantRepository participantRepository) {
		this.groupService = groupService;
		this.groupRepository = groupRepository;
		this.participantRepository = participantRepository;
	}

	@Transactional
	public Participant add(UUID groupId, String inviteToken, AddParticipantRequest request) {
		groupService.requireGroup(groupId, inviteToken);

		// The group lock (13.3) is taken here too. The FK check on group_id
		// already serialized this path against expense and settlement writes as
		// a side effect; taking the lock explicitly makes that property visible
		// and survives any future change to the foreign key.
		groupRepository.lockById(groupId);

		String displayName = Texts.cleanName(request.displayName());
		String pixKeyValue = PixKeys.normalize(request.pixKeyType(), request.pixKeyValue());
		PixKeys.validatePair(request.pixKeyType(), pixKeyValue);

		if (pixKeyValue != null && participantRepository.pixKeyExistsInGroup(groupId, pixKeyValue)) {
			throw new ConflictException("DUPLICATE_PIX_KEY");
		}

		UUID participantId = UUID.randomUUID();
		Instant createdAt;
		try {
			createdAt = participantRepository.insert(participantId, groupId, displayName,
					request.pixKeyType(), pixKeyValue);
		}
		catch (DuplicateKeyException e) {
			// Loser of a concurrent same-key race: the pre-check passed for both
			// requests, the unique constraint caught it — same contract code.
			throw new ConflictException("DUPLICATE_PIX_KEY");
		}

		return new Participant(participantId, groupId, displayName,
				request.pixKeyType(), pixKeyValue, createdAt);
	}

}
