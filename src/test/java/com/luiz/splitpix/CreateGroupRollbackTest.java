package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.luiz.splitpix.participant.ParticipantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Invariant 7: a failed transaction leaves no partial state. Create-group is
 * the multi-table write of Day 1 — if the creator insert fails, no group row
 * may survive.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreateGroupRollbackTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private ParticipantRepository participantRepository;

	@Test
	void failedCreatorInsert_leavesNoGroupRow() throws Exception {
		doThrow(new RuntimeException("simulated participant insert failure"))
				.when(participantRepository).insert(any(), any(), any(), any(), any());

		mockMvc.perform(post("/api/v1/groups")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"groupName": "Atomic Group", "creatorName": "Luiz"}
						"""))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		Integer orphanGroups = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM groups WHERE name = 'Atomic Group'", Integer.class);
		assertThat(orphanGroups).isZero();
	}

}
