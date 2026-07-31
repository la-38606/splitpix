package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.luiz.splitpix.common.GlobalExceptionHandler;
import com.luiz.splitpix.participant.ParticipantRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * The database is the second line of defense: when a constraint fires despite
 * the service-layer pre-check, the response must still honor the section 16
 * contract, and the log must not carry the values the constraint rejected
 * (PostgreSQL puts them on the Detail line — sections 22 and 23 forbid logging
 * Pix keys).
 */
class ConstraintViolationApiTest extends ApiTestSupport {

	@MockitoSpyBean
	private ParticipantRepository participantRepository;

	@Test
	void constraintViolationBehindThePreCheck_returns409_withoutLeakingValues() throws Exception {
		JsonNode group = createGroup("Constraint", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();
		String pixKey = "canary-leak-probe@example.com";

		postParticipant(groupId, token, """
				{"displayName": "Ana", "pixKeyType": "EMAIL", "pixKeyValue": "%s"}
				""".formatted(pixKey))
				.andExpect(status().isCreated());

		Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		handlerLogger.addAppender(appender);

		try {
			// Blind the pre-check so the insert reaches the unique constraint.
			doReturn(false).when(participantRepository).pixKeyExistsInGroup(any(), anyString());

			postParticipant(groupId, token, """
					{"displayName": "Bruno", "pixKeyType": "EMAIL", "pixKeyValue": "%s"}
					""".formatted(pixKey))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("DUPLICATE_PIX_KEY"))
					.andExpect(jsonPath("$.message").isNotEmpty());

			String logged = appender.list.stream()
					.filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
					.map(ILoggingEvent::getFormattedMessage)
					.reduce("", (a, b) -> a + "\n" + b);
			assertThat(logged).doesNotContain(pixKey);
		}
		finally {
			handlerLogger.detachAppender(appender);
		}
	}

}
