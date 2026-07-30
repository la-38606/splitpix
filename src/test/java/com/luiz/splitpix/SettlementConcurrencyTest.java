package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The critical concurrency test (design doc 18.4) and invariant 8: competing
 * settlement requests serialized by the group lock can never over-settle.
 */
class SettlementConcurrencyTest extends ApiTestSupport {

	private record Group18_4(String groupId, String token, String alice, String bob, String carol) {
	}

	/** Alice -10000, Bob +8000, Carol +2000 — exactly the 18.4 setup. */
	private Group18_4 setUp18_4() throws Exception {
		JsonNode group = createGroup("Concurrency", "Alice");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String alice = group.get("creatorParticipantId").asText();
		String bob = addParticipant(groupId, token, "Bob").get("participantId").asText();
		String carol = addParticipant(groupId, token, "Carol").get("participantId").asText();

		postExpense(groupId, token, "e-bob", """
				{"description": "Mercado", "paidByParticipantId": "%s", "totalCents": 8000,
				 "shares": [{"participantId": "%s", "amountCents": 8000}]}
				""".formatted(bob, alice)).andExpect(status().isCreated());
		postExpense(groupId, token, "e-carol", """
				{"description": "Uber", "paidByParticipantId": "%s", "totalCents": 2000,
				 "shares": [{"participantId": "%s", "amountCents": 2000}]}
				""".formatted(carol, alice)).andExpect(status().isCreated());

		return new Group18_4(groupId, token, alice, bob, carol);
	}

	private Map<String, Long> balances(String groupId, String token) throws Exception {
		JsonNode body = readBody(getBalances(groupId, token).andExpect(status().isOk()));
		Map<String, Long> result = new HashMap<>();
		body.get("balances").forEach(b ->
				result.put(b.get("participantId").asText(), b.get("balanceCents").asLong()));
		return result;
	}

	private List<Integer> runConcurrently(Callable<Integer> first, Callable<Integer> second) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> f1 = pool.submit(first);
			Future<Integer> f2 = pool.submit(second);
			return List.of(f1.get(), f2.get());
		}
		finally {
			pool.shutdown();
		}
	}

	@Test
	void criticalConcurrencyTest_18_4() throws Exception {
		Group18_4 g = setUp18_4();
		CyclicBarrier barrier = new CyclicBarrier(2);

		Callable<Integer> aliceToBob = () -> {
			barrier.await();
			return postSettlement(g.groupId(), g.token(), "s-ab",
					settlementJson(g.alice(), g.bob(), 8000))
					.andReturn().getResponse().getStatus();
		};
		Callable<Integer> aliceToCarol = () -> {
			barrier.await();
			return postSettlement(g.groupId(), g.token(), "s-ac",
					settlementJson(g.alice(), g.carol(), 8000))
					.andReturn().getResponse().getStatus();
		};

		List<Integer> statuses = runConcurrently(aliceToBob, aliceToCarol);

		// Alice→Carol can never succeed (Carol is owed only 2000); Alice→Bob always can.
		assertThat(statuses.get(0)).isEqualTo(201);
		assertThat(statuses.get(1)).isEqualTo(409);

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, g.groupId());
		assertThat(rows).isEqualTo(1);

		Map<String, Long> balances = balances(g.groupId(), g.token());
		assertThat(balances.get(g.alice())).isEqualTo(-2000L);
		assertThat(balances.get(g.bob())).isZero();
		assertThat(balances.get(g.carol())).isEqualTo(2000L);
		assertThat(balances.values().stream().mapToLong(Long::longValue).sum()).isZero();
	}

	@Test
	void concurrentIdenticalSettlements_withDistinctKeys_onlyOneCommits() throws Exception {
		// Invariant 8: a retry with a NEW key is a new request; the group lock
		// must reject the loser instead of double-settling.
		Group18_4 g = setUp18_4();
		CyclicBarrier barrier = new CyclicBarrier(2);

		Callable<Integer> request1 = () -> {
			barrier.await();
			return postSettlement(g.groupId(), g.token(), "key-one",
					settlementJson(g.alice(), g.bob(), 8000))
					.andReturn().getResponse().getStatus();
		};
		Callable<Integer> request2 = () -> {
			barrier.await();
			return postSettlement(g.groupId(), g.token(), "key-two",
					settlementJson(g.alice(), g.bob(), 8000))
					.andReturn().getResponse().getStatus();
		};

		List<Integer> statuses = runConcurrently(request1, request2);
		assertThat(statuses).containsExactlyInAnyOrder(201, 409);

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, g.groupId());
		assertThat(rows).isEqualTo(1);

		// Alice is never over-settled: -10000 + 8000 = -2000, not +6000.
		Map<String, Long> balances = balances(g.groupId(), g.token());
		assertThat(balances.get(g.alice())).isEqualTo(-2000L);
		assertThat(balances.values().stream().mapToLong(Long::longValue).sum()).isZero();
	}

}
