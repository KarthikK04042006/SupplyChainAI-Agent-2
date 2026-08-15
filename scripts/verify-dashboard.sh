#!/usr/bin/env bash
#
# End-to-end verification for Part 5 (dashboard UI) against a running stack.
#
# Runs the five checks from the plan in order: load the sample CSVs, scan, read back every
# API each page calls and assert the data is real rather than merely well-formed, re-check
# role gating over HTTP, and confirm a malformed CSV reports its rows.
#
# What this does NOT do: look at a rendered page. It confirms each page is served and that
# the data behind it is present and correctly shaped. Layout, chart appearance and
# responsive behaviour need a browser.
#
# Usage, from anywhere:
#
#   ADMIN_PASSWORD=some-long-password docker compose up --build -d
#   scripts/verify-dashboard.sh
#
# Without ADMIN_PASSWORD the admin password is generated on first boot and logged once; the
# script scrapes it from `docker compose logs backend` in that case. On a re-run against an
# existing mysql-data volume the account already exists and nothing is logged, so pin
# ADMIN_PASSWORD before the first `up` if you want repeatable runs.
#
# Exits non-zero if any check fails.

set -uo pipefail

BASE="${BASE:-http://localhost:8080}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SAMPLES="$ROOT/sample-data"

# Long enough for CreateUserRequest's @Size(min = 12).
VIEWER_PASSWORD="${VIEWER_PASSWORD:-viewer-verify-pass}"
MANAGER_PASSWORD="${MANAGER_PASSWORD:-manager-verify-pass}"

PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ---------------------------------------------------------------------------- helpers

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }

ok()   { PASS=$((PASS + 1)); printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m  %s\n' "$*"; }

# Last response body, written by every call() so a failure can show what came back.
BODY="$TMP/body"

# call METHOD PATH [curl args...] -> prints status, body in $BODY
call() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path" "$@" 2>/dev/null
}

# auth_call TOKEN METHOD PATH [curl args...]
auth_call() {
  local token="$1" method="$2" path="$3"; shift 3
  call "$method" "$path" -H "Authorization: Bearer $token" "$@"
}

# expect STATUS LABEL METHOD PATH [curl args...]
expect() {
  local want="$1" label="$2"; shift 2
  local got; got="$(call "$@")"
  if [ "$got" = "$want" ]; then ok "$label ($got)"
  else bad "$label — wanted $want, got $got: $(head -c 200 "$BODY")"; fi
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

# body_has LABEL PATTERN — the body-shape assertion the plan asks for: not just 200, but
# the field that makes the page non-empty.
body_has() {
  if grep -q "$2" "$BODY"; then ok "$1"
  else bad "$1 — pattern '$2' absent from: $(head -c 300 "$BODY")"; fi
}

login() {
  local user="$1" pass="$2"
  # Separate statement: `local` is a builtin, so every argument on one line is expanded
  # before the assignments take effect, and $user would still be unset under `set -u`.
  local out="$TMP/login-$user.json"
  local status
  status="$(curl -sS -o "$out" -w '%{http_code}' -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$pass\"}" 2>/dev/null)"
  [ "$status" = "200" ] || { printf 'login failed for %s (%s): %s\n' \
    "$user" "$status" "$(head -c 200 "$out")" >&2; return 1; }
  json_str "$out" .token
}

# ---------------------------------------------------------------- 0. stack is up, admin

bold '0 · Stack'

if [ "$(call GET /api/health)" = "200" ]; then
  ok 'backend health'
else
  printf '\033[31mBackend is not answering at %s. Start it with `docker compose up --build -d`.\033[0m\n' "$BASE"
  exit 1
fi

ADMIN_USER="${ADMIN_USERNAME:-admin}"
if [ -z "${ADMIN_PASSWORD:-}" ]; then
  # Generated on first boot and printed once by AdminSeeder, in a block that also contains
  # "username:" and a sentence mentioning the word password — hence matching "password:"
  # followed by a single unbroken token, which a Base64 secret always is.
  ADMIN_PASSWORD="$(cd "$ROOT" && docker compose logs backend 2>/dev/null \
    | grep -o 'password: [^[:space:]]\+' | tail -1 | sed 's/password: //' | tr -d '\r')"
  if [ -n "$ADMIN_PASSWORD" ]; then
    printf '  (admin password scraped from the first-boot log)\n'
  fi
fi

if [ -z "${ADMIN_PASSWORD:-}" ]; then
  cat >&2 <<'EOF'
