#!/usr/bin/env bash
#
# Records the demo video end to end:
#
#   1. starts SplitPix (unless SPLITPIX_URL points at a running instance);
#   2. seeds the deterministic demo group (scripts/seed-demo.sh);
#   3. runs the Playwright walkthrough (e2e/demo.spec.ts) with video on;
#   4. leaves the recording at docs/demo/splitpix-demo.webm
#      (plus .mp4 when an H.264-capable ffmpeg is on PATH).
#
# Needs Java 21 + Docker (for the app) and Node (for Playwright). The first
# run downloads the Playwright Chromium build. Only processes started by
# this script are stopped by it.

set -euo pipefail
cd "$(dirname "$0")/.."

APP_PID=""
cleanup() {
	if [ -n "$APP_PID" ]; then
		kill "$APP_PID" 2>/dev/null || true
		wait "$APP_PID" 2>/dev/null || true
	fi
}
trap cleanup EXIT

BASE_URL="${SPLITPIX_URL:-http://localhost:8080}"
if ! curl -sf "$BASE_URL/api/v1/ping" > /dev/null 2>&1; then
	if [ -n "${SPLITPIX_URL:-}" ]; then
		echo "erro: $SPLITPIX_URL não responde" >&2
		exit 1
	fi
	echo "subindo a aplicação (mvnw spring-boot:test-run)..." >&2
	./mvnw -q spring-boot:test-run > /tmp/splitpix-demo-app.log 2>&1 &
	APP_PID=$!
	for _ in $(seq 1 120); do
		curl -sf "$BASE_URL/api/v1/ping" > /dev/null 2>&1 && break
		sleep 2
	done
	curl -sf "$BASE_URL/api/v1/ping" > /dev/null || {
		echo "erro: aplicação não subiu; veja /tmp/splitpix-demo-app.log" >&2
		exit 1
	}
fi

GROUP_URL="$(BASE_URL="$BASE_URL" ./scripts/seed-demo.sh)"
echo "grupo da demo: $GROUP_URL" >&2

cd e2e
[ -d node_modules ] || npm install --no-fund --no-audit
npx playwright install chromium > /dev/null
rm -rf test-results
GROUP_URL="$GROUP_URL" npx playwright test demo.spec.ts

VIDEO="$(find test-results -name '*.webm' -print -quit)"
[ -n "$VIDEO" ] || { echo "erro: nenhum vídeo gravado" >&2; exit 1; }
cd ..
mkdir -p docs/demo
cp "e2e/$VIDEO" docs/demo/splitpix-demo.webm

if command -v ffmpeg > /dev/null 2>&1; then
	ffmpeg -y -loglevel error -i docs/demo/splitpix-demo.webm \
		-c:v libx264 -pix_fmt yuv420p -crf 26 docs/demo/splitpix-demo.mp4
fi

ls -la docs/demo/
