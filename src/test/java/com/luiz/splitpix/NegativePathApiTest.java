package com.luiz.splitpix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Pins the section 16 error mappings that no happy-path test exercises. */
class NegativePathApiTest extends ApiTestSupport {

	@Test
	void tokenOfAnotherGroup_returns403() throws Exception {
		JsonNode groupA = createGroup("Grupo A", "Ana");
		JsonNode groupB = createGroup("Grupo B", "Bruno");

		getGroup(groupA.get("groupId").asText(), groupB.get("inviteToken").asText())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void emptyToken_returns403() throws Exception {
		JsonNode group = createGroup();
		getGroup(group.get("groupId").asText(), "")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void missingTokenParameter_returns400() throws Exception {
		// Decision pinned: absent token is a malformed request (400), not 403.
		JsonNode group = createGroup();
		mockMvc.perform(get("/api/v1/groups/" + group.get("groupId").asText()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void addParticipantToUnknownGroup_returns404() throws Exception {
		postParticipant(UUID.randomUUID().toString(), "any", """
				{"displayName": "Ana"}
				""")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

	@Test
	void cpfPixKeyType_isRejected() throws Exception {
		// CPF is deliberately excluded from the enum (design doc 10.2).
		JsonNode group = createGroup();
		postParticipant(group.get("groupId").asText(), group.get("inviteToken").asText(), """
				{"displayName": "Ana", "pixKeyType": "CPF", "pixKeyValue": "12345678900"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void malformedJson_returns400() throws Exception {
		postJson("/api/v1/groups", "{not json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void nonUuidGroupId_returns400() throws Exception {
		mockMvc.perform(get("/api/v1/groups/not-a-uuid").param("token", "x"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void unsupportedMethod_returns405WithContract() throws Exception {
		mockMvc.perform(delete("/api/v1/groups/" + UUID.randomUUID()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	@Test
	void unsupportedMediaType_returns415WithContract() throws Exception {
		mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.TEXT_PLAIN)
				.content("hello"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void unknownPath_returns404WithContract() throws Exception {
		mockMvc.perform(get("/api/v1/nonexistent"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

}