No admin password. It is generated on first boot and logged once, and this database has
already been seeded, so it is not recoverable. Either:
  ADMIN_PASSWORD=some-long-password docker compose up -d --force-recreate backend
on a fresh volume (docker compose down -v), or pass ADMIN_PASSWORD= for an account you know.
EOF
  exit 1
fi

ADMIN_TOKEN="$(login "$ADMIN_USER" "$ADMIN_PASSWORD")" || exit 1
ok "logged in as $ADMIN_USER"

# VIEWER and MANAGER accounts for step 4. Created if absent; a duplicate is fine.
for pair in "verify-viewer:VIEWER:$VIEWER_PASSWORD" "verify-manager:MANAGER:$MANAGER_PASSWORD"; do
  IFS=: read -r name role pass <<<"$pair"
  status="$(auth_call "$ADMIN_TOKEN" POST /api/users \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$name\",\"password\":\"$pass\",\"role\":\"$role\"}")"
  case "$status" in
    201) ok "created $name ($role)" ;;
    409|400) ok "$name already exists" ;;
    *) bad "creating $name — $status: $(head -c 200 "$BODY")" ;;
  esac
done

VIEWER_TOKEN="$(login verify-viewer "$VIEWER_PASSWORD")" || exit 1
MANAGER_TOKEN="$(login verify-manager "$MANAGER_PASSWORD")" || exit 1

# --------------------------------------------------------------- 1. the static surface

bold '1 · Static surface (unauthenticated — the shells carry no data)'

expect 200 'GET /' GET /

for page in index.html login.html overview.html product.html inventory.html upload.html \
            css/app.css js/api.js js/auth.js js/overview.js js/product.js js/inventory.js \
            js/upload.js; do
  expect 200 "GET /$page" GET "/$page"
done

# Deliberately last and called out: these are vendored rather than pulled from a CDN (see
# static/vendor/README.md). Every page links them, so a 404 here means the pages render
# unstyled and product.html loses its chart — the JS survives it, the look does not.
for asset in vendor/bootstrap.min.css vendor/bootstrap.bundle.min.js vendor/chart.umd.min.js; do
  if [ "$(call GET "/$asset")" = "200" ]; then
    ok "GET /$asset"
  else
    bad "GET /$asset — not vendored; see backend/src/main/resources/static/vendor/README.md"
  fi
done

# ------------------------------------------------------------------------- 2. the CSVs

bold '2 · Imports (ADMIN)'

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
    ok "$label — $(tr -d '\n' <"$BODY" | head -c 160)"
  else
    bad "$label — parsed=$parsed rejected=$rejected: $(head -c 400 "$BODY")"
  fi
}

import_csv 'products.csv'         /api/products/import  "$SAMPLES/products.csv"
import_csv 'inventory_levels.csv' /api/inventory/import "$SAMPLES/inventory_levels.csv"
import_csv 'demand_history.csv'   /api/demand/import    "$SAMPLES/demand_history.csv"

# ------------------------------------------------------------------------- 3. the scan

bold '3 · Inventory scan (MANAGER) — opens alerts and records accuracy'

status="$(auth_call "$MANAGER_TOKEN" POST /api/inventory/scan)"
if [ "$status" = "200" ]; then
  ok "scan — $(tr -d '\n' <"$BODY" | head -c 200)"
else
  bad "scan — $status: $(head -c 400 "$BODY")"
fi

# ------------------------------------------------------------- 4. what each page reads

bold '4 · Page data (VIEWER) — present and non-empty, not merely 200'

expect_auth 200 'GET /api/dashboard/summary' "$VIEWER_TOKEN" GET /api/dashboard/summary
body_has  'summary: totalSkus present'        '"totalSkus"'
body_has  'summary: alert severities present' '"bySeverity"'
body_has  'summary: inventory value present'  '"skusPriced"'
body_has  'summary: unpriced coverage stated' '"skusUnpriced"'
body_has  'summary: accuracy block present'   '"forecastAccuracy"'
body_has  'summary: accuracy sample size'     '"skusScored"'
TOTAL_SKUS="$(json_num "$BODY" totalSkus)"
if [ "${TOTAL_SKUS:-0}" -gt 0 ] 2>/dev/null; then ok "summary: totalSkus = $TOTAL_SKUS"
else bad "summary: totalSkus is ${TOTAL_SKUS:-absent} — the catalogue did not load"; fi

expect_auth 200 'GET /api/products' "$VIEWER_TOKEN" GET '/api/products?size=5'
body_has 'products: the page carries products' '"products"'
body_has 'products: categories for the filter' '"categories"'

