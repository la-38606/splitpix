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

class SettlementPlanApiTest extends ApiTestSupport {

	private record Fixture(String groupId, String token, Map<String, String> ids) {
	}

	/**
	 * Balances +500/+400/-400/-300/-200 (in reais): the case where greedy
	 * needs four transfers and the true minimum is three. Expenses also shape
	 * the relationship graph: Ana↔Diego, Ana↔Elisa, Bruno↔Clara.
	 */
	private Fixture greedyBeatingFixture() throws Exception {
		JsonNode group = createGroup("Viagem", "Ana");
		Fixture fixture = new Fixture(group.get("groupId").asText(), group.get("inviteToken").asText(),
				new HashMap<>());
		fixture.ids().put("Ana", group.get("creatorParticipantId").asText());
		for (String name : new String[] { "Bruno", "Clara", "Diego", "Elisa" }) {
			fixture.ids().put(name,
					addParticipant(fixture.groupId(), fixture.token(), name).get("participantId").asText());
		}
		postExpense(fixture.groupId(), fixture.token(), "hotel", """
				{
				  "description": "Hotel",
				  "paidByParticipantId": "%s",
				  "totalCents": 50000,
				  "shares": [
				    {"participantId": "%s", "amountCents": 30000},
				    {"participantId": "%s", "amountCents": 20000}
				  ]
				}
				""".formatted(fixture.ids().get("Ana"), fixture.ids().get("Diego"), fixture.ids().get("Elisa")))
				.andExpect(status().isCreated());
		postExpense(fixture.groupId(), fixture.token(), "carro", """
				{
				  "description": "Carro",
				  "paidByParticipantId": "%s",
				  "totalCents": 40000,
				  "shares": [{"participantId": "%s", "amountCents": 40000}]
				}
				""".formatted(fixture.ids().get("Bruno"), fixture.ids().get("Clara")))
				.andExpect(status().isCreated());
		return fixture;
	}

