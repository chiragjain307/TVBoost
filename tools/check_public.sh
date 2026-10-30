#!/usr/bin/env bash
# Stop private data reaching the public repository. It runs on every push through .githooks/pre-push
# and in CI, and checks the files git tracks. Add your own private words, one per line, to
# ~/.tvboost/private-words.txt: your name, business, Wi-Fi network or anything else.
#
# Usage: tools/check_public.sh
#
# Exit status: 0 clean, 1 something must be fixed before pushing.
set -uo pipefail
cd "$(dirname "$0")/.."
SELF=tools/check_public.sh
problems=0

report() {
  echo "BLOCKED: $1"
  printf '%s\n' "$2" | sed 's/^/  /'
  problems=1
}

# Text checks. The example address 192.168.1.50 is allowed: the tools use it in their usage lines.
scan() {
  local label="$1" pattern="$2" found
  found=$(git grep -IlE "$pattern" -- . ":(exclude)$SELF" ":(exclude)LICENSE" 2>/dev/null || true)
  [ -z "$found" ] || report "$label" "$found"
}
scan 'a GitHub or API token' '(ghp_|gho_|github_pat_|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30})'
found=$(git grep -IlE 'BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY' -- . ":(exclude)$SELF" ":(exclude)tests/" 2>/dev/null || true)
[ -z "$found" ] || report 'a private key' "$found"
scan 'an email address' '[A-Za-z0-9._%+-]+@(gmail|yahoo|outlook|hotmail|icloud)\.com'

lan=$(git grep -IhoE '\b(192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+)\b' -- . ":(exclude)$SELF" 2>/dev/null \
  | grep -vxE '192\.168\.1\.[0-9]+|10\.0\.0\.[0-9]+' | sort -u || true)
[ -z "$lan" ] || report 'a private network address' "$lan"

mac=$(git grep -IlE '\b([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b' -- . ":(exclude)$SELF" 2>/dev/null || true)
[ -z "$mac" ] || report 'a MAC address' "$mac"

words="$HOME/.tvboost/private-words.txt"
if [ -f "$words" ]; then
  while IFS= read -r word; do
    [ -n "$word" ] || continue
    found=$(git grep -Iil -F -- "$word" -- . ":(exclude)LICENSE" 2>/dev/null || true)
    [ -z "$found" ] || report "a private word from private-words.txt" "$found"
  done < "$words"
fi

# Picture checks. A logo can hide in any image, so images are only allowed in these folders.
images=$(git ls-files | grep -iE '\.(png|jpe?g|webp|gif|bmp|svg)$' \
  | grep -vE '^(launcher/app/src/main/res/|assets/|devices/[^/]+/(box\.jpg|screenshots/)|designs/[^/]+/preview-)' || true)
[ -z "$images" ] || report 'a picture outside the allowed folders' "$images"
logos=$(git ls-files | grep -iE 'logo' | grep -iE '\.(png|jpe?g|webp|svg)$' | grep -vx 'assets/branding/sample-logo.png' \
  | grep -v '^launcher/app/src/main/res/' || true)
[ -z "$logos" ] || report 'a logo file' "$logos"

# Binaries and captures that must never ship.
binaries=$(git ls-files | grep -iE '\.(apk|aab|bin|so|keystore|jks|zip|mp4)$|manifest\.json$|recovery-.*\.json$' || true)
[ -z "$binaries" ] || report 'a binary or a backup' "$binaries"

if [ "$problems" -eq 0 ]; then
  echo "public check passed: $(git ls-files | wc -l | tr -d ' ') tracked files"
fi
exit "$problems"