# First SKU in the sample catalogue, for the product-page calls.
SKU="$(sed -n '2p' "$SAMPLES/products.csv" | cut -d, -f1 | tr -d '\r"')"
printf '  (product page checks use SKU %s)\n' "$SKU"

expect_auth 200 "GET /api/products/$SKU" "$VIEWER_TOKEN" GET "/api/products/$SKU"
expect_auth 200 'GET .../demand-history'  "$VIEWER_TOKEN" GET "/api/products/$SKU/demand-history"
body_has 'history: dated points present' '"points"[[:space:]]*:[[:space:]]*\[[[:space:]]*{'
OBSERVATIONS="$(json_num "$BODY" observations)"
if [ "${OBSERVATIONS:-0}" -gt 1 ] 2>/dev/null; then ok "history: $OBSERVATIONS observations"
else bad "history: ${OBSERVATIONS:-no} observations — the forecast below cannot be meaningful"; fi

expect_auth 200 'GET .../forecast' "$VIEWER_TOKEN" GET "/api/products/$SKU/forecast"
body_has 'forecast: confidence band lower' '"lower"'
body_has 'forecast: confidence band upper' '"upper"'

expect_auth 200 'GET .../reorder-recommendation' "$VIEWER_TOKEN" GET \
  "/api/products/$SKU/reorder-recommendation"
body_has 'reorder: decision present'     '"decision"'
body_has 'reorder: the "why" steps'      '"intermediates"'
body_has 'reorder: strategy stated'      '"ordering_strategy"'

# SHAP is genuinely optional: a model without support answers 200 with a note, and the ML
# service may reject outright (422). All three are acceptable — silence is not.
status="$(auth_call "$VIEWER_TOKEN" GET "/api/products/$SKU/explain")"
case "$status" in
  200) if grep -q '"contributions"[[:space:]]*:[[:space:]]*\[[[:space:]]*{' "$BODY"; then
         ok 'explain: SHAP contributions present'
       elif grep -q '"note"' "$BODY"; then
         ok "explain: no SHAP, with a reason — $(json_str "$BODY" .note)"
       else
         bad "explain: 200 with neither contributions nor note: $(head -c 300 "$BODY")"
       fi ;;
  422) ok "explain: ML declined with a reason — $(head -c 200 "$BODY")" ;;
  *)   bad "explain — $status: $(head -c 300 "$BODY")" ;;
esac

expect_auth 200 'GET /api/inventory/levels' "$VIEWER_TOKEN" GET /api/inventory/levels
body_has 'grid: warehouse columns' '"warehouses"'
body_has 'grid: product rows'      '"rows"'
body_has 'grid: cells classified'  '"status"'

expect_auth 200 'GET /api/alerts?resolved=false' "$VIEWER_TOKEN" GET '/api/alerts?resolved=false'
body_has 'alerts: the list is present' '"alerts"'
# First id inside the alerts array. AlertPage puts the list first, so the first "id" in the
# body belongs to an alert — but only if the array is non-empty, hence the shape check.
if grep -q '"alerts"[[:space:]]*:[[:space:]]*\[[[:space:]]*{' "$BODY"; then
  ALERT_ID="$(json_num "$BODY" id)"
  ok "alerts: at least one open (id ${ALERT_ID:-?})"
else
  ALERT_ID=''
  printf '  \033[33mNOTE\033[0m  no open alerts — the resolve checks below are skipped.\n'
  printf '        Not a failure: it means the scan found nothing above the risk threshold.\n'
fi

# -------------------------------------------------------------------- 5. role gating

bold '5 · Role gating over HTTP (not just in tests)'

expect 401 'unauthenticated GET /api/products' GET /api/products
expect 401 'unauthenticated GET /api/dashboard/summary' GET /api/dashboard/summary

expect_auth 403 'VIEWER cannot import products'  "$VIEWER_TOKEN" POST /api/products/import \
  -F "file=@$SAMPLES/products.csv"
expect_auth 403 'VIEWER cannot import inventory' "$VIEWER_TOKEN" POST /api/inventory/import \
  -F "file=@$SAMPLES/inventory_levels.csv"
expect_auth 403 'VIEWER cannot import demand'     "$VIEWER_TOKEN" POST /api/demand/import \
  -F "file=@$SAMPLES/demand_history.csv"
expect_auth 403 'VIEWER cannot scan'             "$VIEWER_TOKEN" POST /api/inventory/scan
expect_auth 403 'VIEWER cannot list users'       "$VIEWER_TOKEN" GET /api/users
expect_auth 403 'MANAGER cannot import products' "$MANAGER_TOKEN" POST /api/products/import \
  -F "file=@$SAMPLES/products.csv"
