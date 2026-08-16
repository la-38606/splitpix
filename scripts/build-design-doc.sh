#!/usr/bin/env bash
#
# Regenerates docs/splitpix-design-doc.pdf from docs/design.md.
# Needs python3 and a Chrome/Chromium binary (set CHROME to override).
# Not part of CI on purpose: the PDF changes only when design.md does.

set -euo pipefail
cd "$(dirname "$0")/.."

CHROME="${CHROME:-}"
if [ -z "$CHROME" ]; then
	for candidate in \
		"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
		/usr/bin/google-chrome /usr/bin/chromium /usr/bin/chromium-browser; do
		[ -x "$candidate" ] && CHROME="$candidate" && break
	done
fi
[ -n "$CHROME" ] || { echo "erro: Chrome não encontrado; defina CHROME=" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 scripts/md2html.py docs/design.md > "$TMP/design-doc.html"
"$CHROME" --headless=new --disable-gpu --no-pdf-header-footer \
	--print-to-pdf="docs/splitpix-design-doc.pdf" "$TMP/design-doc.html" 2>/dev/null

ls -la docs/splitpix-design-doc.pdf
