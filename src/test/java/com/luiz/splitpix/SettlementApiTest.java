package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class SettlementApiTest extends ApiTestSupport {

	private String groupId;
	private String token;
	private String luizId;
	private String anaId;

	/** Luiz pays 10000, split evenly: Ana owes 5000, Luiz is owed 5000. */
	@BeforeEach
	void setUpDebt() throws Exception {
		JsonNode group = createGroup("Jantar", "Luiz");
		groupId = group.get("groupId").asText();
		token = group.get("inviteToken").asText();
		luizId = group.get("creatorParticipantId").asText();
		anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "setup-expense", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 10000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 5000},
				    {"participantId": "%s", "amountCents": 5000}
				  ]
				}
				""".formatted(luizId, luizId, anaId))
				.andExpect(status().isCreated());
	}

	private Map<String, Long> balances() throws Exception {
		JsonNode body = readBody(getBalances(groupId, token).andExpect(status().isOk()));
		Map<String, Long> result = new HashMap<>();
		body.get("balances").forEach(b ->
				result.put(b.get("participantId").asText(), b.get("balanceCents").asLong()));
		return result;
	}

	@Test
	void completeSettlement_returns201_andZeroesBalances() throws Exception {
		postSettlement(groupId, token, "s-full", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.settlementId").isNotEmpty())
				.andExpect(jsonPath("$.payerParticipantId").value(anaId))
				.andExpect(jsonPath("$.recipientParticipantId").value(luizId))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.amountCents").value(5000));

		Map<String, Long> balances = balances();
		assertThat(balances.get(anaId)).isZero();
		assertThat(balances.get(luizId)).isZero();
	}

	@Test
	void partialSettlement_isAllowed() throws Exception {
		postSettlement(groupId, token, "s-partial", settlementJson(anaId, luizId, 3000))
				.andExpect(status().isCreated());

		Map<String, Long> balances = balances();
		assertThat(balances.get(anaId)).isEqualTo(-2000L);
		assertThat(balances.get(luizId)).isEqualTo(2000L);
	}

	@Test
	void replay_returns200WithSameSettlement() throws Exception {
		JsonNode first = readBody(postSettlement(groupId, token, "s-replay", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated()));

		JsonNode second = readBody(postSettlement(groupId, token, "s-replay", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isOk()));

		assertThat(second.get("settlementId").asText()).isEqualTo(first.get("settlementId").asText());
		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, groupId);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void overSettlement_returns409_andPersistsNothing() throws Exception {
		// Invariant 4: Ana owes 5000; paying 6000 would flip her to creditor.
		postSettlement(groupId, token, "s-over", settlementJson(anaId, luizId, 6000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, groupId);
		assertThat(rows).isZero();
	}

	@Test
	void payerWhoIsNotADebtor_returns409() throws Exception {
		// Invariant 5 direction: Luiz is a creditor; he cannot "settle" toward Ana.
		postSettlement(groupId, token, "s-wrong-dir", settlementJson(luizId, anaId, 1000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));
	}

	@Test
	void settlementAfterFullSettlement_returns409() throws Exception {
		postSettlement(groupId, token, "s-first", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		postSettlement(groupId, token, "s-second", settlementJson(anaId, luizId, 1))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));
	}

	@Test
	void recipientNotOwedEnough_returns409_evenWhenPayerOwesEnough() throws Exception {
		// Isolates invariant 5: Bia's balance is zero, so she cannot receive,
		// although Ana (payer) genuinely owes 5000.
		String biaId = addParticipant(groupId, token, "Bia").get("participantId").asText();

		postSettlement(groupId, token, "s-recipient", settlementJson(anaId, biaId, 1000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));
	}

	@Test
	void payerWhoOwesNothing_toAGenuineCreditor_returns409() throws Exception {
		// Isolates invariant 4: Luiz really is owed 5000, so the recipient-side
		// check passes; only the payer-side check can reject Bia, who owes
		// nothing and would become a creditor by "settling".
		String biaId = addParticipant(groupId, token, "Bia").get("participantId").asText();

		postSettlement(groupId, token, "s-nondebtor", settlementJson(biaId, luizId, 5000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, groupId);
		assertThat(rows).isZero();
	}

	@Test
	void replay_returnsBodyIdenticalToTheOriginal() throws Exception {
		String original = postSettlement(groupId, token, "s-identical", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String replay = postSettlement(groupId, token, "s-identical", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(replay).isEqualTo(original);
	}

	@Test
	void blankIdempotencyKey_returns400() throws Exception {
		postSettlement(groupId, token, "", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void overlongIdempotencyKey_returns400() throws Exception {
		postSettlement(groupId, token, "k".repeat(121), settlementJson(anaId, luizId, 5000))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void sameKeyInDifferentGroups_createsIndependentSettlements() throws Exception {
		JsonNode other = createGroup("Outro", "Zé");
		String otherGroupId = other.get("groupId").asText();
		String otherToken = other.get("inviteToken").asText();
		String otherLuiz = other.get("creatorParticipantId").asText();
		String otherAna = addParticipant(otherGroupId, otherToken, "Ana").get("participantId").asText();

		postExpense(otherGroupId, otherToken, "other-expense", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 2000,
				 "shares": [{"participantId": "%s", "amountCents": 2000}]}
				""".formatted(otherLuiz, otherAna))
				.andExpect(status().isCreated());

		JsonNode first = readBody(postSettlement(groupId, token, "shared-key",
				settlementJson(anaId, luizId, 5000)).andExpect(status().isCreated()));
		JsonNode second = readBody(postSettlement(otherGroupId, otherToken, "shared-key",
				settlementJson(otherAna, otherLuiz, 2000)).andExpect(status().isCreated()));

		assertThat(second.get("settlementId").asText()).isNotEqualTo(first.get("settlementId").asText());
		assertThat(second.get("amountCents").asLong()).isEqualTo(2000L);
	}

	@Test
	void replayWithDifferentBody_returns409() throws Exception {
		// Request hashing (14.3 / addendum 36.5): a key reused with a different
		// amount is a conflict — the browser back button is the common cause.
		postSettlement(groupId, token, "s-div", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		postSettlement(groupId, token, "s-div", settlementJson(anaId, luizId, 1234))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, groupId);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void amountAboveCap_returns400() throws Exception {
		postSettlement(groupId, token, "s-cap", settlementJson(anaId, luizId, 1_000_000_000_001L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_AMOUNT"));
	}

	@Test
	void missingAmount_returns400ValidationError() throws Exception {
		postSettlement(groupId, token, "s-noamount", """
				{"payerParticipantId": "%s", "recipientParticipantId": "%s"}
				""".formatted(anaId, luizId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void unknownGroup_returns404() throws Exception {
		postSettlement(java.util.UUID.randomUUID().toString(), token, "s-404",
				settlementJson(anaId, luizId, 5000))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

	@Test
	void mistakenSettlement_isCorrectedByACompensatingExpense() throws Exception {
		// Ana records paying Luiz 5000 by mistake (nothing was transferred).
		postSettlement(groupId, token, "s-mistake", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		// The ledger is append-only: an opposite settlement is NOT the fix —
		// Luiz owes nothing, so invariants 4/5 reject it.
		postSettlement(groupId, token, "s-wrong-fix", settlementJson(luizId, anaId, 5000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXCEEDS_DEBT"));

		// The working correction: a compensating expense paid by the wrongly
		// recorded recipient, fully assigned to the wrongly recorded payer.
		postExpense(groupId, token, "e-estorno", """
				{"description": "Estorno", "paidByParticipantId": "%s", "totalCents": 5000,
				 "shares": [{"participantId": "%s", "amountCents": 5000}]}
				""".formatted(luizId, anaId))
				.andExpect(status().isCreated());

		// Balances are back to the pre-mistake state: Ana owes her 5000 again.
		Map<String, Long> balances = balances();
		assertThat(balances.get(anaId)).isEqualTo(-5000L);
		assertThat(balances.get(luizId)).isEqualTo(5000L);
	}

	@Test
	void samePayerAndRecipient_returns400() throws Exception {
		postSettlement(groupId, token, "s-self", settlementJson(anaId, anaId, 1000))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_PARTICIPANTS"));
	}

	@Test
	void zeroOrNegativeAmount_returns400() throws Exception {
		postSettlement(groupId, token, "s-zero", settlementJson(anaId, luizId, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_AMOUNT"));

		postSettlement(groupId, token, "s-neg", settlementJson(anaId, luizId, -100))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_AMOUNT"));
	}

	@Test
	void participantFromAnotherGroup_returns400() throws Exception {
		JsonNode other = createGroup("Outro", "Zé");
		String outsiderId = other.get("creatorParticipantId").asText();

		postSettlement(groupId, token, "s-outsider", settlementJson(outsiderId, luizId, 1000))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_IN_GROUP"));
	}

	@Test
	void wrongToken_returns403() throws Exception {
		postSettlement(groupId, "wrong", "s-403", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void missingIdempotencyKeyHeader_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/groups/" + groupId + "/settlements")
				.param("token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(settlementJson(anaId, luizId, 5000)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

}
