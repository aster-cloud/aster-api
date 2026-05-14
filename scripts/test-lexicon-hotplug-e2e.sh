#!/usr/bin/env bash
# 端到端 hot-plug 后端冒烟测试（更新版本：C2 HMAC 签 body+nonce）。

set -euo pipefail

API="${API_BASE:-http://localhost:8080}"
HMAC_KEY="${HMAC_KEY:-}"
PASS=0
FAIL=0

if [ -z "$HMAC_KEY" ]; then
  echo "FATAL: export HMAC_KEY=<aster.plan-gate.hmac-key>" >&2
  exit 2
fi

# R3-C2b canonical: method\npath\nts\nnonce\ncontent-type\ncontent-length\nbody-sha\nfilename
sign() {
  local method="$1" path="$2" ts="$3" nonce="$4" ct="$5" clen="$6" bsha="$7" fn="$8"
  printf "%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s" \
    "$method" "$path" "$ts" "$nonce" "$ct" "$clen" "$bsha" "$fn" \
    | openssl dgst -sha256 -hmac "$HMAC_KEY" -hex | awk '{print $2}'
}

admin_no_body() {
  local method="$1" path="$2"
  local ts; ts="$(date +%s)"
  local nonce; nonce="$(openssl rand -hex 16)"
  local sig; sig="$(sign "$method" "$path" "$ts" "$nonce" "" 0 "" "")"
  curl -sS -X "$method" \
    -H "X-Aster-Timestamp: $ts" \
    -H "X-Aster-Nonce: $nonce" \
    -H "X-Internal-Signature: $sig" \
    "$API$path"
}

assert_contains() {
  local label="$1" haystack="$2" needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    echo "  ✓ $label"; PASS=$((PASS+1))
  else
    echo "  ✗ $label — expected '$needle' in: $haystack" >&2; FAIL=$((FAIL+1))
  fi
}

assert_not_contains() {
  local label="$1" haystack="$2" needle="$3"
  if ! echo "$haystack" | grep -q "$needle"; then
    echo "  ✓ $label"; PASS=$((PASS+1))
  else
    echo "  ✗ $label — unexpected '$needle': $haystack" >&2; FAIL=$((FAIL+1))
  fi
}

# R7: reset to clean state in case API has stale enable/disable from prior run
admin_no_body POST /api/v1/admin/lexicons/zh-CN/disable >/dev/null 2>&1 || true
admin_no_body POST /api/v1/admin/lexicons/de-DE/disable >/dev/null 2>&1 || true

echo "==== T0: en-only baseline ===="
LIST0="$(curl -sS "$API/api/v1/lexicons")"
echo "  list = $LIST0"
assert_contains "en-US visible" "$LIST0" '"en-US"'
assert_not_contains "zh-CN hidden" "$LIST0" '"zh-CN"'
assert_not_contains "de-DE hidden" "$LIST0" '"de-DE"'

echo ""
echo "==== T1: enable zh-CN via admin ===="
RESP="$(admin_no_body POST /api/v1/admin/lexicons/zh-CN/enable)"
echo "  response = $RESP"
assert_contains "enable returned status" "$RESP" '"status"'

LIST1="$(curl -sS "$API/api/v1/lexicons")"
echo "  list = $LIST1"
assert_contains "en-US still visible" "$LIST1" '"en-US"'
assert_contains "zh-CN now visible" "$LIST1" '"zh-CN"'

echo ""
echo "==== T2: nonce replay should be rejected ===="
# 取一个固定 nonce + ts 调两次
TS="$(date +%s)"
NONCE="$(openssl rand -hex 16)"
SIG="$(sign POST /api/v1/admin/lexicons/de-DE/enable "$TS" "$NONCE" "" 0 "" "")"
R1="$(curl -sS -X POST -H "X-Aster-Timestamp: $TS" -H "X-Aster-Nonce: $NONCE" -H "X-Internal-Signature: $SIG" "$API/api/v1/admin/lexicons/de-DE/enable")"
R2="$(curl -sS -X POST -H "X-Aster-Timestamp: $TS" -H "X-Aster-Nonce: $NONCE" -H "X-Internal-Signature: $SIG" "$API/api/v1/admin/lexicons/de-DE/enable")"
echo "  R1 = $R1"; echo "  R2 = $R2"
assert_contains "first call accepted" "$R1" '"status"'
assert_contains "replayed nonce rejected" "$R2" "replayed_nonce"

echo ""
echo "==== T3: disable zh-CN ===="
RESP="$(admin_no_body POST /api/v1/admin/lexicons/zh-CN/disable)"
echo "  response = $RESP"

LIST3="$(curl -sS "$API/api/v1/lexicons")"
echo "  list after disable = $LIST3"
assert_contains "en-US still visible" "$LIST3" '"en-US"'
assert_not_contains "zh-CN hidden again" "$LIST3" '"zh-CN"'

echo ""
echo "==== T4: invalid signature rejected ===="
TS="$(date +%s)"
NONCE="$(openssl rand -hex 16)"
R="$(curl -sS -X POST -H "X-Aster-Timestamp: $TS" -H "X-Aster-Nonce: $NONCE" -H "X-Internal-Signature: deadbeef" "$API/api/v1/admin/lexicons/zh-CN/enable")"
assert_contains "invalid signature rejected" "$R" "invalid_signature"

echo ""
echo "==== T5: missing signature headers rejected ===="
R="$(curl -sS -X POST "$API/api/v1/admin/lexicons/zh-CN/enable")"
assert_contains "missing headers rejected" "$R" "missing_signature_headers"

echo ""
echo "==== T6: invalid locale id rejected (path traversal / control char) ===="
R="$(admin_no_body POST /api/v1/admin/lexicons/..%2Fzh-CN/enable)"
echo "  response: $R"
assert_contains "invalid locale id rejected" "$R" "invalid_locale_id"

echo ""
echo "==== T7: nonce NOT recoverable after bad signature ===="
# R4-M-3: 一旦 nonce 被观察到（哪怕签错）就消费掉
TS="$(date +%s)"
NONCE="$(openssl rand -hex 16)"
# 先用错签名占用 nonce
R1="$(curl -sS -X POST -H "X-Aster-Timestamp: $TS" -H "X-Aster-Nonce: $NONCE" -H "X-Internal-Signature: deadbeef" "$API/api/v1/admin/lexicons/zh-CN/enable")"
# 再用正确签名 —— 应仍被拒（nonce 已消费）
GOOD_SIG="$(sign POST /api/v1/admin/lexicons/zh-CN/enable "$TS" "$NONCE" "" 0 "" "")"
R2="$(curl -sS -X POST -H "X-Aster-Timestamp: $TS" -H "X-Aster-Nonce: $NONCE" -H "X-Internal-Signature: $GOOD_SIG" "$API/api/v1/admin/lexicons/zh-CN/enable")"
echo "  R1 (bad sig): $R1"
echo "  R2 (good sig same nonce): $R2"
assert_contains "bad sig rejected" "$R1" "invalid_signature"
assert_contains "nonce after bad-sig stays consumed" "$R2" "replayed_nonce"

echo ""
echo "================ SUMMARY ================"
echo "passed: $PASS"
echo "failed: $FAIL"
if [ "$FAIL" -gt 0 ]; then exit 1; fi
