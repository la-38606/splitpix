package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

		Map<String, String> nameById = new HashMap<>();
		nameById.put(ids.get("Ana"), "Ana");
		nameById.put(ids.get("Bruno"), "Bruno");
		nameById.put(ids.get("Clara"), "Clara");
		nameById.put(ids.get("Diego"), "Diego");

		Map<String, Long> amountByPayer = new HashMap<>();
		long total = 0;
		for (JsonNode payment : payments) {
			assertThat(payment.get("recipientParticipantId").asText()).isEqualTo(luizId);
			assertThat(payment.get("recipientName").asText()).isEqualTo("Luiz");
			assertThat(payment.get("recipientPixKey").asText()).isEqualTo("luiz@example.com");
			assertThat(payment.get("payerName").asText())
					.isEqualTo(nameById.get(payment.get("payerParticipantId").asText()));
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
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.payments.length()").value(1))
				.andExpect(jsonPath("$.payments[0].payerParticipantId").value(anaId))
				.andExpect(jsonPath("$.payments[0].amountCents").value(5000))
				// The recipient has no Pix key: the field is present and null.
				.andExpect(jsonPath("$.payments[0].recipientPixKey").value((Object) null));

		// Completing the suggestion regenerates from current state (doc 12.6).
		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		getSuggestedPayments(groupId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(0));
	}

	@Test
	void suggestions_referenceOnlyMembersOfTheRequestedGroup() throws Exception {
		// Two groups with debts each; group A's plan must involve A's members only.
		JsonNode groupA = createGroup("Grupo A", "Luiz");
		String aId = groupA.get("groupId").asText();
		String aToken = groupA.get("inviteToken").asText();
		String aLuiz = groupA.get("creatorParticipantId").asText();
		String aAna = addParticipant(aId, aToken, "Ana").get("participantId").asText();

		JsonNode groupB = createGroup("Grupo B", "Bia");
		String bId = groupB.get("groupId").asText();
		String bToken = groupB.get("inviteToken").asText();
		String bBia = groupB.get("creatorParticipantId").asText();
		String bCaio = addParticipant(bId, bToken, "Caio").get("participantId").asText();

		postExpense(aId, aToken, "ea", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 1000,
				 "shares": [{"participantId": "%s", "amountCents": 1000}]}
				""".formatted(aLuiz, aAna)).andExpect(status().isCreated());
		postExpense(bId, bToken, "eb", """
				{"description": "Uber", "paidByParticipantId": "%s", "totalCents": 2000,
				 "shares": [{"participantId": "%s", "amountCents": 2000}]}
				""".formatted(bBia, bCaio)).andExpect(status().isCreated());

		JsonNode payments = readBody(getSuggestedPayments(aId, aToken)
				.andExpect(status().isOk())).get("payments");
		assertThat(payments.size()).isEqualTo(1);
		java.util.Set<String> members = java.util.Set.of(aLuiz, aAna);
		for (JsonNode payment : payments) {
			assertThat(members).contains(payment.get("payerParticipantId").asText());
			assertThat(members).contains(payment.get("recipientParticipantId").asText());
		}
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
