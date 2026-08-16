#!/usr/bin/env bash
#
# SplitPix — demonstração da API.
#
# Percorre o fluxo completo: cria um grupo, adiciona participantes, registra a
# despesa do exemplo do documento (R$ 420,00 divididos de forma desigual),
# mostra saldos, o plano de quitação sugerido e o extrato que explica um
# saldo, prova a idempotência, quita um pagamento e mostra que um pagamento
# acima da dívida é recusado.
#
# Uso:  ./demo.sh                       (usa http://localhost:8080)
#       ./demo.sh --tecnico             (inclui o ato 2: otimização de planos)
#       BASE_URL=https://... ./demo.sh  (instância remota)
#
# Requisitos: curl e (jq ou python3).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"
TECNICO=0
[ "${1:-}" = "--tecnico" ] && TECNICO=1

if command -v jq >/dev/null 2>&1; then
	json_get() { jq -r "$1"; }
elif command -v python3 >/dev/null 2>&1; then
	json_get() {
		python3 -c '
import json, sys
value = json.load(sys.stdin)
for part in sys.argv[1].lstrip(".").split("."):
    if part.endswith("]"):
        name, index = part[:-1].split("[")
        value = value[name][int(index)] if name else value[int(index)]
    else:
        value = value[part]
print("" if value is None else value)' "$1"
	}
else
	echo "erro: instale jq ou python3 para executar esta demonstração." >&2
	exit 1
fi

# ---------------------------------------------------------------- utilidades

BODY=""
STATUS=""

request() { # METODO CAMINHO [CORPO] [CHAVE_IDEMPOTENCIA]
	local method="$1" url="$2" body="${3:-}" key="${4:-}"
	local args=(-s -X "$method" -H "Content-Type: application/json" -w '\n%{http_code}')
	[ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
	[ -n "$body" ] && args+=(-d "$body")
	local response
	response="$(curl "${args[@]}" "$url")"
	STATUS="${response##*$'\n'}"
	BODY="${response%$'\n'*}"
}

brl() { # centavos -> R$ x,yz
	local cents="$1" sign=""
	if [ "$cents" -lt 0 ]; then
		sign="-"
		cents=$((-cents))
	fi
	printf '%sR$ %d,%02d' "$sign" $((cents / 100)) $((cents % 100))
}

section() {
	printf '\n\033[1m== %s\033[0m\n' "$1"
}

mostrar_plano() { # imprime as transferências do plano em $BODY
	local count i
	count="$(printf '%s' "$BODY" | json_get .plan.transferCount)"
	for i in $(seq 0 $((count - 1))); do
		local payer recipient key cents
		payer="$(printf '%s' "$BODY" | json_get ".plan.transfers[$i].payerName")"
		recipient="$(printf '%s' "$BODY" | json_get ".plan.transfers[$i].recipientName")"
		key="$(printf '%s' "$BODY" | json_get ".plan.transfers[$i].recipientPixKey")"
		cents="$(printf '%s' "$BODY" | json_get ".plan.transfers[$i].amountCents")"
		printf '  %-6s paga %10s para %-6s (chave Pix: %s)\n' \
			"$payer" "$(brl "$cents")" "$recipient" "${key:-—}"
	done
}

# ------------------------------------------------------------------- espera

printf 'Conectando em %s' "$BASE_URL"
for _ in $(seq 1 30); do
	if curl -sf "$API/ping" >/dev/null 2>&1; then
		printf ' — ok\n'
		break
	fi
	printf '.'
	sleep 2
done
curl -sf "$API/ping" >/dev/null 2>&1 || {
	printf '\nerro: a aplicação não respondeu em %s\n' "$BASE_URL" >&2
	exit 1
}

# --------------------------------------------------------- ato 1: o cotidiano

section "1. Criando o grupo"
request POST "$API/groups" '{
  "groupName": "Jantar no Rio",
  "creatorName": "Luiz",
  "pixKeyType": "EMAIL",
  "pixKeyValue": "luiz@example.com"
}'
GROUP_ID="$(printf '%s' "$BODY" | json_get .groupId)"
TOKEN="$(printf '%s' "$BODY" | json_get .inviteToken)"
LUIZ="$(printf '%s' "$BODY" | json_get .creatorParticipantId)"
printf 'Grupo criado (HTTP %s): %s\n' "$STATUS" "$GROUP_ID"
printf 'Token de convite: %s...\n' "${TOKEN:0:12}"

