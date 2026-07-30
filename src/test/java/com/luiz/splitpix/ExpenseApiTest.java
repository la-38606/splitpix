package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class ExpenseApiTest extends ApiTestSupport {

	private String groupId;
	private String token;
	private String luizId;
	private String anaId;
	private String brunoId;

	@BeforeEach
	void setUpGroup() throws Exception {
		JsonNode group = createGroup("Jantar", "Luiz");
		groupId = group.get("groupId").asText();
		token = group.get("inviteToken").asText();
		luizId = group.get("creatorParticipantId").asText();
		anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();
		brunoId = addParticipant(groupId, token, "Bruno").get("participantId").asText();
	}

	private String expenseJson(long total, long shareLuiz, long shareAna, long shareBruno) {
		return """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": %d,
				  "shares": [
				    {"participantId": "%s", "amountCents": %d},
				    {"participantId": "%s", "amountCents": %d},
				    {"participantId": "%s", "amountCents": %d}
				  ]
				}
				""".formatted(luizId, total, luizId, shareLuiz, anaId, shareAna, brunoId, shareBruno);
	}

	@Test
	void createExpense_returns201WithShares() throws Exception {
		postExpense(groupId, token, "exp-001", expenseJson(30000, 10000, 12000, 8000))
				.andExpect(status().isCreated())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.expenseId").isNotEmpty())
				.andExpect(jsonPath("$.totalCents").value(30000))
				.andExpect(jsonPath("$.shares.length()").value(3))
				.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void sameIdempotencyKey_replaysExistingExpense() throws Exception {
		JsonNode first = readBody(postExpense(groupId, token, "exp-replay", expenseJson(30000, 10000, 12000, 8000))
				.andExpect(status().isCreated()));

		JsonNode second = readBody(postExpense(groupId, token, "exp-replay", expenseJson(30000, 10000, 12000, 8000))
				.andExpect(status().isOk()));

		assertThat(second.get("expenseId").asText()).isEqualTo(first.get("expenseId").asText());

		Integer expenseCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE group_id = ?::uuid AND idempotency_key = 'exp-replay'",
				Integer.class, groupId);
		assertThat(expenseCount).isEqualTo(1);
	}

	@Test
	void sameKeyDifferentBody_returnsOriginalExpense() throws Exception {
		// Section 14.1: replay returns the already-created expense; request
		// hashing (14.3) is a stretch goal, so the second body is ignored.
		readBody(postExpense(groupId, token, "exp-original", expenseJson(30000, 10000, 12000, 8000))
				.andExpect(status().isCreated()));

		JsonNode replay = readBody(postExpense(groupId, token, "exp-original", expenseJson(500, 500, 0, 0))
				.andExpect(status().isOk()));

		assertThat(replay.get("totalCents").asLong()).isEqualTo(30000L);
		assertThat(replay.get("shares").size()).isEqualTo(3);
	}

	@Test
	void sameKeyInvalidSecondBody_stillReplays() throws Exception {
		postExpense(groupId, token, "exp-invalid-replay", expenseJson(30000, 10000, 12000, 8000))
				.andExpect(status().isCreated());

		// Replay lookup runs before validation: an invalid retry of an already
		// -applied request must not error.
		postExpense(groupId, token, "exp-invalid-replay", expenseJson(-5, 0, 0, 0))
				.andExpect(status().isOk());
	}

	@Test
	void sameKeyInDifferentGroups_createsIndependentExpenses() throws Exception {
		JsonNode other = createGroup("Outro", "Zé");
		String otherGroupId = other.get("groupId").asText();
		String otherToken = other.get("inviteToken").asText();
		String otherCreator = other.get("creatorParticipantId").asText();

		JsonNode first = readBody(postExpense(groupId, token, "shared-key", expenseJson(100, 100, 0, 0))
				.andExpect(status().isCreated()));

		JsonNode second = readBody(postExpense(otherGroupId, otherToken, "shared-key", """
				{
				  "description": "Outro jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 200,
				  "shares": [{"participantId": "%s", "amountCents": 200}]
				}
				""".formatted(otherCreator, otherCreator))
				.andExpect(status().isCreated()));

		assertThat(second.get("expenseId").asText()).isNotEqualTo(first.get("expenseId").asText());
	}

	@Test
	void overAllocatedShares_returns400() throws Exception {
		postExpense(groupId, token, "exp-over", expenseJson(30000, 10000, 12000, 8001))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPENSE_ALLOCATION"));
	}

	@Test
	void totalAboveCap_returns400() throws Exception {
		postExpense(groupId, token, "exp-huge", expenseJson(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPENSE_TOTAL"));
	}

	@Test
	void blankIdempotencyKey_returns400() throws Exception {
		postExpense(groupId, token, "", expenseJson(100, 100, 0, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void overlongIdempotencyKey_returns400() throws Exception {
		postExpense(groupId, token, "k".repeat(121), expenseJson(100, 100, 0, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void malformedBodies_return400ValidationError() throws Exception {
		String[] payloads = {
				// null totalCents
				"""
				{"description": "d", "paidByParticipantId": "%s", "shares": [{"participantId": "%s", "amountCents": 1}]}
				""".formatted(luizId, luizId),
				// empty shares list
				"""
				{"description": "d", "paidByParticipantId": "%s", "totalCents": 100, "shares": []}
				""".formatted(luizId),
				// null share amount
				"""
				{"description": "d", "paidByParticipantId": "%s", "totalCents": 100, "shares": [{"participantId": "%s"}]}
				""".formatted(luizId, luizId),
				// over-long description
				"""
				{"description": "%s", "paidByParticipantId": "%s", "totalCents": 100, "shares": [{"participantId": "%s", "amountCents": 100}]}
				""".formatted("d".repeat(201), luizId, luizId),
		};
		for (String payload : payloads) {
			postExpense(groupId, token, "exp-malformed", payload)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
	}

	@Test
	void nonBreakingSpaceDescription_returns400() throws Exception {
		postExpense(groupId, token, "exp-nbsp", """
				{"description": "\\u00a0", "paidByParticipantId": "%s", "totalCents": 100,
				 "shares": [{"participantId": "%s", "amountCents": 100}]}
				""".formatted(luizId, luizId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void sharesNotSummingToTotal_returns400_andPersistsNothing() throws Exception {
		postExpense(groupId, token, "exp-bad-sum", expenseJson(30000, 10000, 12000, 7999))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPENSE_ALLOCATION"));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE idempotency_key = 'exp-bad-sum'", Integer.class);
		assertThat(count).isZero();
	}

	@Test
	void zeroOrNegativeTotal_returns400() throws Exception {
		postExpense(groupId, token, "exp-zero", expenseJson(0, 0, 0, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPENSE_TOTAL"));

		postExpense(groupId, token, "exp-neg", expenseJson(-100, -100, 0, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_EXPENSE_TOTAL"));
	}

	@Test
	void negativeShare_returns400() throws Exception {
		postExpense(groupId, token, "exp-neg-share", expenseJson(20000, 25000, -5000, 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SHARE_AMOUNT"));
	}

	@Test
	void duplicateShareParticipant_returns400() throws Exception {
		postExpense(groupId, token, "exp-dup-share", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 200,
				  "shares": [
				    {"participantId": "%s", "amountCents": 100},
				    {"participantId": "%s", "amountCents": 100}
				  ]
				}
				""".formatted(luizId, anaId, anaId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DUPLICATE_SHARE_PARTICIPANT"));
	}

	@Test
	void payerFromAnotherGroup_returns400() throws Exception {
		JsonNode other = createGroup("Outro", "Zé");
		String outsiderId = other.get("creatorParticipantId").asText();

		postExpense(groupId, token, "exp-outsider", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 100,
				  "shares": [{"participantId": "%s", "amountCents": 100}]
				}
				""".formatted(outsiderId, anaId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_IN_GROUP"));
	}

	@Test
	void shareParticipantFromAnotherGroup_returns400() throws Exception {
		JsonNode other = createGroup("Outro", "Zé");
		String outsiderId = other.get("creatorParticipantId").asText();

		postExpense(groupId, token, "exp-outsider-share", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 100,
				  "shares": [{"participantId": "%s", "amountCents": 100}]
				}
				""".formatted(luizId, outsiderId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_IN_GROUP"));
	}

	@Test
	void missingIdempotencyKeyHeader_returns400() throws Exception {
		mockMvc.perform(post("/api/v1/groups/" + groupId + "/expenses")
				.param("token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(expenseJson(100, 100, 0, 0)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void wrongToken_returns403() throws Exception {
		postExpense(groupId, "wrong", "exp-403", expenseJson(100, 100, 0, 0))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void unknownGroup_returns404() throws Exception {
		postExpense(UUID.randomUUID().toString(), token, "exp-404", expenseJson(100, 100, 0, 0))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

}
