package com.luiz.splitpix;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class ActivityApiTest extends ApiTestSupport {

	@Test
	void expensesAndSettlements_appearInCreationOrder() throws Exception {
		JsonNode group = createGroup("Jantar", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "e1", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 10000,
				 "shares": [
				   {"participantId": "%s", "amountCents": 5000},
				   {"participantId": "%s", "amountCents": 5000}
				 ]}
				""".formatted(luizId, luizId, anaId)).andExpect(status().isCreated());

		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());

		getActivity(groupId, token)
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.groupId").value(groupId))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].type").value("EXPENSE"))
				.andExpect(jsonPath("$.items[0].description").value("Jantar"))
				.andExpect(jsonPath("$.items[0].payerParticipantId").value(luizId))
				.andExpect(jsonPath("$.items[0].recipientParticipantId").value((Object) null))
				.andExpect(jsonPath("$.items[0].amountCents").value(10000))
				.andExpect(jsonPath("$.items[1].type").value("SETTLEMENT"))
				.andExpect(jsonPath("$.items[1].payerParticipantId").value(anaId))
				.andExpect(jsonPath("$.items[1].recipientParticipantId").value(luizId))
				.andExpect(jsonPath("$.items[1].amountCents").value(5000));
	}

	@Test
	void itemsInterleaveByCreationTime_notByType() throws Exception {
		// Kills an ORDER BY regression: without it, the UNION branch order
		// would group all expenses before all settlements.
		JsonNode group = createGroup("Ordem", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "e1", """
				{"description": "Primeira", "paidByParticipantId": "%s", "totalCents": 4000,
				 "shares": [{"participantId": "%s", "amountCents": 4000}]}
				""".formatted(luizId, anaId)).andExpect(status().isCreated());
		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 4000))
				.andExpect(status().isCreated());
		postExpense(groupId, token, "e2", """
				{"description": "Segunda", "paidByParticipantId": "%s", "totalCents": 2000,
				 "shares": [{"participantId": "%s", "amountCents": 2000}]}
				""".formatted(luizId, anaId)).andExpect(status().isCreated());

		getActivity(groupId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[0].type").value("EXPENSE"))
				.andExpect(jsonPath("$.items[0].description").value("Primeira"))
				.andExpect(jsonPath("$.items[1].type").value("SETTLEMENT"))
				.andExpect(jsonPath("$.items[2].type").value("EXPENSE"))
				.andExpect(jsonPath("$.items[2].description").value("Segunda"));
	}

	@Test
	void emptyGroup_hasEmptyActivity() throws Exception {
		JsonNode group = createGroup();
		getActivity(group.get("groupId").asText(), group.get("inviteToken").asText())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void wrongToken_returns403() throws Exception {
		JsonNode group = createGroup();
		getActivity(group.get("groupId").asText(), "wrong")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

}
