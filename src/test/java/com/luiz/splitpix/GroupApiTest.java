package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private JsonNode createGroup() throws Exception {
		var result = mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "groupName": "Rio Trip",
						  "creatorName": "Luiz",
						  "pixKeyType": "EMAIL",
						  "pixKeyValue": "luiz@example.com"
						}
						"""))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void createGroup_returnsIdsAndInviteToken() throws Exception {
		JsonNode body = createGroup();
		UUID.fromString(body.get("groupId").asText());
		UUID.fromString(body.get("creatorParticipantId").asText());
		// 22 base64url chars ≈ the 128-bit entropy floor from addendum 35.6.
		assertThat(body.get("inviteToken").asText().length()).isGreaterThanOrEqualTo(22);
	}

	@Test
	void createdGroup_isReadableWithToken_andListsCreator() throws Exception {
		JsonNode created = createGroup();
		mockMvc.perform(get("/api/v1/groups/" + created.get("groupId").asText())
				.param("token", created.get("inviteToken").asText()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Rio Trip"))
				.andExpect(jsonPath("$.participants.length()").value(1))
				.andExpect(jsonPath("$.participants[0].displayName").value("Luiz"))
				.andExpect(jsonPath("$.participants[0].pixKeyValue").value("luiz@example.com"));
	}

	@Test
	void getGroup_withWrongToken_returns403() throws Exception {
		JsonNode created = createGroup();
		mockMvc.perform(get("/api/v1/groups/" + created.get("groupId").asText())
				.param("token", "wrong-token"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void getGroup_unknownGroup_returns404() throws Exception {
		mockMvc.perform(get("/api/v1/groups/" + UUID.randomUUID())
				.param("token", "any"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

	@Test
	void createGroup_blankName_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "  ", "creatorName": "Luiz"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void createGroup_pixTypeWithoutValue_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "Rio Trip", "creatorName": "Luiz", "pixKeyType": "EMAIL"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PIX_KEY_PAIR"));
	}

}
