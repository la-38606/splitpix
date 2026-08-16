package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class BalanceExplanationApiTest extends ApiTestSupport {

	@Test
	void explanation_walksEveryLegOfTheBalance() throws Exception {
		JsonNode group = createGroup("Jantar", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "jantar", """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 42000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 7000},
				    {"participantId": "%s", "amountCents": 35000}
				  ]
				}
				""".formatted(luizId, luizId, anaId)).andExpect(status().isCreated());
		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 20000))
				.andExpect(status().isCreated());

		// Ana: share -35000, settlement sent +20000 → balance -15000.
		getBalanceExplanation(groupId, anaId, token)
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.participantId").value(anaId))
				.andExpect(jsonPath("$.displayName").value("Ana"))
				.andExpect(jsonPath("$.balanceCents").value(-15000))
				.andExpect(jsonPath("$.entries.length()").value(2))
				.andExpect(jsonPath("$.entries[0].type").value("EXPENSE_SHARE"))
				.andExpect(jsonPath("$.entries[0].description").value("Jantar"))
				.andExpect(jsonPath("$.entries[0].amountCents").value(-35000))
				.andExpect(jsonPath("$.entries[0].counterpartyName").value("Luiz"))
				.andExpect(jsonPath("$.entries[1].type").value("SETTLEMENT_SENT"))
				.andExpect(jsonPath("$.entries[1].amountCents").value(20000))
				.andExpect(jsonPath("$.entries[1].counterpartyName").value("Luiz"));

		// Luiz: paid +42000, own share -7000, settlement received -20000 → +15000.
		getBalanceExplanation(groupId, luizId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceCents").value(15000))
				.andExpect(jsonPath("$.entries.length()").value(3))
				.andExpect(jsonPath("$.entries[0].type").value("EXPENSE_PAID"))
				.andExpect(jsonPath("$.entries[0].amountCents").value(42000))
				.andExpect(jsonPath("$.entries[1].type").value("EXPENSE_SHARE"))
				.andExpect(jsonPath("$.entries[1].amountCents").value(-7000))
				.andExpect(jsonPath("$.entries[2].type").value("SETTLEMENT_RECEIVED"))
				.andExpect(jsonPath("$.entries[2].amountCents").value(-20000));
	}

	@Test
	void zeroShares_appearInNoStatement() throws Exception {
		JsonNode group = createGroup("Feira", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();
		String biaId = addParticipant(groupId, token, "Bia").get("participantId").asText();

		postExpense(groupId, token, "feira", """
				{
				  "description": "Feira",
				  "paidByParticipantId": "%s",
				  "totalCents": 3000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 3000},
				    {"participantId": "%s", "amountCents": 0}
				  ]
				}
				""".formatted(luizId, anaId, biaId)).andExpect(status().isCreated());

		getBalanceExplanation(groupId, biaId, token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceCents").value(0))
				.andExpect(jsonPath("$.entries.length()").value(0));
	}

	@Test
	void everyParticipantsExplanation_sumsToTheirReportedBalance() throws Exception {
		// Randomized activity, fixed seed; the invariant under test is
		// sum(entry amounts) == the balance the balances endpoint reports.
		Random random = new Random(7);
		JsonNode group = createGroup("República", "P0");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		List<String> ids = new ArrayList<>();
		ids.add(group.get("creatorParticipantId").asText());
		for (int i = 1; i < 5; i++) {
			ids.add(addParticipant(groupId, token, "P" + i).get("participantId").asText());
		}

		for (int e = 0; e < 6; e++) {
			long total = 0;
			StringBuilder shares = new StringBuilder();
			for (int i = 0; i < ids.size(); i++) {
				long share = random.nextLong(20_000);
				total += share;
				if (i > 0) {
					shares.append(",");
				}
				shares.append("""
						{"participantId": "%s", "amountCents": %d}""".formatted(ids.get(i), share));
			}
			if (total == 0) {
				continue;
			}
			postExpense(groupId, token, "e" + e, """
					{"description": "Despesa %d", "paidByParticipantId": "%s", "totalCents": %d,
					 "shares": [%s]}
					""".formatted(e, ids.get(random.nextInt(ids.size())), total, shares))
					.andExpect(status().isCreated());
		}

		JsonNode balances = readBody(getBalances(groupId, token).andExpect(status().isOk()));
		Map<String, Long> reported = new HashMap<>();
		balances.get("balances").forEach(b ->
				reported.put(b.get("participantId").asText(), b.get("balanceCents").asLong()));

		for (String id : ids) {
			JsonNode explanation = readBody(getBalanceExplanation(groupId, id, token)
					.andExpect(status().isOk()));
			long sum = 0;
			for (JsonNode entry : explanation.get("entries")) {
				sum += entry.get("amountCents").asLong();
			}
			assertThat(sum)
					.as("participant %s", id)
					.isEqualTo(explanation.get("balanceCents").asLong())
					.isEqualTo(reported.get(id));
		}
	}

	@Test
	void strangerParticipant_returns400() throws Exception {
		JsonNode group = createGroup();
		JsonNode other = createGroup("Outro", "Zoe");
		getBalanceExplanation(group.get("groupId").asText(),
				other.get("creatorParticipantId").asText(), group.get("inviteToken").asText())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_IN_GROUP"));
	}

	@Test
	void wrongToken_returns403_unknownGroup_returns404() throws Exception {
		JsonNode group = createGroup();
		String participantId = group.get("creatorParticipantId").asText();
		getBalanceExplanation(group.get("groupId").asText(), participantId, "wrong")
				.andExpect(status().isForbidden());
		getBalanceExplanation(UUID.randomUUID().toString(), participantId, "any")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
	}

}
