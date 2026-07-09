#!/usr/bin/env bash
# open-image-pin-pr —— 源仓 CI 用：开/更新 image-pin PR 到 k3s（统一 digest pin A′ Phase 2）
#
# 见 wontlost-ltd/k3s#4。Codex 对抗式审查修正（81→）：token 用 extraheader 不进 .git/config；
# force-with-lease 用远端实际 SHA 作 lease 基线；gh pr list --state open 判存在；无 diff+无 open
# PR 时干净退出；校验目标 entry 唯一存在。
#
# 用法：open-image-pin-pr.sh <IMAGE> <BRANCH>
#   IMAGE  = 如 docker.io/wontlost/aster-api（必须在 k3s image-lock 里唯一存在）
#   BRANCH = 固定分支名，如 image-pin/aster-api
# 环境变量（必需）：
#   GH_TOKEN     k3s-scoped installation token（contents+PR write）
#   DIGEST       本次已验签 manifest-list digest（sha256:...）
#   SOURCE_SHA   github.sha
#   RUN_ID       github.run_id
# 环境变量（可选）：
#   K3S_REPO     默认 wontlost-ltd/k3s
#   LOCK_PATH    默认 apps/aster-lang/cloud/image-lock.yaml
#   YQ_VERSION   默认 v4.44.3
#   YQ_SHA256    yq_linux_amd64 的 sha256（提供则校验；强烈建议在 workflow 传入）
set -euo pipefail

IMAGE="${1:?usage: open-image-pin-pr.sh <IMAGE> <BRANCH>}"
BRANCH="${2:?usage: open-image-pin-pr.sh <IMAGE> <BRANCH>}"
K3S_REPO="${K3S_REPO:-wontlost-ltd/k3s}"
LOCK_PATH="${LOCK_PATH:-apps/aster-lang/cloud/image-lock.yaml}"
KUSTOMIZATION_PATH="${KUSTOMIZATION_PATH:-apps/aster-lang/cloud/kustomization.yaml}"
YQ_VERSION="${YQ_VERSION:-v4.44.3}"

