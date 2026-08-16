#!/usr/bin/env bash
#
# Seeds the deterministic demo group used by the recorded walkthrough
# (scripts/record-demo.sh) and prints its invite URL on stdout.
#
# The expense history is chosen so the settlement strategies genuinely
# differ on this group:
#
#   balances   Ana -600, Bruno -400, Clara +600, Diego +400, Elisa 0
#   related    Ana-Diego and Bruno-Clara (among people with open balances)
#
# The two-transfer minimum (Ana→Clara, Bruno→Diego) needs two brand-new
# payment pairs, while the relationship-aware optimum spends a third
# transfer to get away with one. Elisa exists to make that state reachable
# from real expenses: her boat trip and farewell dinner net to zero but
# shift credit between Diego and Clara without relating them to Ana or
# Bruno. All identities are synthetic.
#
# Usage: BASE_URL=http://localhost:8080 ./scripts/seed-demo.sh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api/v1"

jsget() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

say() { printf '%s\n' "$*" >&2; }

post() { # PATH BODY [IDEMPOTENCY_KEY]
	local args=(-sf -X POST -H "Content-Type: application/json" -d "$2")
	[ -n "${3:-}" ] && args+=(-H "Idempotency-Key: $3")
	curl "${args[@]}" "$API$1"
}

GROUP_JSON="$(post /groups '{
  "groupName": "Fim de semana em Búzios",
  "creatorName": "Ana",
  "pixKeyType": "EMAIL",
  "pixKeyValue": "ana@example.com"
}')"
G="$(printf '%s' "$GROUP_JSON" | jsget groupId)"
T="$(printf '%s' "$GROUP_JSON" | jsget inviteToken)"
ANA="$(printf '%s' "$GROUP_JSON" | jsget creatorParticipantId)"
say "grupo criado: $G"

add() { # NAME TYPE VALUE -> participant id
	post "/groups/$G/participants?token=$T" \
		"$(printf '{"displayName": "%s", "pixKeyType": "%s", "pixKeyValue": "%s"}' "$1" "$2" "$3")" \
		| jsget participantId
}
BRUNO="$(add Bruno RANDOM 'c3f8a1b2-1111-4a2b-9c3d-000000000001')"
CLARA="$(add Clara EMAIL 'clara@example.com')"
DIEGO="$(add Diego PHONE '+5521999990004')"
ELISA="$(add Elisa PHONE '+5521999990005')"
say "participantes: Ana, Bruno, Clara, Diego, Elisa"

expense() { # KEY DESCRIPTION PAYER TOTAL SHARES_JSON
	post "/groups/$G/expenses?token=$T" "$(printf \
		'{"description": "%s", "paidByParticipantId": "%s", "totalCents": %d, "shares": [%s]}' \
		"$2" "$3" "$4" "$5")" "$1" > /dev/null
	say "despesa: $2"
}
share() { printf '{"participantId": "%s", "amountCents": %d}' "$1" "$2"; }

expense pousada "Pousada em Búzios" "$DIEGO" 100000 "$(share "$ANA" 60000),$(share "$DIEGO" 40000)"
expense mercado "Mercado e churrasco" "$CLARA" 50000 "$(share "$BRUNO" 40000),$(share "$CLARA" 10000)"
expense barco "Passeio de barco" "$ELISA" 20000 "$(share "$DIEGO" 20000)"
expense jantar "Jantar de despedida" "$CLARA" 20000 "$(share "$ELISA" 20000)"

# Sanity: the recorded story depends on the strategies actually diverging.
curl -sf "$API/groups/$G/settlement-plan/compare?token=$T" | python3 -c '
import json, sys
plans = {p["strategy"]: p for p in json.load(sys.stdin)["plans"]}
aware, fewest = plans["RELATIONSHIP_AWARE"], plans["MIN_TRANSFERS"]
assert fewest["transferCount"] == 2 and fewest["novelRelationshipEdges"] == 2, fewest
assert aware["transferCount"] == 3 and aware["novelRelationshipEdges"] == 1, aware
print("estrategias divergem: 2 transferencias/2 pares novos vs 3/1", file=sys.stderr)
'

printf '%s/g/%s?token=%s\n' "$BASE_URL" "$G" "$T"
