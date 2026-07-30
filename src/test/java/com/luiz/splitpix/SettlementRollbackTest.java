package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.settlement.SettlementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Invariant 7 for the settlement transaction: the insert REALLY executes, then
 * the transaction fails — the committed state must contain no settlement row.
 * (Throwing instead of inserting would pass vacuously without any transaction.)
 */
class SettlementRollbackTest extends ApiTestSupport {

	@MockitoSpyBean
	private SettlementRepository settlementRepository;

	@Test
	void failureAfterRealInsert_leavesNoSettlementRow() throws Exception {
		JsonNode group = createGroup("Rollback", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "e1", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 5000,
				 "shares": [{"participantId": "%s", "amountCents": 5000}]}
				""".formatted(luizId, anaId))
				.andExpect(status().isCreated());

		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new RuntimeException("simulated failure after real insert");
		}).when(settlementRepository).insert(any(), any(), any(), any(), anyLong(), anyString());

		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE idempotency_key = 's1'", Integer.class);
		assertThat(rows).isZero();
	}

}
