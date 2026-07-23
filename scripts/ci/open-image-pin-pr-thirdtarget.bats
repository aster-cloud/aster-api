#!/usr/bin/env bash
# 验 open-image-pin-pr.sh 第三写目标：
#   (1) 不传 ENV_PATCH_* → 现有 image-lock/kustomization 双写不变（零改动回归）。
#   (2) 传 ENV_PATCH_* → 额外 patch 指定 Deployment env 的 value（第三写目标）。
# ★离线测：把脚本的「写 3 个目标」逻辑抽成 patch_targets() 函数，本测直接调它，
#   不触发 git clone/push（那些走 CI 集成，非单测）。
#
# ★本机无 bats（`command -v bats` 为空），改用纯 bash 断言脚本（brief 允许的降级路径）。
# 用法：bash scripts/ci/open-image-pin-pr-thirdtarget.bats
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/open-image-pin-pr.sh"

FAILED=0
assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  ✓ ${desc}"
  else
    echo "  ✗ ${desc}: expected='${expected}' actual='${actual}'"
    FAILED=1
  fi
}

setup_fixture() {
  local tmp="$1"
  # image-lock（runner 条）+ kustomization（runner name）+ launcher deployment（RUNNER_IMAGE_DIGEST env）。
  cat > "$tmp/image-lock.yaml" <<'EOF'
version: 1
images:
  - image: docker.io/wontlost/aster-replay-runner
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000
    sourceSha: UNVERIFIED-SEED
    runId: "0"
EOF
  cat > "$tmp/kustomization.yaml" <<'EOF'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
images:
  - name: docker.io/wontlost/aster-replay-runner
    digest: sha256:0000000000000000000000000000000000000000000000000000000000000000
EOF
  cat > "$tmp/deployment.yaml" <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: runner-launcher
spec:
  template:
    spec:
      containers:
        - name: runner-launcher
          env:
            - name: RUNNER_IMAGE_DIGEST
              value: sha256:0000000000000000000000000000000000000000000000000000000000000000
EOF
}

echo "=== Test 1: 不传 ENV_PATCH_* → image-lock/kustomization 正常写，deployment 不动（零改动回归） ==="
TMP1="$(mktemp -d)"
trap 'rm -rf "$TMP1" "${TMP2:-}"' EXIT
setup_fixture "$TMP1"

# 快照 deployment.yaml 内容（byte-identical 对比用）。
DEPLOY_BEFORE_SHA="$(shasum -a 256 "$TMP1/deployment.yaml" | awk '{print $1}')"

DIGEST_1="sha256:$(printf 'a%.0s' $(seq 1 64))"
(
  source "$TARGET_SCRIPT" --source-only
  LOCK_PATH="$TMP1/image-lock.yaml" KUSTOMIZATION_PATH="$TMP1/kustomization.yaml" \
    IMAGE="docker.io/wontlost/aster-replay-runner" \
    patch_targets "$DIGEST_1" "seed-sha" "123"
)

actual_lock_digest="$(yq '.images[0].digest' "$TMP1/image-lock.yaml")"
assert_eq "image-lock digest 已改" "$DIGEST_1" "$actual_lock_digest"

actual_kustomization_digest="$(yq '.images[0].digest' "$TMP1/kustomization.yaml")"
assert_eq "kustomization digest 已改" "$DIGEST_1" "$actual_kustomization_digest"

actual_lock_sha="$(yq '.images[0].sourceSha' "$TMP1/image-lock.yaml")"
assert_eq "image-lock sourceSha 已改" "seed-sha" "$actual_lock_sha"

actual_lock_run="$(yq '.images[0].runId' "$TMP1/image-lock.yaml")"
assert_eq "image-lock runId 已改" "123" "$actual_lock_run"

DEPLOY_AFTER_SHA="$(shasum -a 256 "$TMP1/deployment.yaml" | awk '{print $1}')"
assert_eq "deployment.yaml 字节级零改动（未被触碰）" "$DEPLOY_BEFORE_SHA" "$DEPLOY_AFTER_SHA"

actual_deploy_env="$(yq '.spec.template.spec.containers[0].env[0].value' "$TMP1/deployment.yaml")"
assert_eq "deployment env value 未变" "sha256:0000000000000000000000000000000000000000000000000000000000000000" "$actual_deploy_env"

echo ""
echo "=== Test 2: 传 ENV_PATCH_* → 额外 patch deployment RUNNER_IMAGE_DIGEST env（第三写目标） ==="
TMP2="$(mktemp -d)"
setup_fixture "$TMP2"

DIGEST_2="sha256:$(printf 'b%.0s' $(seq 1 64))"
(
  source "$TARGET_SCRIPT" --source-only
  LOCK_PATH="$TMP2/image-lock.yaml" KUSTOMIZATION_PATH="$TMP2/kustomization.yaml" \
    IMAGE="docker.io/wontlost/aster-replay-runner" \
    ENV_PATCH_PATH="$TMP2/deployment.yaml" \
    ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "RUNNER_IMAGE_DIGEST")' \
    patch_targets "$DIGEST_2" "seed-sha" "456"
)

