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
		String groupId = location.substring("/g/".length());
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
				.andExpect(status().isForbidden())
				.andExpect(view().name("erro"))
				.andExpect(cookie().doesNotExist(COOKIE))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_INVITE_TOKEN")));
	}

	@Test
	void groupPage_withoutCookie_showsErrorPage() throws Exception {
		var group = createGroup();
		mockMvc.perform(get("/g/" + group.get("groupId").asText()))
				.andExpect(status().isForbidden())
				.andExpect(view().name("erro"));
	}

	@Test
	void fullBrowserLoop_fromCreationToSettlement() throws Exception {
		Session session = createGroupViaForm();
		addParticipantViaForm(session, "Ana");
		addParticipantViaForm(session, "Bia");

		MvcResult page = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andExpect(view().name("group"))
				.andReturn();
		String html = page.getResponse().getContentAsString();
		assertThat(html).contains("República").contains("Ana").contains("Luiz");

		// Submit what the browser would submit: the key the page rendered, and
		// an empty share field for Bia (real forms send every input).
		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", expenseFormKey(html))
				.param("description", "Mercado")
				.param("paidByParticipantId", creatorIdOf(session, html))
				.param("totalReais", "100,00")
				.param("share-" + creatorIdOf(session, html), "50,00")
				.param("share-" + otherIdOf(session, html), "50,00")
				.param("share-" + shareIds(html).get(2), ""))
				.andExpect(status().is3xxRedirection());

		String withExpense = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(withExpense).contains("luiz@example.com");
		// The "Em aberto" headline is the sum of positive balances, not the
		// zero-sum total.
		assertThat(outstandingOf(withExpense)).isEqualTo("50,00");

		// Complete the payment exactly as the rendered form would.
		java.util.Map<String, String> payment = paymentForms(withExpense).get(0);
		mockMvc.perform(post("/g/" + session.groupId() + "/pagamentos")
				.cookie(session.cookie())
				.param("idempotencyKey", payment.get("idempotencyKey"))
				.param("payerParticipantId", payment.get("payerParticipantId"))
				.param("recipientParticipantId", payment.get("recipientParticipantId"))
				.param("amountCents", payment.get("amountCents")))
				.andExpect(status().is3xxRedirection());

		String settled = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		// Everything is squared up, so the suggestions list gives way to its empty state.
		assertThat(settled).contains("Tudo quitado");
		assertThat(outstandingOf(settled)).isEqualTo("0,00");
		// History renders newest first: the settlement row above the expense row.
		// ">Pagamento<" is the history type cell; the "Pagamentos sugeridos"
		// heading would match a bare "Pagamento" and make this vacuous.
		assertThat(settled.indexOf(">Pagamento<")).isPositive();
		assertThat(settled.indexOf(">Pagamento<")).isLessThan(settled.indexOf("Mercado"));
	}

	@Test
	void inviteLinkCopiedFromThePage_letsAFreshClientJoin() throws Exception {
		// The copy button carries the full invite URL, token included — it is
		// the only way a second person joins through the browser.
		Session session = createGroupViaForm();
		String html = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();

		java.util.regex.Matcher link = java.util.regex.Pattern
				.compile("data-copiar=\"([^\"]*\\?token=[^\"]+)\"").matcher(html);
		assertThat(link.find()).as("copy button must carry the invite URL with its token").isTrue();

		// A fresh client (no cookie) opening the copied link gets the cookie
		// exchange and lands on the token-free page.
		mockMvc.perform(get(link.group(1)))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", "/g/" + session.groupId()))
				.andExpect(cookie().exists(COOKIE));
	}

	@Test
	void inviteCookie_carriesItsProtectionAttributes() throws Exception {
		var group = createGroup("Rio", "Luiz");
		String setCookie = mockMvc.perform(get("/g/" + group.get("groupId").asText())
				.param("token", group.get("inviteToken").asText())
				.secure(true))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getHeader("Set-Cookie");

		assertThat(setCookie)
				.contains("HttpOnly")
				.contains("SameSite=Lax")
				.contains("Max-Age=43200")
				.contains("Secure")
				.doesNotContain("Path=/;");
	}

	@Test
	void clientInputMistakes_are400ErrorPages_notAlerts() throws Exception {
		Session session = createGroupViaForm();

		// Malformed group id in the URL.
		mockMvc.perform(get("/g/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("erro"));

		// Missing required form field.
		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Mercado")
				.param("paidByParticipantId", UUID.randomUUID().toString()))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("erro"));

		// A share field whose suffix is not a UUID.
		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Mercado")
				.param("paidByParticipantId", UUID.randomUUID().toString())
				.param("totalReais", "10,00")
				.param("share-notauuid", "10,00"))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("erro"));
	}

	@Test
	void pixTypeWithoutValue_onTheForm_isAnErrorPage_notASilentDrop() throws Exception {
		// The page tier must enforce the same pair rule as the API; silently
		// adding the participant keyless would be data loss with a success flash.
		Session session = createGroupViaForm();
		mockMvc.perform(post("/g/" + session.groupId() + "/participantes")
				.cookie(session.cookie())
				.param("displayName", "Ana")
				.param("pixKeyType", "EMAIL")
				.param("pixKeyValue", ""))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("erro"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_PIX_KEY_PAIR")));

		Integer participants = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM participants WHERE group_id = ?::uuid", Integer.class, session.groupId());
		assertThat(participants).isEqualTo(1);
	}

	@Test
	void tamperedPixKeyTypeSelect_isA400_notA500() throws Exception {
		Session session = createGroupViaForm();
		mockMvc.perform(post("/g/" + session.groupId() + "/participantes")
				.cookie(session.cookie())
				.param("displayName", "Ana")
				.param("pixKeyType", "CPF")
				.param("pixKeyValue", "12345678900"))
				.andExpect(status().isBadRequest())
				.andExpect(view().name("erro"));
	}

	@Test
	void unknownGroup_withACookie_isA404Page() throws Exception {
		Session session = createGroupViaForm();
		mockMvc.perform(get("/g/" + UUID.randomUUID()).cookie(session.cookie()))
				.andExpect(status().isNotFound())
				.andExpect(view().name("erro"));
	}

	/** The idempotency key the rendered expense form carries. */
	private String expenseFormKey(String html) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("(?s)despesas.*?name=\"idempotencyKey\" value=\"([^\"]+)\"").matcher(html);
		assertThat(matcher.find()).as("expense form must render an idempotency key").isTrue();
		return matcher.group(1);
	}

	/** The value inside the \"Em aberto\" headline. */
	private String outstandingOf(String html) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("(?s)cifra-grande.*?</span><span[^>]*>([^<]+)</span>").matcher(html);
		assertThat(matcher.find()).as("balance band must render").isTrue();
		return matcher.group(1);
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
				.andExpect(status().isConflict())
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

	@Test
	void onePayerOwingTwoCreditors_canMarkBothPaidFromOneRender() throws Exception {
		// The greedy simplifier emits two payments from the SAME payer here; the
		// two rendered forms must carry distinct idempotency keys or the second
		// click dies with a spurious conflict.
		Session session = createGroupViaForm();
		addParticipantViaForm(session, "Ana");
		addParticipantViaForm(session, "Bia");
		String html = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();
		java.util.List<String> ids = shareIds(html);
		String luiz = ids.get(0);
		String ana = ids.get(1);
		String bia = ids.get(2);

		// Ana pays 60, Bia pays 40, everything consumed by Luiz: Luiz owes both.
		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Mercado")
				.param("paidByParticipantId", ana)
				.param("totalReais", "60,00")
				.param("share-" + luiz, "60,00"))
				.andExpect(status().is3xxRedirection());
		mockMvc.perform(post("/g/" + session.groupId() + "/despesas")
				.cookie(session.cookie())
				.param("idempotencyKey", UUID.randomUUID().toString())
				.param("description", "Uber")
				.param("paidByParticipantId", bia)
				.param("totalReais", "40,00")
				.param("share-" + luiz, "40,00"))
				.andExpect(status().is3xxRedirection());

		String page = mockMvc.perform(get("/g/" + session.groupId()).cookie(session.cookie()))
				.andReturn().getResponse().getContentAsString();
		java.util.List<java.util.Map<String, String>> forms = paymentForms(page);
		assertThat(forms).hasSize(2);
		assertThat(forms.get(0).get("idempotencyKey"))
				.isNotEqualTo(forms.get(1).get("idempotencyKey"));

		for (java.util.Map<String, String> form : forms) {
			mockMvc.perform(post("/g/" + session.groupId() + "/pagamentos")
					.cookie(session.cookie())
					.param("payerParticipantId", form.get("payerParticipantId"))
					.param("recipientParticipantId", form.get("recipientParticipantId"))
					.param("amountCents", form.get("amountCents"))
					.param("idempotencyKey", form.get("idempotencyKey")))
					.andExpect(status().is3xxRedirection());
		}

		Integer settlements = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM settlements WHERE group_id = ?::uuid", Integer.class, session.groupId());
		assertThat(settlements).isEqualTo(2);
	}

	/** Extracts each payment form's hidden fields, in document order. */
	private java.util.List<java.util.Map<String, String>> paymentForms(String html) {
		java.util.List<java.util.Map<String, String>> forms = new java.util.ArrayList<>();
		java.util.regex.Matcher form = java.util.regex.Pattern
				.compile("(?s)<form method=\"post\"[^>]*pagamentos.*?</form>").matcher(html);
		while (form.find()) {
			java.util.Map<String, String> fields = new java.util.HashMap<>();
			java.util.regex.Matcher field = java.util.regex.Pattern
					.compile("name=\"([a-zA-Z]+)\" value=\"([^\"]+)\"").matcher(form.group());
			while (field.find()) {
				fields.put(field.group(1), field.group(2));
			}
			forms.add(fields);
		}
		return forms;
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
