package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.settlement.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Invariant 7 for the settlement transaction: a failed insert leaves no row. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SettlementRollbackTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private SettlementRepository settlementRepository;

	@Test
	void failedInsert_leavesNoSettlementRow() throws Exception {
		var groupBody = mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "Rollback", "creatorName": "Luiz"}
						"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode group = objectMapper.readTree(groupBody);
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();

		var anaBody = mockMvc.perform(post("/api/v1/groups/" + groupId + "/participants")
				.param("token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName": "Ana"}
						"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String anaId = objectMapper.readTree(anaBody).get("participantId").asText();

		mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
				.param("token", token)
				.header("Idempotency-Key", "e1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 5000,
						 "shares": [{"participantId": "%s", "amountCents": 5000}]}
						""".formatted(luizId, anaId)))
				.andExpect(status().isCreated());

		doThrow(new RuntimeException("simulated settlement insert failure"))
				.when(settlementRepository).insert(any(), any(), any(), any(), anyLong(), anyString());

		mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
				.param("token", token)
				.header("Idempotency-Key", "s1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"payerParticipantId": "%s", "recipientParticipantId": "%s", "amountCents": 5000}
						""".formatted(anaId, luizId)))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE idempotency_key = 's1'", Integer.class);
		assertThat(rows).isZero();
	}

}