section "2. Adicionando participantes"
PARTICIPANT_ID=""
add_participant() { # NOME TIPO_CHAVE VALOR_CHAVE
	request POST "$API/groups/$GROUP_ID/participants?token=$TOKEN" \
		"$(printf '{"displayName": "%s", "pixKeyType": "%s", "pixKeyValue": "%s"}' "$1" "$2" "$3")"
	PARTICIPANT_ID="$(printf '%s' "$BODY" | json_get .participantId)"
	printf '  %-6s adicionado(a) (HTTP %s) — chave %s: %s\n' "$1" "$STATUS" "$2" "$3"
}
add_participant Ana PHONE '+5511999990001'
ANA="$PARTICIPANT_ID"
add_participant Bruno EMAIL 'bruno@example.com'
BRUNO="$PARTICIPANT_ID"
add_participant Clara RANDOM 'a1b2c3d4-0000-4000-8000-000000000001'
CLARA="$PARTICIPANT_ID"
add_participant Diego PHONE '+5511999990004'
DIEGO="$PARTICIPANT_ID"

section "3. Registrando a despesa (R\$ 420,00, divisão desigual)"
EXPENSE_BODY="$(cat <<JSON
{
  "description": "Jantar no Rio",
  "paidByParticipantId": "$LUIZ",
  "totalCents": 42000,
  "shares": [
    {"participantId": "$LUIZ",  "amountCents": 7000},
    {"participantId": "$ANA",   "amountCents": 9000},
    {"participantId": "$BRUNO", "amountCents": 8000},
    {"participantId": "$CLARA", "amountCents": 6000},
    {"participantId": "$DIEGO", "amountCents": 12000}
  ]
}
JSON
)"
request POST "$API/groups/$GROUP_ID/expenses?token=$TOKEN" "$EXPENSE_BODY" "despesa-jantar-001"
printf 'Despesa registrada (HTTP %s): Luiz pagou %s\n' "$STATUS" "$(brl 42000)"

section "4. Reenviando a mesma requisição (idempotência)"
request POST "$API/groups/$GROUP_ID/expenses?token=$TOKEN" "$EXPENSE_BODY" "despesa-jantar-001"
printf 'Mesma chave de idempotência: HTTP %s (200 = despesa existente, nada duplicado)\n' "$STATUS"

section "5. Saldos"
request GET "$API/groups/$GROUP_ID/balances?token=$TOKEN"
TOTAL=0
for i in 0 1 2 3 4; do
	NAME="$(printf '%s' "$BODY" | json_get ".balances[$i].displayName")"
	CENTS="$(printf '%s' "$BODY" | json_get ".balances[$i].balanceCents")"
	printf '  %-8s %12s\n' "$NAME" "$(brl "$CENTS")"
	TOTAL=$((TOTAL + CENTS))
done
printf '  %-8s %12s  <- a soma dos saldos é sempre zero\n' "TOTAL" "$(brl $TOTAL)"

section "6. Plano de quitação sugerido"
request GET "$API/groups/$GROUP_ID/settlement-plan?token=$TOKEN"
mostrar_plano
printf '  (revisão do livro: %s — o plano é derivado dos saldos, nunca armazenado)\n' \
	"$(printf '%s' "$BODY" | json_get .ledgerRevision)"

section "7. Por que a Ana deve R\$ 90,00? (extrato de saldo)"
request GET "$API/groups/$GROUP_ID/participants/$ANA/balance-explanation?token=$TOKEN"
printf '  %-22s %12s  (%s)\n' \
	"$(printf '%s' "$BODY" | json_get '.entries[0].type')" \
	"$(brl "$(printf '%s' "$BODY" | json_get '.entries[0].amountCents')")" \
	"$(printf '%s' "$BODY" | json_get '.entries[0].description')"
printf '  saldo = soma das linhas: %s\n' \
	"$(brl "$(printf '%s' "$BODY" | json_get .balanceCents)")"

section "8. Ana confirma o pagamento de R\$ 90,00"
request POST "$API/groups/$GROUP_ID/settlements?token=$TOKEN" \
	"$(printf '{"payerParticipantId": "%s", "recipientParticipantId": "%s", "amountCents": 9000}' "$ANA" "$LUIZ")" \
	"pagamento-ana-luiz-001"
printf 'Pagamento registrado (HTTP %s)\n' "$STATUS"

request GET "$API/groups/$GROUP_ID/balances?token=$TOKEN"
for i in 0 1; do
	NAME="$(printf '%s' "$BODY" | json_get ".balances[$i].displayName")"
	CENTS="$(printf '%s' "$BODY" | json_get ".balances[$i].balanceCents")"
	printf '  %-8s %12s\n' "$NAME" "$(brl "$CENTS")"
done

section "9. Tentativa de pagar mais do que se deve"
request POST "$API/groups/$GROUP_ID/settlements?token=$TOKEN" \
	"$(printf '{"payerParticipantId": "%s", "recipientParticipantId": "%s", "amountCents": 50000}' "$BRUNO" "$LUIZ")" \
	"pagamento-bruno-invalido"
