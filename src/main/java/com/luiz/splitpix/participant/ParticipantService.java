package com.luiz.splitpix.participant;

import com.luiz.splitpix.common.ConflictException;
import com.luiz.splitpix.common.Texts;
import com.luiz.splitpix.group.GroupService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
