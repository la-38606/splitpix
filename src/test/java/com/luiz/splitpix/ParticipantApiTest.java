package com.luiz.splitpix;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

class ParticipantApiTest extends ApiTestSupport {

	private String groupId;
	private String token;

	@BeforeEach
	void setUpGroup() throws Exception {
		JsonNode body = createGroup("República", "Luiz");
		groupId = body.get("groupId").asText();
		token = body.get("inviteToken").asText();
	}

	@Test
	void addParticipant_returns201WithParticipant() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": "Ana", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""")
				.andExpect(status().isCreated())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.participantId").isNotEmpty())
				.andExpect(jsonPath("$.displayName").value("Ana"))
				.andExpect(jsonPath("$.pixKeyType").value("PHONE"));
	}

	@Test
	void addParticipant_duplicatePixKeyInGroup_returns409() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": "Ana", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""").andExpect(status().isCreated());

		postParticipant(groupId, token, """
				{"displayName": "Bruno", "pixKeyType": "PHONE", "pixKeyValue": "+5511999999999"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PIX_KEY"));
	}

	@Test
	void emailPixKeys_collideCaseInsensitively() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": "Ana", "pixKeyType": "EMAIL", "pixKeyValue": "ana@x.com"}
				""").andExpect(status().isCreated());

		postParticipant(groupId, token, """
				{"displayName": "Bruno", "pixKeyType": "EMAIL", "pixKeyValue": "Ana@X.com"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_PIX_KEY"));
	}

	@Test
	void addParticipant_withoutPixKey_isAllowed() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": "Clara"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.pixKeyType").value((Object) null))
				.andExpect(jsonPath("$.pixKeyValue").value((Object) null));
	}

	@Test
	void addParticipant_valueWithoutType_returns400() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": "Diego", "pixKeyValue": "diego@example.com"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PIX_KEY_PAIR"));
	}

	@Test
	void addParticipant_wrongToken_returns403() throws Exception {
		postParticipant(groupId, "wrong", """
				{"displayName": "Eva"}
				""")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("INVALID_INVITE_TOKEN"));
	}

	@Test
	void addParticipant_blankName_returns400() throws Exception {
		postParticipant(groupId, token, """
				{"displayName": ""}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void addParticipant_invisibleOrDeceptiveName_returns400() throws Exception {
		// Zero-width and format characters render as nothing; ANSI escapes and
		// bidi overrides actively lie in terminal output (demo.sh) and in a UI.
		String[] names = {
				"\\u200b\\u200d",   // zero-width space + joiner
				"\\u00ad",          // soft hyphen
				"\\u001b[31mAna",   // ANSI escape
				"\\u202eAna",       // right-to-left override
				"\\u0007Ana",       // bell
		};
		for (String name : names) {
			postParticipant(groupId, token, """
					{"displayName": "%s"}
					""".formatted(name))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
	}

	@Test
	void addParticipant_accentsAndEmojiAreAccepted() throws Exception {
		// The invisible-character rule must not reject legitimate names.
		postParticipant(groupId, token, """
				{"displayName": "José Antônio 🎉"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.displayName").value("José Antônio 🎉"));
	}

	@Test
	void addParticipant_pixKeyThatGrowsPastTheColumnWhenNormalized_returns400() throws Exception {
		// U+0130 lowercases into two code points, so a 200-char key becomes 400.
		postParticipant(groupId, token, """
				{"displayName": "Zed", "pixKeyType": "EMAIL", "pixKeyValue": "%s"}
				""".formatted("\\u0130".repeat(200)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void addParticipant_nonBreakingSpaceName_returns400() throws Exception {
		// U+00A0 passes @NotBlank (not Java whitespace) but is visually blank.
		postParticipant(groupId, token, """
				{"displayName": "\\u00a0"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

}
