package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.expense.ExpenseRepository;
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

/**
 * Invariant 7 for the expense transaction (design doc 13.1): if share insertion
 * fails after the expense insert, the whole transaction rolls back and no
 * expense row survives.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExpenseRollbackTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private ExpenseRepository expenseRepository;

	@Test
	void failedShareInsert_leavesNoExpenseRow() throws Exception {
		var created = mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "Rollback", "creatorName": "Luiz"}
						"""))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode group = objectMapper.readTree(created);

		doThrow(new RuntimeException("simulated share insert failure"))
				.when(expenseRepository).insertShares(any(), anyList());

		mockMvc.perform(post("/api/v1/groups/" + group.get("groupId").asText() + "/expenses")
				.param("token", group.get("inviteToken").asText())
				.header("Idempotency-Key", "rollback-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "description": "Jantar",
						  "paidByParticipantId": "%s",
						  "totalCents": 100,
						  "shares": [{"participantId": "%s", "amountCents": 100}]
						}
						""".formatted(group.get("creatorParticipantId").asText(),
						group.get("creatorParticipantId").asText())))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer orphanExpenses = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE idempotency_key = 'rollback-1'", Integer.class);
		assertThat(orphanExpenses).isZero();
	}

}