actual_deploy_env_2="$(yq '.spec.template.spec.containers[0].env[0].value' "$TMP2/deployment.yaml")"
assert_eq "deployment env value 已 patch 为新 digest" "$DIGEST_2" "$actual_deploy_env_2"

actual_lock_digest_2="$(yq '.images[0].digest' "$TMP2/image-lock.yaml")"
assert_eq "image-lock digest 同时正常写（第三目标不影响前两个）" "$DIGEST_2" "$actual_lock_digest_2"

actual_kustomization_digest_2="$(yq '.images[0].digest' "$TMP2/kustomization.yaml")"
assert_eq "kustomization digest 同时正常写" "$DIGEST_2" "$actual_kustomization_digest_2"

echo ""
echo "=== Test 3: ENV_PATCH_SELECTOR 命中 0 项 → fail-closed（防误配/漂移） ==="
TMP3="$(mktemp -d)"
setup_fixture "$TMP3"
set +e
(
  source "$TARGET_SCRIPT" --source-only
  LOCK_PATH="$TMP3/image-lock.yaml" KUSTOMIZATION_PATH="$TMP3/kustomization.yaml" \
    IMAGE="docker.io/wontlost/aster-replay-runner" \
    ENV_PATCH_PATH="$TMP3/deployment.yaml" \
    ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "NONEXISTENT_ENV")' \
    patch_targets "sha256:$(printf 'c%.0s' $(seq 1 64))" "seed-sha" "789"
)
rc=$?
set -e
if [[ "$rc" != "0" ]]; then
  echo "  ✓ 选择器命中 0 项时 patch_targets 非零退出（fail-closed）"
else
  echo "  ✗ 选择器命中 0 项时 patch_targets 未拒绝（应 fail-closed）"
  FAILED=1
fi
rm -rf "$TMP3"

# ── Test 3b（★Codex 抓）：selector 命中恰 1 项但命中的是**别的 env**（非 RUNNER_IMAGE_DIGEST）
#    → 名字校验 fail-closed（防错配 selector 静默 patch 错 env 而 RUNNER_IMAGE_DIGEST 不更新）──
echo "=== Test 3b: selector 命中 1 项但非 RUNNER_IMAGE_DIGEST（如 PORT）→ 名字校验 fail-closed ==="
TMP3B="$(mktemp -d)"
setup_fixture "$TMP3B"
# 造一个含第二个 env（PORT）的 deployment，供错配 selector 命中。
cat > "$TMP3B/deployment.yaml" <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: runner-launcher
spec:
  template:
    spec:
      containers:
        - name: runner-launcher
          env:
            - name: PORT
              value: "8080"
            - name: RUNNER_IMAGE_DIGEST
              value: sha256:0000000000000000000000000000000000000000000000000000000000000000
YAML
set +e
(
  source "$TARGET_SCRIPT" --source-only
  LOCK_PATH="$TMP3B/image-lock.yaml" KUSTOMIZATION_PATH="$TMP3B/kustomization.yaml" \
    IMAGE="docker.io/wontlost/aster-replay-runner" \
    ENV_PATCH_PATH="$TMP3B/deployment.yaml" \
    ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "PORT")' \
    patch_targets "sha256:$(printf 'd%.0s' $(seq 1 64))" "seed-sha" "999"
)
rc=$?
set -e
# 校验：既非零退出，且 RUNNER_IMAGE_DIGEST env 未被改（仍是占位全零，证明没 patch 错 env）。
rid_after="$(yq '.spec.template.spec.containers[0].env[] | select(.name == "RUNNER_IMAGE_DIGEST") | .value' "$TMP3B/deployment.yaml")"
if [[ "$rc" != "0" && "$rid_after" == "sha256:0000000000000000000000000000000000000000000000000000000000000000" ]]; then
  echo "  ✓ 错配 selector（命中 PORT）→ 名字校验拒 + RUNNER_IMAGE_DIGEST 未被误改（fail-closed）"
else
  echo "  ✗ 错配 selector 未被名字校验拦（rc=$rc, RUNNER_IMAGE_DIGEST=$rid_after）"
  FAILED=1
fi
rm -rf "$TMP3B"

echo ""
echo "=== Test 4: 只设 ENV_PATCH_* 之一 → 成对 XOR fail-closed（Codex 抓的配置漂移）==="
# 主流程运行脚本（非 source），只设一个 ENV_PATCH_* + dummy IMAGE/BRANCH。XOR 校验在 clone/写入
# 之前，故不触网——应 exit 2。DIGEST 给合法形状假值满足前置 :? 与 shape 校验。code 在子壳外捕获。
DUMMY_DIGEST="sha256:$(printf '0%.0s' {1..64})"

# ★用 || 捕获退出码——test 顶部 set -e 会让非零命令直接 abort，故须 `|| code=$?` 兜住。
code_only_path=0
ENV_PATCH_PATH="/tmp/whatever-deployment.yaml" ENV_PATCH_SELECTOR="" \
  GH_TOKEN="x" DIGEST="$DUMMY_DIGEST" SOURCE_SHA="x" RUN_ID="0" \
  bash "$TARGET_SCRIPT" docker.io/wontlost/foo image-pin/foo >/dev/null 2>&1 || code_only_path=$?