expect_auth 403 'MANAGER cannot list users'      "$MANAGER_TOKEN" GET /api/users

# Before the MANAGER scan below, deliberately: auto-resolve is on, so a fresh scan can close
# this very alert and turn the resolve into a 409.
if [ -n "${ALERT_ID:-}" ]; then
  expect_auth 403 'VIEWER cannot resolve an alert' "$VIEWER_TOKEN" PATCH \
    "/api/alerts/$ALERT_ID/resolve" -H 'Content-Type: application/json' -d '{"note":"denied"}'
  expect_auth 200 'MANAGER can resolve an alert'   "$MANAGER_TOKEN" PATCH \
    "/api/alerts/$ALERT_ID/resolve" -H 'Content-Type: application/json' \
    -d '{"note":"resolved by verify-dashboard.sh"}'
  # Resolving twice is the 409 the overview page handles by refreshing rather than showing a
  # dead error, so it is worth confirming the status is really what the page keys off.
  expect_auth 409 'resolving twice is a 409'       "$MANAGER_TOKEN" PATCH \
    "/api/alerts/$ALERT_ID/resolve" -H 'Content-Type: application/json' -d '{}'
fi

expect_auth 200 'MANAGER can scan'               "$MANAGER_TOKEN" POST /api/inventory/scan

# --------------------------------------------------------------- 6. the rejection path

bold '6 · Malformed CSV — page 5 must show the rows, not just fail'

# Header is valid; three of the four rows are not. This is the case the upload page's error
# list exists for, and it answers 200 — ImportResult's javadoc is explicit that rejecting the
# whole file over one bad line would cost the caller the rows that were fine.
#
# VERIFY-GOOD-1 is the one row that loads, and it does land in the catalogue. That is the
# point of the check — a partial success has to actually be partial — but it means this
# script leaves one junk product behind. Delete it, or re-run against a fresh volume.
cat >"$TMP/bad-rows.csv" <<'EOF'
sku,name,category,lead_time_days,unit_cost
,No SKU at all,Components,7,10.00
VERIFY-GOOD-1,Left behind by verify-dashboard.sh,Components,7,10.00
BAD-2,Negative lead time,Components,-3,10.00
BAD-3,Lead time is not a number,Components,seven,10.00
EOF

status="$(auth_call "$ADMIN_TOKEN" POST /api/products/import -F "file=@$TMP/bad-rows.csv")"
if [ "$status" = "200" ]; then
  REJECTED="$(json_num "$BODY" rejected)"
  PARSED="$(json_num "$BODY" rowsParsed)"
  if [ "${REJECTED:-0}" = "3" ] && [ "${PARSED:-0}" = "1" ]; then
    ok 'three bad rows rejected, the good one kept'
  elif [ "${REJECTED:-0}" -gt 0 ] 2>/dev/null; then
    bad "rows rejected but not as expected: rejected=$REJECTED parsed=$PARSED (wanted 3 and 1)"
  else
    bad "bad rows were not rejected: $(head -c 400 "$BODY")"
  fi
  # The per-row messages the page renders. Each must name its line and its column.
  if grep -q '"Row ' "$BODY"; then
    ok 'each rejection names its row'
    grep -o '"Row [^"]*"' "$BODY" | sed 's/^/      /'
  else
    bad "no per-row messages: $(head -c 400 "$BODY")"
  fi
else
  bad "malformed-row import — wanted 200 with an error list, got $status: $(head -c 300 "$BODY")"
fi

# The other branch upload.js renders: an outright 400 with a ProblemDetail. An empty file is
# what triggers it — a file with a *wrong* header still parses, and comes back as rejected
# rows above, so it would not exercise this path.
: >"$TMP/empty.csv"
expect_auth 400 'an empty file is a 400' "$ADMIN_TOKEN" POST /api/products/import \
  -F "file=@$TMP/empty.csv"
body_has 'the 400 carries a ProblemDetail title' '"title"'
body_has 'and names the problem'                 'Malformed CSV upload'

# ------------------------------------------------------------------------------ result

bold "Result: $PASS passed, $FAIL failed"
if [ "$FAIL" -gt 0 ]; then
  printf 'Mechanical checks only — no page was rendered. Layout, chart appearance and\n'
  printf 'responsive behaviour still need a browser.\n'
  exit 1
fi
printf 'Every page is served and every API behind it returned real, correctly shaped data.\n'
printf 'Not checked here: how any of it looks. That needs a browser.\n'
