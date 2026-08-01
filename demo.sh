#!/usr/bin/env bash
#
# SplitPix — demonstração da API (design doc, seção 21).
#
# Percorre o fluxo completo: cria um grupo, adiciona participantes, registra a
# despesa do exemplo do documento (R$ 420,00 divididos de forma desigual),
# mostra saldos e pagamentos sugeridos, prova a idempotência, quita um
# pagamento e mostra que um pagamento acima da dívida é recusado.
#
# Uso:  ./demo.sh                       (usa http://localhost:8080)
#       BASE_URL=https://... ./demo.sh  (instância remota)
#
# Requisitos: curl e (jq ou python3).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"

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

# ------------------------------------------------------- grupo (seções 1-2)

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

# ---------------------------------------------------- despesa (seções 3-4)

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

# ---------------------------------------------------- balanço (seções 5-6)

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

section "6. Pagamentos sugeridos"
request GET "$API/groups/$GROUP_ID/suggested-payments?token=$TOKEN"
for i in 0 1 2 3; do
	PAYER="$(printf '%s' "$BODY" | json_get ".payments[$i].payerName")"
	RECIPIENT="$(printf '%s' "$BODY" | json_get ".payments[$i].recipientName")"
	KEY="$(printf '%s' "$BODY" | json_get ".payments[$i].recipientPixKey")"
	CENTS="$(printf '%s' "$BODY" | json_get ".payments[$i].amountCents")"
	printf '  %-6s paga %10s para %-6s (chave Pix: %s)\n' "$PAYER" "$(brl "$CENTS")" "$RECIPIENT" "$KEY"
done

# --------------------------------------------------- quitação (seções 7-9)

section "7. Ana confirma o pagamento de R\$ 90,00"
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

section "8. Tentativa de pagar mais do que se deve"
request POST "$API/groups/$GROUP_ID/settlements?token=$TOKEN" \
	"$(printf '{"payerParticipantId": "%s", "recipientParticipantId": "%s", "amountCents": 50000}' "$BRUNO" "$LUIZ")" \
	"pagamento-bruno-invalido"
printf 'HTTP %s\n%s\n' "$STATUS" "$BODY"

section "9. Histórico"
request GET "$API/groups/$GROUP_ID/activity?token=$TOKEN"
for i in 0 1; do
	TYPE="$(printf '%s' "$BODY" | json_get ".items[$i].type")"
	CENTS="$(printf '%s' "$BODY" | json_get ".items[$i].amountCents")"
	printf '  %-11s %12s\n' "$TYPE" "$(brl "$CENTS")"
done

printf '\nFim da demonstração. Nenhum pagamento Pix real foi feito.\n'