	@Test
	void defaultPlan_isGreedyWithMetadataAndPixKeys() throws Exception {
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
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "almoco", """
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

		getSettlementPlan(groupId, token, null)
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.groupId").value(groupId))
				.andExpect(jsonPath("$.ledgerRevision").value(1))
				.andExpect(jsonPath("$.plan.strategy").value("GREEDY"))
				.andExpect(jsonPath("$.plan.exact").value(false))
				.andExpect(jsonPath("$.plan.transferCount").value(1))
				.andExpect(jsonPath("$.plan.totalAmountCents").value(5000))
				// Ana shared Luiz's expense, so paying him is not a new relationship.
				.andExpect(jsonPath("$.plan.novelRelationshipEdges").value(0))
				.andExpect(jsonPath("$.plan.transfers[0].payerParticipantId").value(anaId))
				.andExpect(jsonPath("$.plan.transfers[0].recipientName").value("Luiz"))
				.andExpect(jsonPath("$.plan.transfers[0].recipientPixKey").value("luiz@example.com"))
				.andExpect(jsonPath("$.plan.transfers[0].novelRelationship").value(false));
	}

	@Test
	void minTransfers_beatsGreedyOnTheCounterexample() throws Exception {
		Fixture fixture = greedyBeatingFixture();

		JsonNode greedy = readBody(getSettlementPlan(fixture.groupId(), fixture.token(), "GREEDY")
				.andExpect(status().isOk()));
		assertThat(greedy.get("plan").get("transferCount").asInt()).isEqualTo(4);

		JsonNode exact = readBody(getSettlementPlan(fixture.groupId(), fixture.token(), "MIN_TRANSFERS")
				.andExpect(status().isOk()));
		assertThat(exact.get("plan").get("transferCount").asInt()).isEqualTo(3);
		assertThat(exact.get("plan").get("exact").asBoolean()).isTrue();
		// The three-transfer plan happens to follow the expense relationships
		// exactly: Diego→Ana, Elisa→Ana, Clara→Bruno.
		assertThat(exact.get("plan").get("novelRelationshipEdges").asInt()).isZero();

		long total = 0;
		for (JsonNode transfer : exact.get("plan").get("transfers")) {
			total += transfer.get("amountCents").asLong();
		}
		assertThat(total).isEqualTo(90000L);
	}

	@Test
	void compare_returnsEveryStrategyForTheSameSnapshot() throws Exception {
		Fixture fixture = greedyBeatingFixture();

		JsonNode body = readBody(compareSettlementPlans(fixture.groupId(), fixture.token())
				.andExpect(status().isOk()));
		assertThat(body.get("ledgerRevision").asLong()).isEqualTo(2);
		assertThat(body.get("skipped")).isEmpty();

		Map<String, JsonNode> byStrategy = new HashMap<>();
		body.get("plans").forEach(plan -> byStrategy.put(plan.get("strategy").asText(), plan));
		assertThat(byStrategy).containsOnlyKeys("GREEDY", "MIN_TRANSFERS", "RELATIONSHIP_AWARE");
		assertThat(byStrategy.get("GREEDY").get("transferCount").asInt()).isEqualTo(4);
		assertThat(byStrategy.get("MIN_TRANSFERS").get("transferCount").asInt()).isEqualTo(3);
		assertThat(byStrategy.get("RELATIONSHIP_AWARE").get("novelRelationshipEdges").asInt()).isZero();
	}

	@Test
	void forbiddenPair_isExcludedFromThePlan() throws Exception {
		Fixture fixture = greedyBeatingFixture();
		String diego = fixture.ids().get("Diego");
		String ana = fixture.ids().get("Ana");

		JsonNode body = readBody(postSettlementPlan(fixture.groupId(), fixture.token(), """
				{
				  "strategy": "MIN_TRANSFERS",
				  "constraints": {
				    "forbiddenPairs": [
				      {"payerParticipantId": "%s", "recipientParticipantId": "%s"}
				    ]
				  }
				}
				""".formatted(diego, ana)).andExpect(status().isOk()));

		long total = 0;
		for (JsonNode transfer : body.get("plan").get("transfers")) {
			boolean forbidden = transfer.get("payerParticipantId").asText().equals(diego)
					&& transfer.get("recipientParticipantId").asText().equals(ana);
			assertThat(forbidden).isFalse();
			total += transfer.get("amountCents").asLong();
		}
		assertThat(total).isEqualTo(90000L);
	}

	@Test
	void transferCap_boundsEveryInstruction() throws Exception {
		Fixture fixture = greedyBeatingFixture();

		JsonNode body = readBody(postSettlementPlan(fixture.groupId(), fixture.token(), """
				{"strategy": "MIN_TRANSFERS", "constraints": {"maxTransferCents": 25000}}
				""").andExpect(status().isOk()));

		assertThat(body.get("plan").get("transfers")).isNotEmpty();
		for (JsonNode transfer : body.get("plan").get("transfers")) {
			assertThat(transfer.get("amountCents").asLong()).isLessThanOrEqualTo(25000L);
		}
	}

	@Test
	void impossibleConstraints_return409() throws Exception {
		JsonNode group = createGroup("Par", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();
		postExpense(groupId, token, "e1", """
				{"description": "Almoço", "paidByParticipantId": "%s", "totalCents": 5000,
				 "shares": [{"participantId": "%s", "amountCents": 5000}]}
				""".formatted(luizId, anaId)).andExpect(status().isCreated());

		postSettlementPlan(groupId, token, """
				{
				  "strategy": "MIN_TRANSFERS",
				  "constraints": {
				    "forbiddenPairs": [
				      {"payerParticipantId": "%s", "recipientParticipantId": "%s"}
				    ]
				  }
				}
				""".formatted(anaId, luizId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NO_FEASIBLE_SETTLEMENT_PLAN"));
	}

	@Test
	void constraintsWithGreedy_return400() throws Exception {
		Fixture fixture = greedyBeatingFixture();
		postSettlementPlan(fixture.groupId(), fixture.token(), """
				{"strategy": "GREEDY", "constraints": {"maxTransferCents": 1000}}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SETTLEMENT_CONSTRAINT"));
	}

