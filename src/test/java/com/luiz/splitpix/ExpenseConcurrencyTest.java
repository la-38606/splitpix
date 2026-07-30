package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Pins the group lock (design doc 13.3): two concurrent requests with the same
 * idempotency key must produce exactly one expense — one 201, one 200 replay.
 * Without the lock the loser would hit unique_expense_request and surface a
 * 409 CONSTRAINT_VIOLATION instead of the replay.
 */
class ExpenseConcurrencyTest extends ApiTestSupport {

	@Test
	void concurrentSameKeyRequests_produceExactlyOneExpense() throws Exception {
		JsonNode group = createGroup("Corrida", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String creatorId = group.get("creatorParticipantId").asText();

		String json = """
				{
				  "description": "Jantar",
				  "paidByParticipantId": "%s",
				  "totalCents": 100,
				  "shares": [{"participantId": "%s", "amountCents": 100}]
				}
				""".formatted(creatorId, creatorId);

		CyclicBarrier barrier = new CyclicBarrier(2);
		Callable<Integer> request = () -> {
			barrier.await();
			return postExpense(groupId, token, "race-key", json)
					.andReturn().getResponse().getStatus();
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = pool.submit(request);
			Future<Integer> second = pool.submit(request);
			List<Integer> statuses = List.of(first.get(), second.get());
			assertThat(statuses).containsExactlyInAnyOrder(201, 200);
		}
		finally {
			pool.shutdown();
		}

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE group_id = ?::uuid AND idempotency_key = 'race-key'",
				Integer.class, groupId);
		assertThat(rows).isEqualTo(1);
	}

}
