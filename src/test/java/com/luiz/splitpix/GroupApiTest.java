package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class GroupApiTest extends ApiTestSupport {

	@Test
	void createGroup_returnsIdsAndInviteToken() throws Exception {
		JsonNode body = readBody(postJson("/api/v1/groups", """
				{
				  "groupName": "Rio Trip",
				  "creatorName": "Luiz",
				  "pixKeyType": "EMAIL",
				  "pixKeyValue": "luiz@example.com"
				}
				""").andExpect(status().isCreated())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON)));
		UUID.fromString(body.get("groupId").asText());
		UUID.fromString(body.get("creatorParticipantId").asText());
		// 22 base64url chars ≈ the 128-bit entropy floor (docs/design.md section 4.7).
		assertThat(body.get("inviteToken").asText().length()).isGreaterThanOrEqualTo(22);
	}

	@Test
	void inviteTokens_areUnpredictable() throws Exception {
		// The token is the only credential in the system: it must be distinct
		// per group and carry real entropy, not a counter or a seeded PRNG.
		Set<String> tokens = new HashSet<>();
		for (int i = 0; i < 50; i++) {
			String token = createGroup().get("inviteToken").asText();
			assertThat(Base64.getUrlDecoder().decode(token).length)
					.as("token entropy in bytes")
					.isGreaterThanOrEqualTo(16);
			tokens.add(token);
		}
		assertThat(tokens).hasSize(50);
	}

	@Test
	void createdGroup_isReadableWithToken_andListsCreator() throws Exception {
		JsonNode created = createGroup();
		getGroup(created.get("groupId").asText(), created.get("inviteToken").asText())
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.name").value("Rio Trip"))
				.andExpect(jsonPath("$.participants.length()").value(1))
				.andExpect(jsonPath("$.participants[0].displayName").value("Luiz"));
	}

	@Test
	void participants_areListedInInsertionOrder_creatorFirst() throws Exception {
		JsonNode created = createGroup();
		String groupId = created.get("groupId").asText();
		String token = created.get("inviteToken").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();
		String brunoId = addParticipant(groupId, token, "Bruno").get("participantId").asText();

		getGroup(groupId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.participants.length()").value(3))
				.andExpect(jsonPath("$.participants[0].participantId")
						.value(created.get("creatorParticipantId").asText()))
				.andExpect(jsonPath("$.participants[1].participantId").value(anaId))
				.andExpect(jsonPath("$.participants[2].participantId").value(brunoId));
	}

	@Test
	void getGroup_doesNotEchoInviteToken() throws Exception {
		JsonNode created = createGroup();
		String body = getGroup(created.get("groupId").asText(), created.get("inviteToken").asText())
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(created.get("inviteToken").asText());
	}

	@Test
	void getGroup_withWrongToken_returns403() throws Exception {
		JsonNode created = createGroup();
		getGroup(created.get("groupId").asText(), "wrong-token")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void getGroup_unknownGroup_returns404_withPtBrMessage() throws Exception {
		JsonNode body = readBody(getGroup(UUID.randomUUID().toString(), "any")
				.andExpect(status().isNotFound())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND")));
		// Non-empty and no mojibake: an encoding regression turns "não" into "nÃ£o".
		assertThat(body.get("message").asText())
				.isNotBlank()
				.doesNotContain("Ã", "�");
	}

	@Test
	void createGroup_blankName_returns400() throws Exception {
		postJson("/api/v1/groups", """
				{"groupName": "  ", "creatorName": "Luiz"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void createGroup_pixTypeWithoutValue_returns400() throws Exception {
		postJson("/api/v1/groups", """
				{"groupName": "Rio Trip", "creatorName": "Luiz", "pixKeyType": "EMAIL"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PIX_KEY_PAIR"));
	}

	@Test
	void groupName_isCanonicalizedBeforeLengthValidation() throws Exception {
		// 120 meaningful chars + trailing newline = 121 raw; stored value fits.
		String name = "x".repeat(120);
		postJson("/api/v1/groups", """
				{"groupName": "%s\\n", "creatorName": "Luiz"}
				""".formatted(name))
				.andExpect(status().isCreated());

		postJson("/api/v1/groups", """
				{"groupName": "%s", "creatorName": "Luiz"}
				""".formatted("x".repeat(121)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

}
