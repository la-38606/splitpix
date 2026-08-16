package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class BalanceApiTest extends ApiTestSupport {

	private record TestGroup(String groupId, String token, Map<String, String> idsByName) {
	}

	private TestGroup groupOfFive() throws Exception {
		JsonNode group = createGroup("Jantar", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		Map<String, String> ids = new HashMap<>();
		ids.put("Luiz", group.get("creatorParticipantId").asText());
		for (String name : new String[] { "Ana", "Bruno", "Clara", "Diego" }) {
			ids.put(name, addParticipant(groupId, token, name).get("participantId").asText());
		}
		return new TestGroup(groupId, token, ids);
	}

	private Map<String, Long> balancesByParticipantId(TestGroup group) throws Exception {
		JsonNode body = readBody(getBalances(group.groupId(), group.token()).andExpect(status().isOk()));
		Map<String, Long> result = new HashMap<>();
		body.get("balances").forEach(b ->
				result.put(b.get("participantId").asText(), b.get("balanceCents").asLong()));
		return result;
	}

	@Test
	void designDocExample_producesExpectedBalances() throws Exception {
		// Section 2: Luiz pays R$420.00 for five people with unequal shares.
		TestGroup group = groupOfFive();
		Map<String, String> ids = group.idsByName();

		postExpense(group.groupId(), group.token(), "dinner-420", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 42000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 7000},
				    {"participantId": "%s", "amountCents": 9000},
				    {"participantId": "%s", "amountCents": 8000},
				    {"participantId": "%s", "amountCents": 6000},
				    {"participantId": "%s", "amountCents": 12000}
				  ]
				}
				""".formatted(ids.get("Luiz"), ids.get("Luiz"), ids.get("Ana"), ids.get("Bruno"),
				ids.get("Clara"), ids.get("Diego")))
				.andExpect(status().isCreated());

		Map<String, Long> balances = balancesByParticipantId(group);
		assertThat(balances.get(ids.get("Luiz"))).isEqualTo(35000L);
		assertThat(balances.get(ids.get("Ana"))).isEqualTo(-9000L);
		assertThat(balances.get(ids.get("Bruno"))).isEqualTo(-8000L);
		assertThat(balances.get(ids.get("Clara"))).isEqualTo(-6000L);
		assertThat(balances.get(ids.get("Diego"))).isEqualTo(-12000L);
	}

	@Test
	void balancesAlwaysSumToZero_acrossMultipleExpenses() throws Exception {
		// Invariant 1: sum of all participant balances is zero.
		TestGroup group = groupOfFive();
		Map<String, String> ids = group.idsByName();

		long[][] expenses = {
				{ 42000, 7000, 9000, 8000, 6000, 12000 },
				{ 9900, 3300, 3300, 3300, 0, 0 },
				{ 123457, 1, 2, 3, 123451, 0 },
		};
		String[] payers = { "Luiz", "Ana", "Diego" };
		String[] names = { "Luiz", "Ana", "Bruno", "Clara", "Diego" };

		for (int i = 0; i < expenses.length; i++) {
			StringBuilder shares = new StringBuilder();
			for (int j = 0; j < names.length; j++) {
				if (j > 0) {
					shares.append(",");
				}
				shares.append("""
						{"participantId": "%s", "amountCents": %d}
						""".formatted(ids.get(names[j]), expenses[i][j + 1]));
			}
			postExpense(group.groupId(), group.token(), "multi-" + i, """
					{
					  "description": "Despesa %d",
					  "paidByParticipantId": "%s",
					  "totalCents": %d,
					  "shares": [%s]
					}
					""".formatted(i, ids.get(payers[i]), expenses[i][0], shares))
					.andExpect(status().isCreated());
		}

		Map<String, Long> balances = balancesByParticipantId(group);
		assertThat(balances.values().stream().mapToLong(Long::longValue).sum()).isZero();
		assertThat(balances.values()).anyMatch(b -> b != 0);
	}

	@Test
	void groupWithNoExpenses_hasAllZeroBalances() throws Exception {
		TestGroup group = groupOfFive();
		Map<String, Long> balances = balancesByParticipantId(group);
		assertThat(balances.values()).hasSize(5).allMatch(b -> b == 0L);
	}

	@Test
	void completedSettlements_moveBalancesTowardZero() throws Exception {
		// The settlement legs of the balance query, exercised by direct insert
		// so they stay pinned independently of the settlement endpoint.
		TestGroup group = groupOfFive();
		Map<String, String> ids = group.idsByName();

		postExpense(group.groupId(), group.token(), "pre-settle", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 10000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 5000},
				    {"participantId": "%s", "amountCents": 5000}
				  ]
				}
				""".formatted(ids.get("Luiz"), ids.get("Luiz"), ids.get("Ana")))
				.andExpect(status().isCreated());

		jdbcTemplate.update("""
				INSERT INTO settlements (id, group_id, payer_participant_id, recipient_participant_id,
				                         amount_cents, idempotency_key, request_hash, status)
				VALUES (?, ?::uuid, ?::uuid, ?::uuid, ?, ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'COMPLETED')
				""", UUID.randomUUID(), group.groupId(), ids.get("Ana"), ids.get("Luiz"), 5000L, "settle-1");

		Map<String, Long> balances = balancesByParticipantId(group);
		assertThat(balances.get(ids.get("Luiz"))).isZero();
		assertThat(balances.get(ids.get("Ana"))).isZero();
	}

	@Test
	void balances_areOrderedByParticipantCreation() throws Exception {
		TestGroup group = groupOfFive();
		getBalances(group.groupId(), group.token())
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.groupId").value(group.groupId()))
				.andExpect(jsonPath("$.balances.length()").value(5))
				.andExpect(jsonPath("$.balances[0].displayName").value("Luiz"))
				.andExpect(jsonPath("$.balances[0].participantId")
						.value(group.idsByName().get("Luiz")));
	}

	@Test
	void wrongToken_returns403() throws Exception {
		TestGroup group = groupOfFive();
		getBalances(group.groupId(), "wrong")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

}
