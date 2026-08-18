#!/usr/bin/env bash
#
# One-command end-to-end smoke test against docker-compose.
#
# Unlike scripts/verify-dashboard.sh — which checks a stack someone else started, page by
# page — this owns the whole stack and runs the pipeline end to end as a regression gate:
#
#   1. bring the stack up  (docker compose up --build -d)
#   2. wait for the backend to report healthy
#   3. import the three sample CSVs
#   4. run an inventory scan
#   5. assert open alerts exist
#   6. assert the forecast endpoint returns a confidence band
#   7. assert the what-if simulation returns a baseline and a simulated recommendation
#
# Exits non-zero on the first thing that is missing or the wrong shape, so it is usable as a
# CI check or a pre-release gate.
#
# Usage, from anywhere:
#
#   scripts/smoke-test.sh
#
# It seeds a fresh database each run so the admin password is known, which means it starts by
# DESTROYING the compose stack and its mysql-data volume. Do not point it at a stack holding
# data you care about. Override the defaults with:
#
#   ADMIN_PASSWORD=... BASE=... HEALTH_TIMEOUT=... SAMPLE_SKU=... KEEP_STACK=1 scripts/smoke-test.sh
#
# KEEP_STACK=1 leaves the stack running afterwards (for inspection); by default it is torn down.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SAMPLES="$ROOT/sample-data"

BASE="${BASE:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USERNAME:-admin}"
# Known, so login is deterministic against the fresh volume this script seeds. Long enough for
# any password-policy minimum the app enforces on seeded accounts.
ADMIN_PASSWORD="${ADMIN_PASSWORD:-smoke-test-admin-password}"
SAMPLE_SKU="${SAMPLE_SKU:-WIDGET-001}"
# Waited only after `up -d` returns, i.e. after the image build — so this covers container
# start (mysql + backend start_period ≈ 90s combined), not the build.
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"
KEEP_STACK="${KEEP_STACK:-}"

PASS=0
FAIL=0
TMP="$(mktemp -d)"
BODY="$TMP/body"

# ---------------------------------------------------------------------------- helpers

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$*"; }

# docker compose, always from the repo root where the compose file lives.
dc() { (cd "$ROOT" && docker compose "$@"); }

# call METHOD PATH [curl args...] -> prints status, writes body to $BODY
call() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path" "$@" 2>/dev/null
}

# auth_call TOKEN METHOD PATH [curl args...]
auth_call() {
  local token="$1" method="$2" path="$3"; shift 3
  call "$method" "$path" -H "Authorization: Bearer $token" "$@"
}

# expect_auth STATUS LABEL TOKEN METHOD PATH [curl args...]
expect_auth() {
  local want="$1" label="$2" token="$3"; shift 3
  local got; got="$(auth_call "$token" "$@")"
  if [ "$got" = "$want" ]; then ok "$label ($got)"
  else bad "$label — wanted $want, got $got: $(head -c 200 "$BODY")"; fi
}

# String field out of the last body. Falls back to grep so jq is optional — Git Bash on
# Windows rarely has it.
json_str() {
  if command -v jq >/dev/null 2>&1; then
    jq -r "$2 // empty" <"$1" 2>/dev/null
  else
    grep -o "\"${2##*.}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" <"$1" \
      | head -1 | sed 's/.*:[[:space:]]*"//; s/"$//'
  fi
}

# Numeric field, for the "is this figure actually non-zero" assertions.
json_num() {
  grep -o "\"$2\"[[:space:]]*:[[:space:]]*-\?[0-9][0-9.eE+-]*" <"$1" \
    | head -1 | sed 's/.*:[[:space:]]*//'
}

# body_has LABEL PATTERN — not just 200, but the field that makes the response non-empty.
body_has() {
  if grep -q "$2" "$BODY"; then ok "$1"
  else bad "$1 — pattern '$2' absent from: $(head -c 300 "$BODY")"; fi
}

login() {
  local user="$1" pass="$2"
  local out="$TMP/login-$user.json"
  local status
  status="$(curl -sS -o "$out" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" 2>/dev/null)"
  [ "$status" = "200" ] || { printf 'login failed for %s (%s): %s\n' \
    "$user" "$status" "$(head -c 200 "$out")" >&2; return 1; }
  json_str "$out" .token
}

import_csv() {
  local label="$1" path="$2" file="$3"
  local status; status="$(auth_call "$ADMIN_TOKEN" POST "$path" -F "file=@$file")"
  if [ "$status" != "200" ]; then
    bad "$label — $status: $(head -c 300 "$BODY")"; return
  fi
  local parsed rejected
  parsed="$(json_num "$BODY" rowsParsed)"
  rejected="$(json_num "$BODY" rejected)"
  if [ "${parsed:-0}" -gt 0 ] 2>/dev/null && [ "${rejected:-1}" = "0" ]; then
    ok "$label — parsed $parsed, 0 rejected"
  else
    bad "$label — parsed=$parsed rejected=$rejected: $(head -c 300 "$BODY")"
  fi
}

