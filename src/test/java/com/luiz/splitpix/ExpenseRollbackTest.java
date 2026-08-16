package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.expense.ExpenseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Invariant 7 for the expense transaction (design doc 13.1): the expense insert
 * really executes, then share insertion fails — the whole transaction rolls
 * back and no expense row survives.
 */
class ExpenseRollbackTest extends ApiTestSupport {

	@MockitoSpyBean
	private ExpenseRepository expenseRepository;

	@Test
	void failedShareInsert_leavesNoExpenseRow() throws Exception {
		JsonNode group = createGroup("Rollback", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();

		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new RuntimeException("simulated failure after real share insert");
		}).when(expenseRepository).insertShares(any(), any(), anyList());

		postExpense(groupId, token, "rollback-1", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 100,
				  "shares": [{"participantId": "%s", "amountCents": 100}]
				}
				""".formatted(luizId, luizId))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer orphanExpenses = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE idempotency_key = 'rollback-1'", Integer.class);
		assertThat(orphanExpenses).isZero();
	}

}
