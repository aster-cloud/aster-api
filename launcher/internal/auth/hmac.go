// Package auth 实现 cloud→launcher 的 HMAC 验证中间件。
// 逐字节复刻已上线契约（runner-launcher-stub.ts + signRunnerLauncherHeaders）：
// 7 行 canonical、独立 key、±300s 时间戳窗口、常量时间比对。无状态（nonce 去重是 S2-1b）。
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"os"
	"strconv"
	"strings"
)

// ExpectedCaller 是 cloud 侧 signRunnerLauncherHeaders 固定写入的 caller 标识。
// 非此值一律 401（与 stub:29 的 caller !== 'cloud-runner-launcher' 一致）。
const ExpectedCaller = "cloud-runner-launcher"

// TimestampWindowSeconds 是时间戳允许的时钟偏移窗口（秒），双向。与 stub:35 的 300 一致。
const TimestampWindowSeconds = 300

// nowUnix 抽成变量便于测试注入（默认取真实时钟）。
var nowUnix = func() int64 { return timeNowUnix() }

// VerifyHMAC 验证请求签名。返回 (tenant, role, status)：
//   - status == 0：通过，tenant/role 为已验证的 X-Aster-Tenant/X-Aster-Role。
//   - status == 401：缺 header / caller 不符 / 时间戳无效或越窗（fail-fast，不做 crypto）。
//   - status == 403：签名不匹配（key 隔离验证：错 key 签的 sig 对不上真 key）。
//   - status == 500：ASTER_RUNNER_LAUNCHER_HMAC_KEY 未配置（handler 转结构化响应）。
//
// body 由调用方预读一次（body 不可重读）——须是与 client 签名时逐字节一致的 raw UTF-8。
func VerifyHMAC(r *http.Request, body []byte) (tenant, role string, status int) {
	key := os.Getenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY")
	if key == "" {
		return "", "", http.StatusInternalServerError
	}

	sig := r.Header.Get("X-Internal-Signature")
	timestamp := r.Header.Get("X-Aster-Timestamp")
	nonce := r.Header.Get("X-Aster-Nonce")
	caller := r.Header.Get("X-Internal-Caller")
	tenant = r.Header.Get("X-Aster-Tenant")
	role = r.Header.Get("X-Aster-Role")

	// (1) 缺签名头 / caller 不符 → 401（stub:29-31）。
	//     tenant/role 允许为空串，但 header 必须存在（client 恒发；用 Values 判存在性）。
	if sig == "" || timestamp == "" || nonce == "" || caller != ExpectedCaller ||
		!headerPresent(r, "X-Aster-Tenant") || !headerPresent(r, "X-Aster-Role") {
		return "", "", http.StatusUnauthorized
	}

	// (2) 时间戳窗口，parse/finite 守卫在前（stub:34-36）：
	//     Number('abc')=NaN 在 Go 表现为 ParseInt err → 401，避免 NaN 误放行。
	ts, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return "", "", http.StatusUnauthorized
	}
	if abs64(nowUnix()-ts) > TimestampWindowSeconds {
		return "", "", http.StatusUnauthorized
	}

	// (3) 重算 bodyHash 重建 canonical，常量时间比对（stub:44-51）。
	sum := sha256.Sum256(body)
	bodyHash := hex.EncodeToString(sum[:])
	canonical := strings.Join(
		[]string{r.Method, r.URL.Path, timestamp, nonce, bodyHash, tenant, role}, "\n")
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(canonical))
	expected := mac.Sum(nil)

	// 收到的 sig 是小写 hex；解码失败即不可能匹配 → 403。
	got, decErr := hex.DecodeString(sig)
	if decErr != nil || !hmac.Equal(got, expected) {
		return "", "", http.StatusForbidden
	}
	return tenant, role, 0
}

// headerPresent 判断 header 是否存在（区分「空串」与「缺失」——client 恒发这两个头）。
func headerPresent(r *http.Request, name string) bool {
	_, ok := r.Header[http.CanonicalHeaderKey(name)]
	return ok
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