wait_for_health() {
  printf 'Waiting up to %ss for %s/api/health ...\n' "$HEALTH_TIMEOUT" "$BASE"
  local deadline=$(( SECONDS + HEALTH_TIMEOUT ))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if [ "$(call GET /api/health)" = "200" ]; then
      ok 'backend healthy'
      return 0
    fi
    sleep 3
  done
  bad "backend did not become healthy within ${HEALTH_TIMEOUT}s"
  return 1
}

diagnostics() {
  bold 'Diagnostics (before teardown)'
  dc ps 2>/dev/null || true
  printf '\n--- backend logs (tail) ---\n';    dc logs --tail=50 backend    2>/dev/null || true
  printf '\n--- ml-service logs (tail) ---\n';  dc logs --tail=20 ml-service 2>/dev/null || true
}

result() {
  if [ "$FAIL" -gt 0 ]; then
    diagnostics
    bold "Result: $PASS passed, $FAIL failed"
    exit 1
  fi
  bold "Result: $PASS passed, $FAIL failed"
  printf 'The full pipeline ran end to end against docker-compose.\n'
  exit 0
}

cleanup() {
  local code=$?
  rm -rf "$TMP"
  if [ -z "$KEEP_STACK" ]; then
    printf '\nTearing down the stack (set KEEP_STACK=1 to keep it) ...\n'
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  else
    printf '\nKEEP_STACK set — leaving the stack up. Tear down with: docker compose down -v\n'
  fi
  exit "$code"
}
trap cleanup EXIT

# ------------------------------------------------------------------- 0. bring the stack up

command -v docker >/dev/null 2>&1 || { printf 'docker is not on PATH.\n' >&2; exit 1; }
dc version >/dev/null 2>&1 || { printf 'The docker compose plugin is not available.\n' >&2; exit 1; }

bold '0 · Stack'
printf 'This recreates the compose stack and DESTROYS its mysql-data volume, so the admin\n'
printf 'password is known. Ctrl-C now if that stack holds data you need.\n'

# Clean slate so AdminSeeder seeds the account with our known password.
dc down -v --remove-orphans >/dev/null 2>&1 || true

export ADMIN_PASSWORD
if ! dc up --build -d; then
  bad 'docker compose up failed'
  result
fi

# ------------------------------------------------------------------------------ 1. checks

if wait_for_health; then
  ADMIN_TOKEN="$(login "$ADMIN_USER" "$ADMIN_PASSWORD" || true)"
  if [ -n "${ADMIN_TOKEN:-}" ]; then
    ok "logged in as $ADMIN_USER"

    bold '2 · Imports'
    import_csv 'products.csv'         /api/products/import  "$SAMPLES/products.csv"
    import_csv 'inventory_levels.csv' /api/inventory/import "$SAMPLES/inventory_levels.csv"
    import_csv 'demand_history.csv'   /api/demand/import    "$SAMPLES/demand_history.csv"

    bold '3 · Scan'
    scan_status="$(auth_call "$ADMIN_TOKEN" POST /api/inventory/scan)"
    if [ "$scan_status" = "200" ]; then
      ok "scan — $(tr -d '\n' <"$BODY" | head -c 160)"
    else
      bad "scan — $scan_status: $(head -c 300 "$BODY")"
    fi

    bold '4 · Alerts exist'
    expect_auth 200 'GET /api/alerts?resolved=false' "$ADMIN_TOKEN" GET '/api/alerts?resolved=false'
    body_has 'alerts: the list is present' '"alerts"'
    # A non-empty array is the assertion: on the fresh sample data, NEWITEM-005 (6 on hand
    # against an 80 reorder point) is a near-certain shortage. Zero here means the scan found
    # nothing above the risk threshold — check the demand import and SCAN_RISK_THRESHOLD.
    if grep -q '"alerts"[[:space:]]*:[[:space:]]*\[[[:space:]]*{' "$BODY"; then
      ok "alerts: at least one open (id $(json_num "$BODY" id))"
    else
      bad 'alerts: none open — the fresh sample data should breach the risk threshold'
    fi

    bold '5 · Forecast shape'
    expect_auth 200 "GET /api/products/$SAMPLE_SKU/forecast" "$ADMIN_TOKEN" GET \
      "/api/products/$SAMPLE_SKU/forecast"
    body_has 'forecast: confidence band lower' '"lower"'
    body_has 'forecast: confidence band upper' '"upper"'

    bold '6 · What-if simulation shape'
    window_start="$(date -u +%Y-%m-%d)"
    sim_body="$(printf '{"sku":"%s","demandMultiplier":1.3,"windowStartDate":"%s","windowDays":14}' \
      "$SAMPLE_SKU" "$window_start")"
    expect_auth 200 'POST /api/simulate' "$ADMIN_TOKEN" POST /api/simulate \
      -H 'Content-Type: application/json' -d "$sim_body"
    body_has 'simulate: baseline recommendation'  '"baseline"'
    body_has 'simulate: simulated recommendation' '"simulated"'
  else
    bad "admin login failed — cannot run the pipeline checks"
  fi
fi

result
