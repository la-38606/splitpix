package com.luiz.splitpix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ParticipantApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String groupId;
	private String token;

	@BeforeEach
	void createGroup() throws Exception {
		var result = mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "República", "creatorName": "Luiz"}
						"""))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		groupId = body.get("groupId").asText();
		token = body.get("inviteToken").asText();
	}

	private ResultActions addParticipant(String json) throws Exception {
		return mockMvc.perform(post("/api/v1/groups/" + groupId + "/participants")
				.param("token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	@Test
	void addParticipant_returns201WithParticipant() throws Exception {
		addParticipant("""
				{"displayName": "Ana", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.participantId").isNotEmpty())
				.andExpect(jsonPath("$.displayName").value("Ana"))
				.andExpect(jsonPath("$.pixKeyType").value("PHONE"));
	}

	@Test
	void addParticipant_duplicatePixKeyInGroup_returns409() throws Exception {
		addParticipant("""
				{"displayName": "Ana", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""").andExpect(status().isCreated());

		addParticipant("""
				{"displayName": "Bruno", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PIX_KEY"));
	}

	@Test
	void addParticipant_withoutPixKey_isAllowed() throws Exception {
		addParticipant("""
				{"displayName": "Clara"}
				""").andExpect(status().isCreated());
	}

	@Test
	void addParticipant_valueWithoutType_returns400() throws Exception {
		addParticipant("""
				{"displayName": "Diego", "pixKeyValue": "diego@example.com"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PIX_KEY_PAIR"));
	}

	@Test
	void addParticipant_wrongToken_returns403() throws Exception {
		mockMvc.perform(post("/api/v1/groups/" + groupId + "/participants")
				.param("token", "wrong")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName": "Eva"}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void addParticipant_blankName_returns400() throws Exception {
		addParticipant("""
				{"displayName": ""}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

}
