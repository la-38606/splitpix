package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.participant.ParticipantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Invariant 7: a failed transaction leaves no partial state. Create-group is
 * the group-plus-creator multi-table write — if the creator insert fails, no group row
 * may survive.
 */
class CreateGroupRollbackTest extends ApiTestSupport {

	@MockitoSpyBean
	private ParticipantRepository participantRepository;

	@Test
	void failedCreatorInsert_leavesNoGroupRow() throws Exception {
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new RuntimeException("simulated failure after real participant insert");
		}).when(participantRepository).insert(any(), any(), any(), any(), any());

		postJson("/api/v1/groups", """
				{"groupName": "Atomic Group", "creatorName": "Luiz"}
				""")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer orphanGroups = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM groups WHERE name = 'Atomic Group'", Integer.class);
		assertThat(orphanGroups).isZero();
	}

}