assert_eq "只设 ENV_PATCH_PATH → exit 2（成对 XOR fail-closed）" "2" "$code_only_path"

code_only_sel=0
ENV_PATCH_PATH="" ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "X")' \
  GH_TOKEN="x" DIGEST="$DUMMY_DIGEST" SOURCE_SHA="x" RUN_ID="0" \
  bash "$TARGET_SCRIPT" docker.io/wontlost/foo image-pin/foo >/dev/null 2>&1 || code_only_sel=$?
assert_eq "只设 ENV_PATCH_SELECTOR → exit 2（成对 XOR fail-closed）" "2" "$code_only_sel"

echo ""
echo "=== Test 5: PIN_DEPLOY_BINDING=env → patch_targets 写 image-lock + deployment env，不写 kustomization ==="
TMP5="$(mktemp -d)"
setup_fixture "$TMP5"
KUST5_BEFORE="$(shasum -a 256 "$TMP5/kustomization.yaml" | awk '{print $1}')"
DIGEST_5="sha256:$(printf 'd%.0s' $(seq 1 64))"
(
  source "$TARGET_SCRIPT" --source-only
  PIN_DEPLOY_BINDING=env \
  LOCK_PATH="$TMP5/image-lock.yaml" KUSTOMIZATION_PATH="$TMP5/kustomization.yaml" \
    IMAGE="docker.io/wontlost/aster-replay-runner" \
    ENV_PATCH_PATH="$TMP5/deployment.yaml" \
    ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "RUNNER_IMAGE_DIGEST")' \
    patch_targets "$DIGEST_5" "seed-sha" "500"
)
actual_lock_5="$(yq '.images[0].digest' "$TMP5/image-lock.yaml")"
assert_eq "env 模式：image-lock digest 已写" "$DIGEST_5" "$actual_lock_5"
actual_dep_5="$(yq '.spec.template.spec.containers[0].env[] | select(.name == "RUNNER_IMAGE_DIGEST") | .value' "$TMP5/deployment.yaml")"
assert_eq "env 模式：deployment RUNNER_IMAGE_DIGEST env 已写" "$DIGEST_5" "$actual_dep_5"
KUST5_AFTER="$(shasum -a 256 "$TMP5/kustomization.yaml" | awk '{print $1}')"
assert_eq "env 模式：kustomization 字节级零改动（未写）" "$KUST5_BEFORE" "$KUST5_AFTER"
rm -rf "$TMP5"

echo ""
echo "=== Test 6: PIN_DEPLOY_BINDING 非法值 → exit 2 ==="
DUMMY_DIGEST6="sha256:$(printf '0%.0s' {1..64})"
code6=0
PIN_DEPLOY_BINDING=bogus \
  GH_TOKEN="x" DIGEST="$DUMMY_DIGEST6" SOURCE_SHA="x" RUN_ID="0" \
  bash "$TARGET_SCRIPT" docker.io/wontlost/foo image-pin/foo >/dev/null 2>&1 || code6=$?
assert_eq "非法 PIN_DEPLOY_BINDING → exit 2" "2" "$code6"

echo ""
echo "=== Test 7: PIN_DEPLOY_BINDING=kustomization（默认）+ 设 ENV_PATCH_* → 互斥 exit 2 ==="
DUMMY_DIGEST7="sha256:$(printf '0%.0s' {1..64})"
code7=0
PIN_DEPLOY_BINDING=kustomization \
  ENV_PATCH_PATH="/tmp/whatever.yaml" \
  ENV_PATCH_SELECTOR='.spec.template.spec.containers[0].env[] | select(.name == "X")' \
  GH_TOKEN="x" DIGEST="$DUMMY_DIGEST7" SOURCE_SHA="x" RUN_ID="0" \
  bash "$TARGET_SCRIPT" docker.io/wontlost/foo image-pin/foo >/dev/null 2>&1 || code7=$?
assert_eq "kustomization 模式设 ENV_PATCH → 互斥 exit 2" "2" "$code7"

echo ""
echo "=== Test 8: PIN_DEPLOY_BINDING=env 但缺 ENV_PATCH_* → exit 2（env 模式必须提供部署真相）==="
DUMMY_DIGEST8="sha256:$(printf '0%.0s' {1..64})"
code8=0
PIN_DEPLOY_BINDING=env \
  GH_TOKEN="x" DIGEST="$DUMMY_DIGEST8" SOURCE_SHA="x" RUN_ID="0" \
  bash "$TARGET_SCRIPT" docker.io/wontlost/foo image-pin/foo >/dev/null 2>&1 || code8=$?
assert_eq "env 模式缺 ENV_PATCH → exit 2" "2" "$code8"

echo ""
if [[ "$FAILED" == "0" ]]; then
  echo "全部通过（零改动回归 + 第三目标激活 + fail-closed 校验 + XOR 成对 + binding-mode env/枚举/互斥/名字校验）。"
  exit 0
else
  echo "存在失败用例，见上方 ✗。"
  exit 1
fi