: "${GH_TOKEN:?需要 GH_TOKEN（k3s-scoped token）}"
: "${DIGEST:?需要 DIGEST}"
: "${SOURCE_SHA:?需要 SOURCE_SHA}"
: "${RUN_ID:?需要 RUN_ID}"
[[ "$DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "::error::DIGEST 形状非法：$DIGEST"; exit 1; }

# ── 安装 yq（可选 checksum 校验）──
if ! command -v yq >/dev/null; then
  curl -fsSL -o /tmp/yq "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64"
  if [[ -n "${YQ_SHA256:-}" ]]; then
    echo "${YQ_SHA256}  /tmp/yq" | sha256sum -c - || { echo "::error::yq checksum 校验失败"; exit 1; }
  fi
  sudo install -m 0755 /tmp/yq /usr/local/bin/yq
fi

# ── clone k3s：token 走 extraheader，不写进 remote URL（不落 .git/config）──
AUTH="AUTHORIZATION: bearer ${GH_TOKEN}"
git -c "http.https://github.com/.extraheader=${AUTH}" \
  clone --depth=1 "https://github.com/${K3S_REPO}.git" k3s
cd k3s
# 后续所有 remote 操作都带 extraheader，保持 token 不落盘。
git_c() { git -c "http.https://github.com/.extraheader=${AUTH}" "$@"; }

# ── 校验目标 entry 在 image-lock 中唯一存在 ──
count="$(IMAGE="$IMAGE" yq '.images | map(select(.image == strenv(IMAGE))) | length' "$LOCK_PATH")"
[[ "$count" == "1" ]] || { echo "::error::image-lock 中 ${IMAGE} 的 entry 数=${count} (需恰好 1)"; exit 1; }

# Phase 3 keystone：kustomization 里本镜像也必须唯一存在（双写目标）。
kcount="$(IMAGE="$IMAGE" yq '.images | map(select(.name == strenv(IMAGE))) | length' "$KUSTOMIZATION_PATH")"
[[ "$kcount" == "1" ]] || { echo "::error::kustomization 中 ${IMAGE} 的 images entry 数=${kcount} (需恰好 1)"; exit 1; }

# ── 从最新 origin/main 重建固定分支，双写本 entry（image-lock 验签真相 + kustomization 部署真相）──
git checkout -B "$BRANCH" origin/main
DIGEST="$DIGEST" SOURCE_SHA="$SOURCE_SHA" RUN_ID="$RUN_ID" IMAGE="$IMAGE" yq -i '
  (.images[] | select(.image == strenv(IMAGE))).digest    = strenv(DIGEST)  |
  (.images[] | select(.image == strenv(IMAGE))).sourceSha = strenv(SOURCE_SHA) |
  (.images[] | select(.image == strenv(IMAGE))).runId     = strenv(RUN_ID)
' "$LOCK_PATH"
# kustomization：只改本镜像的 digest（部署真相），k3s verify 校验 == image-lock digest。
DIGEST="$DIGEST" IMAGE="$IMAGE" yq -i '
  (.images[] | select(.name == strenv(IMAGE))).digest = strenv(DIGEST)
' "$KUSTOMIZATION_PATH"

open_pr_number() {
  gh pr list -R "$K3S_REPO" --head "$BRANCH" --base main --state open \
    --json number --jq '.[0].number // empty'
}

if git diff --quiet -- "$LOCK_PATH" "$KUSTOMIZATION_PATH"; then
  # 无变更：main 已是本 digest。若恰有 open PR 则确保 auto-merge，否则干净退出（不 create）。
  pr="$(open_pr_number)"
  if [[ -n "$pr" ]]; then
    echo "image-lock 已最新，复用 open PR #$pr 确保 auto-merge"
    gh pr merge "$pr" -R "$K3S_REPO" --auto --squash --delete-branch
  else
    echo "image-lock 已最新且无 open PR → 无需开 PR，退出"
  fi
  exit 0
fi

git config user.name  "aster-image-pin[bot]"
git config user.email "301590099+aster-image-pin[bot]@users.noreply.github.com"
git add "$LOCK_PATH" "$KUSTOMIZATION_PATH"
git commit -m "chore(image-pin): ${IMAGE##*/} → ${DIGEST} (sha ${SOURCE_SHA})"

# ── force-with-lease 用远端实际 SHA 作基线（防同 App 并发 run 互相覆盖）──
remote_sha="$(git_c ls-remote --heads origin "$BRANCH" | cut -f1 || true)"
if [[ -n "$remote_sha" ]]; then
  git_c push --force-with-lease="${BRANCH}:${remote_sha}" origin "$BRANCH"
else
  git_c push origin "$BRANCH"
fi

# ── 只查 open PR 决定复用/创建；create/merge 用 --match-head-commit 锁定本 run 的 head ──
head_sha="$(git rev-parse HEAD)"
pr="$(open_pr_number)"
if [[ -z "$pr" ]]; then
  gh pr create -R "$K3S_REPO" --base main --head "$BRANCH" \
    --title "chore(image-pin): ${IMAGE##*/} digest bump" \
    --body $'自动开启（源仓 CI）。\n\nimage: '"${IMAGE}"$'\ndigest: '"${DIGEST}"$'\nsourceSha: '"${SOURCE_SHA}"$'\nrunId: '"${RUN_ID}"$'\n\nstale/红：等待最新源仓 CI 重开，或对 latest main 手动 rerun（不允许旧 SHA 绕过 freshness）。' \
    --label image-pin
  pr="$(open_pr_number)"
else
  echo "复用 open PR #$pr"
fi
# enable auto-merge 失败必须 fail-loud（PR 开着不合＝发布链断）。
gh pr merge "$pr" -R "$K3S_REPO" --auto --squash --delete-branch --match-head-commit "$head_sha"
echo "image-pin PR #${pr} 就绪 (auto-merge enabled, head=${head_sha})"
