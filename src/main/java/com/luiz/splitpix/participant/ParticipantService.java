package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.ConflictException;
import com.luiz.splitpix.group.GroupService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipantService {

	private final GroupService groupService;
	private final ParticipantRepository participantRepository;

	public ParticipantService(GroupService groupService, ParticipantRepository participantRepository) {
		this.groupService = groupService;
		this.participantRepository = participantRepository;
	}

	@Transactional
	public Participant add(UUID groupId, String inviteToken, AddParticipantRequest request) {
		groupService.requireGroup(groupId, inviteToken);

		String pixKeyValue = PixKeys.normalize(request.pixKeyValue());
		PixKeys.validatePair(request.pixKeyType(), pixKeyValue);

		if (pixKeyValue != null && participantRepository.pixKeyExistsInGroup(groupId, pixKeyValue)) {
			throw new ConflictException("DUPLICATE_PIX_KEY");
		}

		UUID participantId = UUID.randomUUID();
		participantRepository.insert(participantId, groupId, request.displayName().trim(),
				request.pixKeyType(), pixKeyValue);

		return new Participant(participantId, groupId, request.displayName().trim(),
				request.pixKeyType(), pixKeyValue, Instant.now());
	}

}
