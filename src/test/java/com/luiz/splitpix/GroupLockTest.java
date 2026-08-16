package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.expense.ExpenseRepository;
import com.luiz.splitpix.group.GroupRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Pins the expense-path group lock (design doc 13.3) deterministically.
 *
 * A barrier-synchronized race is not enough: in a warm shared test context both
 * transactions can complete before they ever overlap, so the race test passes
 * even with the lock removed. These two tests remove the timing dependency —
 * one asserts the lock is taken, the other blocks the lock holder and proves
 * the second request genuinely waits.
 */
class GroupLockTest extends ApiTestSupport {

	@MockitoSpyBean
	private GroupRepository groupRepository;

	@MockitoSpyBean
	private ExpenseRepository expenseRepository;

	private String expenseJson(String participantId) {
		return """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 100,
				 "shares": [{"participantId": "%s", "amountCents": 100}]}
				""".formatted(participantId, participantId);
	}

	@Test
	void settlementCompletion_takesTheGroupLock() throws Exception {
		JsonNode group = createGroup("Lock", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String luizId = group.get("creatorParticipantId").asText();
		String anaId = addParticipant(groupId, token, "Ana").get("participantId").asText();

		postExpense(groupId, token, "lock-s-setup", """
				{"description": "Jantar", "paidByParticipantId": "%s", "totalCents": 100,
				 "shares": [{"participantId": "%s", "amountCents": 100}]}
				""".formatted(luizId, anaId))
				.andExpect(status().isCreated());
		org.mockito.Mockito.clearInvocations(groupRepository);

		postSettlement(groupId, token, "lock-s", settlementJson(anaId, luizId, 100))
				.andExpect(status().isCreated());

		verify(groupRepository).lockById(java.util.UUID.fromString(groupId));
	}

	@Test
	void expenseCreation_takesTheGroupLock() throws Exception {
		JsonNode group = createGroup("Lock", "Luiz");
		String groupId = group.get("groupId").asText();

		postExpense(groupId, group.get("inviteToken").asText(), "lock-1",
				expenseJson(group.get("creatorParticipantId").asText()))
				.andExpect(status().isCreated());

		verify(groupRepository).lockById(java.util.UUID.fromString(groupId));
	}

	@Test
	void secondSameKeyRequest_blocksOnTheLock_thenReplays() throws Exception {
		JsonNode group = createGroup("Lock", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String json = expenseJson(group.get("creatorParticipantId").asText());

		CountDownLatch holderIsInside = new CountDownLatch(1);
		CountDownLatch releaseHolder = new CountDownLatch(1);
		AtomicBoolean firstCall = new AtomicBoolean(true);

		// The first request pauses inside its idempotency check — after the
		// lock is taken, before the insert — so the lock is held while the
		// second request runs.
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			if (firstCall.compareAndSet(true, false)) {
				holderIsInside.countDown();
				releaseHolder.await(10, TimeUnit.SECONDS);
			}
			return result;
		}).when(expenseRepository).findByGroupIdAndIdempotencyKey(any(), anyString());

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> holder = pool.submit(() ->
					postExpense(groupId, token, "race", json).andReturn().getResponse().getStatus());
			assertThat(holderIsInside.await(10, TimeUnit.SECONDS)).isTrue();

			Future<Integer> waiter = pool.submit(() ->
					postExpense(groupId, token, "race", json).andReturn().getResponse().getStatus());

			// Without the lock the second request would sail past the held
			// transaction, insert, and finish here — this is the assertion the
			// lock removal fails.
			assertThat(waiter.isDone())
					.as("second request must block on the group lock")
					.isFalse();
			Thread.sleep(500);
			assertThat(waiter.isDone())
					.as("second request must still be blocked while the lock is held")
					.isFalse();

			releaseHolder.countDown();
			assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(201);
			assertThat(waiter.get(10, TimeUnit.SECONDS)).isEqualTo(200);
		}
		finally {
			releaseHolder.countDown();
			pool.shutdown();
		}

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE group_id = ?::uuid AND idempotency_key = 'race'",
				Integer.class, groupId);
		assertThat(rows).isEqualTo(1);
	}

}