printf 'HTTP %s\n%s\n' "$STATUS" "$BODY"

section "10. Histórico (o livro, em ordem de serialização)"
request GET "$API/groups/$GROUP_ID/activity?token=$TOKEN"
for i in 0 1; do
	SEQ="$(printf '%s' "$BODY" | json_get ".items[$i].sequence")"
	TYPE="$(printf '%s' "$BODY" | json_get ".items[$i].type")"
	CENTS="$(printf '%s' "$BODY" | json_get ".items[$i].amountCents")"
	printf '  #%-2s %-11s %12s\n' "$SEQ" "$TYPE" "$(brl "$CENTS")"
done

if [ "$TECNICO" -eq 0 ]; then
	printf '\nFim da demonstração. Nenhum pagamento Pix real foi feito.\n'
	printf '(Rode ./demo.sh --tecnico para o ato 2: estratégias de otimização.)\n'
	exit 0
fi

# ----------------------------------------- ato 2: otimização (--tecnico)

section "T1. Um grupo onde a estratégia gulosa erra"
# Saldos: Ana +500, Bruno +400, Clara -400, Diego -300, Elisa -200.
# O plano guloso precisa de 4 transferências; o mínimo verdadeiro é 3.
request POST "$API/groups" '{"groupName": "Viagem", "creatorName": "Ana"}'
G2="$(printf '%s' "$BODY" | json_get .groupId)"
T2="$(printf '%s' "$BODY" | json_get .inviteToken)"
A2="$(printf '%s' "$BODY" | json_get .creatorParticipantId)"
p2() {
	request POST "$API/groups/$G2/participants?token=$T2" \
		"$(printf '{"displayName": "%s"}' "$1")"
	PARTICIPANT_ID="$(printf '%s' "$BODY" | json_get .participantId)"
}
p2 Bruno;  B2="$PARTICIPANT_ID"
p2 Clara;  C2="$PARTICIPANT_ID"
p2 Diego;  D2="$PARTICIPANT_ID"
p2 Elisa;  E2="$PARTICIPANT_ID"

request POST "$API/groups/$G2/expenses?token=$T2" "$(cat <<JSON
{"description": "Hotel", "paidByParticipantId": "$A2", "totalCents": 50000,
 "shares": [{"participantId": "$D2", "amountCents": 30000},
            {"participantId": "$E2", "amountCents": 20000}]}
JSON
)" "hotel-001"
request POST "$API/groups/$G2/expenses?token=$T2" "$(cat <<JSON
{"description": "Carro", "paidByParticipantId": "$B2", "totalCents": 40000,
 "shares": [{"participantId": "$C2", "amountCents": 40000}]}
JSON
)" "carro-001"
printf 'Grupo montado: Ana +500, Bruno +400, Clara -400, Diego -300, Elisa -200\n'

section "T2. Comparando as três estratégias"
request GET "$API/groups/$G2/settlement-plan/compare?token=$T2"
for i in 0 1 2; do
	STRAT="$(printf '%s' "$BODY" | json_get ".plans[$i].strategy")"
	N="$(printf '%s' "$BODY" | json_get ".plans[$i].transferCount")"
	NOVEL="$(printf '%s' "$BODY" | json_get ".plans[$i].novelRelationshipEdges")"
	EXACT="$(printf '%s' "$BODY" | json_get ".plans[$i].exact" | tr '[:upper:]' '[:lower:]')"
	printf '  %-20s %s transferências, %s pares novos, exato=%s\n' "$STRAT" "$N" "$NOVEL" "$EXACT"
done
printf 'O mesmo vetor de saldos admite planos diferentes; a escolha é explícita.\n'

section "T3. Plano com restrição: Diego não pode pagar a Ana"
request POST "$API/groups/$G2/settlement-plan?token=$T2" "$(cat <<JSON
{"strategy": "MIN_TRANSFERS",
 "constraints": {"forbiddenPairs": [
   {"payerParticipantId": "$D2", "recipientParticipantId": "$A2"}]}}
JSON
)"
mostrar_plano

section "T4. Restrições sem plano possível"
request POST "$API/groups/$G2/settlement-plan?token=$T2" "$(cat <<JSON
{"strategy": "MIN_TRANSFERS",
 "constraints": {"forbiddenPairs": [
   {"payerParticipantId": "$C2", "recipientParticipantId": "$A2"},
   {"payerParticipantId": "$C2", "recipientParticipantId": "$B2"}]}}
JSON
)"
printf 'HTTP %s\n%s\n' "$STATUS" "$BODY"

printf '\nFim da demonstração. Nenhum pagamento Pix real foi feito.\n'
