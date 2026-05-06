#!/usr/bin/env bash
#
# scripts/check.sh — run the four PR-readiness gates locally in the
# same order as .github/workflows/ci.yml. Exits non-zero on the first
# failure. Run from anywhere; the script cd's to the repo root.
#
#   1. clj-kondo                       — 0 errors, 0 warnings
#   2. cljfmt check                    — clean
#   3. shadow-cljs compile test        — 0 failures, 0 errors
#   4. shadow-cljs release app         — 0 warnings
#
# CLAUDE.md "PR readiness gate" mandates all four before opening a
# pull request. CI re-runs them; this script is the local pre-flight.

set -euo pipefail

cd "$(dirname "$0")/.."

# Optional colour — degrade gracefully when stdout isn't a tty.
if [[ -t 1 ]] && command -v tput >/dev/null 2>&1; then
    bold=$(tput bold); green=$(tput setaf 2); red=$(tput setaf 1); reset=$(tput sgr0)
else
    bold=""; green=""; red=""; reset=""
fi

step() { printf "\n%s→ %s%s\n" "$bold" "$1" "$reset"; }
ok()   { printf "%s  ✓ %s%s\n" "$green" "$1" "$reset"; }
die()  { printf "\n%s%s%s\n" "$red" "$1" "$reset" >&2; exit 1; }

start=$SECONDS

step "1/4  clj-kondo --lint src test scripts"
clj-kondo --lint src test scripts
ok "clj-kondo: 0 errors, 0 warnings"

step "2/4  cljfmt check"
cljfmt check
ok "cljfmt: clean"

step "3/4  npx shadow-cljs compile test"
npx shadow-cljs compile test

step "4/4  npx shadow-cljs release app"
log=$(mktemp); trap 'rm -f "$log"' EXIT
npx shadow-cljs release app 2>&1 | tee "$log"
# CLAUDE.md mandates 0 warnings; CI tees + greps. Replicate that here.
if grep -E ', [1-9][0-9]* warnings?,' "$log" >/dev/null; then
    die "Release build emitted warnings — fix before opening a PR."
fi

elapsed=$((SECONDS - start))
printf "\n%s%sAll four PR-readiness gates green in %ds.%s\n" "$bold" "$green" "$elapsed" "$reset"
