package com.luiz.splitpix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The browser flow of addendum 36: invite link exchanged for a cookie, then
 * Post/Redirect/Get through the whole loop.
 */
class WebFlowTest extends ApiTestSupport {

	private static final String COOKIE = "spx_convite";

	private record Session(String groupId, Cookie cookie) {
	}

	/** Creates a group through the form and keeps the cookie the server set. */
	private Session createGroupViaForm() throws Exception {
		MvcResult result = mockMvc.perform(post("/grupos")
				.param("groupName", "República")
				.param("creatorName", "Luiz")
				.param("pixKeyType", "EMAIL")
				.param("pixKeyValue", "luiz@example.com"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrlPattern("/g/*"))
				.andExpect(cookie().exists(COOKIE))
				.andExpect(cookie().httpOnly(COOKIE, true))
				.andReturn();

		String location = result.getResponse().getRedirectedUrl();
		String groupId = location.substring("/g/".length()).replace("?novo", "");
		return new Session(groupId, result.getResponse().getCookie(COOKIE));
	}

	private void addParticipantViaForm(Session session, String name) throws Exception {
		mockMvc.perform(post("/g/" + session.groupId() + "/participantes")
				.cookie(session.cookie())
				.param("displayName", name))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/g/" + session.groupId()));
	}

	@Test
	void inviteLink_exchangesTokenForCookie_andRedirectsToTokenFreeUrl() throws Exception {
		var group = createGroup("Rio", "Luiz");
		String groupId = group.get("groupId").asText();
		String token = group.get("inviteToken").asText();

		mockMvc.perform(get("/g/" + groupId).param("token", token))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/g/" + groupId))
				.andExpect(cookie().exists(COOKIE))
				.andExpect(cookie().httpOnly(COOKIE, true))
				.andExpect(cookie().path(COOKIE, "/g/" + groupId));
	}

	@Test
	void inviteLink_withWrongToken_showsErrorPage_andSetsNoCookie() throws Exception {
		var group = createGroup();
		mockMvc.perform(get("/g/" + group.get("groupId").asText()).param("token", "errado"))
				.andExpect(view().name("erro"))
				.andExpect(cookie().doesNotExist(COOKIE))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_INVITE_TOKEN")));
	}

	@Test
	void groupPage_withoutCookie_showsErrorPage() throws Exception {
		var group = createGroup();
		mockMvc.perform(get("/g/" + group.get("groupId").asText()))
				.andExpect(view().name("erro"));
	}

	@Test
	void fullBrowserLoop_fromCreationToSettlement() throws Exception {
		Session session = createGroupViaForm();
		addParticipantViaForm(session, "Ana");

		// The rendered page carries a fresh idempotency key for the expense form.
		MvcResult page = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andExpect(view().name("group"))
				.andReturn();
		String html = page.getResponse().getContentAsString();
		assertThat(html).contains("República").contains("Ana").contains("Luiz");

		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Mercado")
				.param("paidByParticipantId", creatorIdOf(session, html))
				.param("totalReais", "100,00")
				.param("share-" + creatorIdOf(session, html), "50,00")
				.param("share-" + otherIdOf(session, html), "50,00"))
				.andExpect(status().is3xxRedirection());

		// Balances and a suggested payment now render.
		String withExpense = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(withExpense).contains("50,00").contains("luiz@example.com");

		mockMvc.perform(post("/g/" + session.groupId() + "/pagamentos")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("payerParticipantId", otherIdOf(session, withExpense))
				.param("recipientParticipantId", creatorIdOf(session, withExpense))
				.param("amountCents", "5000"))
				.andExpect(status().is3xxRedirection());

		String settled = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		// Everything is squared up, so the suggestions list gives way to its empty state.
		assertThat(settled).contains("Tudo quitado");
	}

	@Test
	void repeatingAnExpenseFormWithDifferentValues_isAConflict_notSilentLoss() throws Exception {
		// The back-button case: same rendered form (same key), edited amounts.
		Session session = createGroupViaForm();
		addParticipantViaForm(session, "Ana");
		String html = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();
		String key = UUID.randomUUID().toString();
		String creator = creatorIdOf(session, html);
		String other = otherIdOf(session, html);

		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", key)
				.param("description", "Mercado")
				.param("paidByParticipantId", creator)
				.param("totalReais", "100,00")
				.param("share-" + creator, "50,00")
				.param("share-" + other, "50,00"))
				.andExpect(status().is3xxRedirection());

		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", key)
				.param("description", "Mercado")
				.param("paidByParticipantId", creator)
				.param("totalReais", "80,00")
				.param("share-" + creator, "40,00")
				.param("share-" + other, "40,00"))
				.andExpect(view().name("erro"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("IDEMPOTENCY_CONFLICT")));

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE group_id = ?::uuid", Integer.class, session.groupId());
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void participantListMasksPixKeys_butThePaymentInstructionShowsTheKey() throws Exception {
		Session session = createGroupViaForm();
		addParticipantViaForm(session, "Ana");
		String html = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();
		String creator = creatorIdOf(session, html);
		String other = otherIdOf(session, html);

		// Before any expense there is no payment instruction, so the only place
		// the creator's key appears is the masked participant list.
		assertThat(html).contains("l•••@example.com");
		assertThat(html).doesNotContain(">luiz@example.com<");

		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Mercado")
				.param("paidByParticipantId", creator)
				.param("totalReais", "100,00")
				.param("share-" + creator, "50,00")
				.param("share-" + other, "50,00"))
				.andExpect(status().is3xxRedirection());

		String withPayment = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();
		assertThat(withPayment).contains("luiz@example.com");
	}

	/** The creator is the first participant rendered; ids come from the share inputs. */
	private String creatorIdOf(Session session, String html) {
		return shareIds(html).get(0);
	}

	private String otherIdOf(Session session, String html) {
		return shareIds(html).get(1);
	}

	private java.util.List<String> shareIds(String html) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("name=\"share-([0-9a-f-]{36})\"").matcher(html);
		java.util.List<String> ids = new java.util.ArrayList<>();
		while (matcher.find()) {
			ids.add(matcher.group(1));
		}
		return ids;
	}

}
