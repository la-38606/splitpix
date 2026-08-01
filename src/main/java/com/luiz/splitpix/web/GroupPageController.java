package com.luiz.splitpix.web;

import com.luiz.splitpix.activity.ActivityService;
import com.luiz.splitpix.balance.BalanceService;
import com.luiz.splitpix.balance.ParticipantBalance;
import com.luiz.splitpix.expense.CreateExpenseRequest;
import com.luiz.splitpix.expense.ExpenseService;
import com.luiz.splitpix.group.CreateGroupRequest;
import com.luiz.splitpix.group.CreateGroupResult;
import com.luiz.splitpix.group.GroupService;
import com.luiz.splitpix.group.GroupView;
import com.luiz.splitpix.participant.AddParticipantRequest;
import com.luiz.splitpix.participant.Participant;
import com.luiz.splitpix.participant.ParticipantService;
import com.luiz.splitpix.participant.PixKeyType;
import com.luiz.splitpix.settlement.CompleteSettlementRequest;
import com.luiz.splitpix.settlement.SettlementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The browser-facing pages (addendum 36). Every write is Post/Redirect/Get with
 * an idempotency key minted when the form is rendered, so a refresh cannot
 * resubmit and a back-button edit is a conflict rather than silent data loss.
 *
 * These controllers hold no business logic: they call the same services the
 * REST API calls, and the REST API remains the primary interface.
 */
@Controller
public class GroupPageController {

	private final GroupService groupService;
	private final ParticipantService participantService;
	private final ExpenseService expenseService;
	private final BalanceService balanceService;
	private final SettlementService settlementService;
	private final ActivityService activityService;

	public GroupPageController(GroupService groupService, ParticipantService participantService,
			ExpenseService expenseService, BalanceService balanceService,
			SettlementService settlementService, ActivityService activityService) {
		this.groupService = groupService;
		this.participantService = participantService;
		this.expenseService = expenseService;
		this.balanceService = balanceService;
		this.settlementService = settlementService;
		this.activityService = activityService;
	}

	@GetMapping("/")
	public String home() {
		return "home";
	}

	@PostMapping("/grupos")
	public String createGroup(@RequestParam String groupName, @RequestParam String creatorName,
			@RequestParam(required = false) String pixKeyType, @RequestParam(required = false) String pixKeyValue,
			HttpServletRequest request, HttpServletResponse response) {
		CreateGroupResult result = groupService.create(new CreateGroupRequest(
				groupName, creatorName, parseKeyType(pixKeyType, pixKeyValue), emptyToNull(pixKeyValue)));
		InviteCookie.set(request, response, result.groupId(), result.inviteToken());
		return "redirect:/g/" + result.groupId() + "?novo";
	}

	/** Invite link: exchanges the token for a cookie, then redirects to a token-free URL. */
	@GetMapping(value = "/g/{groupId}", params = "token")
	public String openInvite(@PathVariable UUID groupId, @RequestParam String token,
			HttpServletRequest request, HttpServletResponse response) {
		groupService.requireGroup(groupId, token);
		InviteCookie.set(request, response, groupId, token);
		return "redirect:/g/" + groupId;
	}

	@GetMapping("/g/{groupId}")
	public String groupPage(@PathVariable UUID groupId, HttpServletRequest request, Model model) {
		model.addAttribute("page", loadPage(groupId, InviteCookie.require(request)));
		model.addAttribute("keyTypes", PixKeyType.values());
		model.addAttribute("inviteLink", inviteLink(request, groupId));
		return "group";
	}

	@PostMapping("/g/{groupId}/participantes")
	public String addParticipant(@PathVariable UUID groupId, @RequestParam String displayName,
			@RequestParam(required = false) String pixKeyType, @RequestParam(required = false) String pixKeyValue,
			HttpServletRequest request, RedirectAttributes redirect) {
		participantService.add(groupId, InviteCookie.require(request), new AddParticipantRequest(
				displayName, parseKeyType(pixKeyType, pixKeyValue), emptyToNull(pixKeyValue)));
		redirect.addFlashAttribute("aviso", "participante.adicionado");
		return "redirect:/g/" + groupId;
	}

	@PostMapping("/g/{groupId}/despesas")
	public String addExpense(@PathVariable UUID groupId, @RequestParam String description,
			@RequestParam UUID paidByParticipantId, @RequestParam String totalReais,
			@RequestParam Map<String, String> form, @RequestParam String idempotencyKey,
			HttpServletRequest request, RedirectAttributes redirect) {
		List<CreateExpenseRequest.ShareRequest> shares = new ArrayList<>();
		form.forEach((field, value) -> {
			if (field.startsWith("share-") && !value.isBlank()) {
				shares.add(new CreateExpenseRequest.ShareRequest(
						UUID.fromString(field.substring("share-".length())), cents(value)));
			}
		});

		expenseService.create(groupId, InviteCookie.require(request), idempotencyKey,
				new CreateExpenseRequest(description, paidByParticipantId, cents(totalReais), shares));
		redirect.addFlashAttribute("aviso", "despesa.registrada");
		return "redirect:/g/" + groupId;
	}

	@PostMapping("/g/{groupId}/pagamentos")
	public String completePayment(@PathVariable UUID groupId, @RequestParam UUID payerParticipantId,
			@RequestParam UUID recipientParticipantId, @RequestParam long amountCents,
			@RequestParam String idempotencyKey, HttpServletRequest request, RedirectAttributes redirect) {
		settlementService.complete(groupId, InviteCookie.require(request), idempotencyKey,
				new CompleteSettlementRequest(payerParticipantId, recipientParticipantId, amountCents));
		redirect.addFlashAttribute("aviso", "pagamento.registrado");
		return "redirect:/g/" + groupId;
	}

	private GroupPage loadPage(UUID groupId, String token) {
		GroupView view = groupService.getView(groupId, token);
		List<ParticipantBalance> balances = balanceService.getBalances(groupId, token);

		Map<UUID, String> names = new LinkedHashMap<>();
		for (Participant participant : view.participants()) {
			names.put(participant.id(), participant.displayName());
		}
		long outstanding = balances.stream()
				.mapToLong(ParticipantBalance::balanceCents)
				.filter(cents -> cents > 0)
				.sum();

		return new GroupPage(view.group(), view.participants(), balances,
				balanceService.getSuggestedPayments(groupId, token),
				activityService.getActivity(groupId, token).reversed(),
				names, outstanding,
				UUID.randomUUID().toString(), UUID.randomUUID().toString());
	}

	private static String inviteLink(HttpServletRequest request, UUID groupId) {
		String base = request.getRequestURL().toString();
		int path = base.indexOf("/g/");
		return (path > 0 ? base.substring(0, path) : base) + "/g/" + groupId + "?token=";
	}

	private static PixKeyType parseKeyType(String type, String value) {
		return type == null || type.isBlank() || value == null || value.isBlank()
				? null : PixKeyType.valueOf(type);
	}

	private static String emptyToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** Accepts "70", "70,50" and "70.50" — all three are natural to type. */
	private static long cents(String reais) {
		String normalized = reais.strip().replace(".", "").replace(',', '.');
		java.math.BigDecimal value = new java.math.BigDecimal(normalized)
				.setScale(2, java.math.RoundingMode.UNNECESSARY);
		return value.movePointRight(2).longValueExact();
	}

}