	@Test
	void constraintReferencingAStranger_return400() throws Exception {
		Fixture fixture = greedyBeatingFixture();
		JsonNode other = createGroup("Outro", "Zoe");
		String stranger = other.get("creatorParticipantId").asText();

		postSettlementPlan(fixture.groupId(), fixture.token(), """
				{
				  "strategy": "MIN_TRANSFERS",
				  "constraints": {
				    "forbiddenPairs": [
				      {"payerParticipantId": "%s", "recipientParticipantId": "%s"}
				    ]
				  }
				}
				""".formatted(stranger, fixture.ids().get("Ana")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PARTICIPANT_NOT_IN_GROUP"));
	}

	@Test
	void unknownStrategy_return400() throws Exception {
		Fixture fixture = greedyBeatingFixture();
		getSettlementPlan(fixture.groupId(), fixture.token(), "OPTIMAL")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void oversizedGroup_skipsExactStrategiesButNeverGreedy() throws Exception {
		JsonNode group = createGroup("Grande", "Ana");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String anaId = group.get("creatorParticipantId").asText();

		// Eleven debtors plus the creditor: twelve nonzero balances, past the
		// exact search's limit of ten.
		StringBuilder shares = new StringBuilder();
		for (int i = 0; i < 11; i++) {
			String id = addParticipant(groupId, token, "P" + i).get("participantId").asText();
			if (i > 0) {
				shares.append(",");
			}
			shares.append("""
					{"participantId": "%s", "amountCents": 1000}""".formatted(id));
		}
		postExpense(groupId, token, "grande", """
				{"description": "Rachada", "paidByParticipantId": "%s", "totalCents": 11000,
				 "shares": [%s]}
				""".formatted(anaId, shares)).andExpect(status().isCreated());

		getSettlementPlan(groupId, token, "MIN_TRANSFERS")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_OPTIMIZATION_SIZE"));

		getSettlementPlan(groupId, token, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.plan.transferCount").value(11));

		JsonNode comparison = readBody(compareSettlementPlans(groupId, token).andExpect(status().isOk()));
		assertThat(comparison.get("plans")).hasSize(1);
		assertThat(comparison.get("skipped")).hasSize(2);
		comparison.get("skipped").forEach(skip ->
				assertThat(skip.get("reason").asText()).isEqualTo("UNSUPPORTED_OPTIMIZATION_SIZE"));
	}

	@Test
	void ledgerRevision_countsAccountingEntriesOnly() throws Exception {
		JsonNode group = createGroup("Par", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		getSettlementPlan(groupId, token, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ledgerRevision").value(0))
				.andExpect(jsonPath("$.plan.transferCount").value(0));

		postExpense(groupId, token, "e1", """
				{"description": "Almoço", "paidByParticipantId": "%s", "totalCents": 5000,
				 "shares": [{"participantId": "%s", "amountCents": 5000}]}
				""".formatted(luizId, anaId)).andExpect(status().isCreated());
		getSettlementPlan(groupId, token, null)
				.andExpect(jsonPath("$.ledgerRevision").value(1));

		postSettlement(groupId, token, "s1", settlementJson(anaId, luizId, 5000))
				.andExpect(status().isCreated());
		getSettlementPlan(groupId, token, null)
				.andExpect(jsonPath("$.ledgerRevision").value(2))
				.andExpect(jsonPath("$.plan.transferCount").value(0));
	}

	@Test
	void plans_referenceOnlyMembersOfTheRequestedGroup() throws Exception {
		JsonNode groupA = createGroup("Grupo A", "Luiz");
		String aId = groupA.get("groupId").asText();
		String aToken = groupA.get("inviteToken").asText();
		String aLuiz = groupA.get("creatorParticipantId").asText();
		String aAna = addParticipant(aId, aToken, "Ana").get("participantId").asText();

		JsonNode groupB = createGroup("Grupo B", "Bia");
		String bId = groupB.get("groupId").asText();
		String bToken = groupB.get("inviteToken").asText();
		String bCaio = addParticipant(bId, bToken, "Caio").get("participantId").asText();
		postExpense(bId, bToken, "eb", """
				{"description": "Uber", "paidByParticipantId": "%s", "totalCents": 2000,
				 "shares": [{"participantId": "%s", "amountCents": 2000}]}
				""".formatted(groupB.get("creatorParticipantId").asText(), bCaio))
				.andExpect(status().isCreated());
		postExpense(aId, aToken, "ea", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 1000,
				 "shares": [{"participantId": "%s", "amountCents": 1000}]}
				""".formatted(aLuiz, aAna)).andExpect(status().isCreated());

		JsonNode body = readBody(getSettlementPlan(aId, aToken, null).andExpect(status().isOk()));
		assertThat(body.get("plan").get("transferCount").asInt()).isEqualTo(1);
		assertThat(body.get("plan").get("transfers").get(0).get("payerParticipantId").asText())
				.isEqualTo(aAna);
		assertThat(body.get("plan").get("transfers").get(0).get("recipientParticipantId").asText())
				.isEqualTo(aLuiz);
	}

	@Test
	void wrongToken_returns403() throws Exception {
		JsonNode group = createGroup();
		getSettlementPlan(group.get("groupId").asText(), "wrong", null)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
		compareSettlementPlans(group.get("groupId").asText(), "wrong")
				.andExpect(status().isForbidden());
	}

}
