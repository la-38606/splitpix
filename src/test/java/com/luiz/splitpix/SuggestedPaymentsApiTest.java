package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SuggestedPaymentsApiTest extends ApiTestSupport {

	@Test
	void designDocExample_suggestsFourPaymentsAllToLuiz() throws Exception {
		JsonNode group = readBody(postJson("/api/v1/groups", """
				{
				  "groupName": "Jantar",
				  "creatorName": "Luiz",
				  "pixKeyType": "EMAIL",
				  "pixKeyValue": "luiz@example.com"
				}
				""").andExpect(status().isCreated()));
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();

		Map<String, String> ids = new HashMap<>();
		for (String name : new String[] { "Ana", "Bruno", "Clara", "Diego" }) {
			ids.put(name, addParticipant(groupId, token, name).get("participantId").asText());
		}

		postExpense(groupId, token, "dinner", """
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
				""".formatted(luizId, luizId, ids.get("Ana"), ids.get("Bruno"),
				ids.get("Clara"), ids.get("Diego")))
				.andExpect(status().isCreated());

		JsonNode body = readBody(getSuggestedPayments(groupId, token).andExpect(status().isOk()));
		JsonNode payments = body.get("payments");
		assertThat(payments.size()).isEqualTo(4);

		Map<String, Long> amountByPayer = new HashMap<>();
		long total = 0;
		for (JsonNode payment : payments) {
			assertThat(payment.get("recipientParticipantId").asText()).isEqualTo(luizId);
			assertThat(payment.get("recipientName").asText()).isEqualTo("Luiz");
			assertThat(payment.get("recipientPixKey").asText()).isEqualTo("luiz@example.com");
			assertThat(payment.get("amountCents").asLong()).isPositive();
			amountByPayer.put(payment.get("payerParticipantId").asText(), payment.get("amountCents").asLong());
			total += payment.get("amountCents").asLong();
		}
		assertThat(total).isEqualTo(35000L);
		assertThat(amountByPayer)
				.containsEntry(ids.get("Ana"), 9000L)
				.containsEntry(ids.get("Bruno"), 8000L)
				.containsEntry(ids.get("Clara"), 6000L)
				.containsEntry(ids.get("Diego"), 12000L);
	}

	@Test
	void settledGroup_hasNoSuggestions() throws Exception {
		JsonNode group = createGroup("Par", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "e1", """
				{
				  "description": "Almoço",
				  "paidByParticipantId": "%s",
				  "totalCents": 10000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 5000},
				    {"participantId": "%s", "amountCents": 5000}
				  ]
				}
				""".formatted(luizId, luizId, anaId)).andExpect(status().isCreated());

		getSuggestedPayments(groupId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(1))
				.andExpect(jsonPath("$.payments[0].payerParticipantId").value(anaId))
				.andExpect(jsonPath("$.payments[0].amountCents").value(5000));

		// Completing the suggestion regenerates from current state (doc 12.6).
		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		getSuggestedPayments(groupId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(0));
	}

	@Test
	void groupWithNoExpenses_hasNoSuggestions() throws Exception {
		JsonNode group = createGroup();
		getSuggestedPayments(group.get("groupId").asText(), group.get("inviteToken").asText())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(0));
	}

	@Test
	void wrongToken_returns403() throws Exception {
		JsonNode group = createGroup();
		getSuggestedPayments(group.get("groupId").asText(), "wrong")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

}
