#!/usr/bin/env bash
# 产 expected.json 基线 = aster-api 生产 evaluateSource(replayCapture=true) 对固定 corpus 的
# ReplayMetadata（spec L133 权威对照，非 host runner 自产——避免 runner-vs-runner 自证）。
#
# ★对照语义（诚实边界，见 task0-arm64-parity.sh 顶注）：
#   expected = aster-api（host/生产 REST 路径）的 ReplayMetadata；
#   candidate（task0 脚本比对方）= runner（arm64 容器/stock-JRE）的 envelope.replayMetadata。
#   Task 0 证的是「runner 打包+容器+arm64+stock-JRE 环境相对 aster-api 有无分叉」。
#
# 实现：经专用 @QuarkusTest（GenExpectedCorpusTest）驱动 corpus 每个请求打真实生产端点
#   POST /api/v1/policies/evaluate-source?replayCapture=true（HMAC 已验证内部调用方路径），
#   取响应 replayMetadata 写同名 .expected.json。复用 PolicyEvaluationReplayOrderingTest 的
#   HMAC harness，不新起 HTTP server。
#
# 前置：本地 postgres（DB=aster_policy，见 memory）+ Testcontainers 自管 Redis 可用
#   （@QuarkusTest 需 DB；缺 DB 会 Failed to start quarkus）。
set -euo pipefail

cd "$(dirname "$0")/.."   # aster-replay-runner/
ROOT="$(cd ../ && pwd)"   # aster-api/
CORPUS_DIR="$PWD/src/test/resources/parity-corpus"

echo "== gen-expected：经 aster-api evaluateSource 权威路径产 expected.json =="
echo "   corpus dir = $CORPUS_DIR"

# -Dparity.gen.expected=true 开写盘门控；-Dparity.corpus.dir 传 corpus 绝对路径。
# ★用 :test（root project 测试任务）——GenExpectedCorpusTest 在 aster-api 根 src/test，
#   非 runner 子模块；裸 `test` 会连带跑 :aster-replay-runner:test 且 --tests 无匹配报错。
(cd "$ROOT" && ./gradlew :test \
    --tests "io.aster.replay.parity.GenExpectedCorpusTest" \
    -Dparity.corpus.dir="$CORPUS_DIR" \
    -Dparity.gen.expected=true)

echo "== expected.json 已由 aster-api evaluateSource 路径生成（权威对照）=="
ls -1 "$CORPUS_DIR"/*.expected.json
