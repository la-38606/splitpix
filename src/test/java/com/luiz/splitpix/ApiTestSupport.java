package com.luiz.splitpix;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared base for API tests: one Spring context (and one Postgres container)
 * across all subclasses, plus the group/participant setup every feature needs.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ObjectMapper objectMapper;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	protected ResultActions postJson(String path, String json) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	protected JsonNode readBody(ResultActions actions) throws Exception {
		return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
	}

	protected JsonNode createGroup(String groupName, String creatorName) throws Exception {
		return readBody(postJson("/api/v1/groups", """
				{"groupName": "%s", "creatorName": "%s"}
				""".formatted(groupName, creatorName))
				.andExpect(status().isCreated()));
	}

	protected JsonNode createGroup() throws Exception {
		return createGroup("Rio Trip", "Luiz");
	}

	protected ResultActions postParticipant(String groupId, String token, String json) throws Exception {
		return mockMvc.perform(post("/api/v1/groups/" + groupId + "/participants")
				.param("token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	protected JsonNode addParticipant(String groupId, String token, String displayName) throws Exception {
		return readBody(postParticipant(groupId, token, """
				{"displayName": "%s"}
				""".formatted(displayName))
				.andExpect(status().isCreated()));
	}

	protected ResultActions getGroup(String groupId, String token) throws Exception {
		return mockMvc.perform(get("/api/v1/groups/" + groupId).param("token", token));
	}

	protected ResultActions postExpense(String groupId, String token, String idempotencyKey, String json)
			throws Exception {
		return mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
				.param("token", token)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	protected ResultActions getBalances(String groupId, String token) throws Exception {
		return mockMvc.perform(get("/api/v1/groups/" + groupId + "/balances").param("token", token));
	}

}
