package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

// 测试用固定 key（与 client signRunnerLauncherHeaders 的 ASTER_RUNNER_LAUNCHER_HMAC_KEY 同角色）。
const testKey = "test-runner-launcher-hmac-key"

// signCanonical 复刻 client 侧签名：小写 hex HMAC-SHA256(key, 7 行 canonical)。
// ★测试是契约的可执行规格——它必须与 signRunnerLauncherHeaders 逐字节一致，否则守不住 drift。
func signCanonical(t *testing.T, key, method, path, ts, nonce string, body []byte, tenant, role string) string {
	t.Helper()
	sum := sha256.Sum256(body)
	bodyHash := hex.EncodeToString(sum[:])
	canonical := strings.Join([]string{method, path, ts, nonce, bodyHash, tenant, role}, "\n")
	mac := hmac.New(sha256.New, []byte(key))
	mac.Write([]byte(canonical))
	return hex.EncodeToString(mac.Sum(nil))
}

// buildRequest 造一个带全套签名头的 *http.Request（body 单独返回供 VerifyHMAC 用）。
func buildRequest(t *testing.T, key, tenant, role string, ts int64, tamper func(h http.Header)) (*http.Request, []byte) {
	t.Helper()
	const method, path, nonce = "POST", "/api/v1/runner/launch", "0123456789abcdef0123456789abcdef"
	body := []byte(`{"tenantId":"t1","source":"m","input":{},"locale":"en-US","functionName":"f","aliasSet":null}`)
	tsStr := strconv.FormatInt(ts, 10)
	sig := signCanonical(t, key, method, path, tsStr, nonce, body, tenant, role)
	r := httptest.NewRequest(method, path, nil)
	r.Header.Set("X-Internal-Caller", ExpectedCaller)
	r.Header.Set("X-Aster-Timestamp", tsStr)
	r.Header.Set("X-Aster-Nonce", nonce)
	r.Header.Set("X-Aster-Tenant", tenant)
	r.Header.Set("X-Aster-Role", role)
	r.Header.Set("X-Internal-Signature", sig)
	if tamper != nil {
		tamper(r.Header)
	}
	return r, body
}

func TestVerifyHMAC(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", testKey)
	now := time.Now().Unix()

	cases := []struct {
		name       string
		ts         int64
		tamper     func(h http.Header)
		wantStatus int // 0 = 通过
	}{
		{"valid", now, nil, 0},
		{"tampered-sig-403", now, func(h http.Header) { h.Set("X-Internal-Signature", "deadbeef"+h.Get("X-Internal-Signature")[8:]) }, http.StatusForbidden},
		{"expired-ts-401", now - 400, nil, http.StatusUnauthorized},
		{"future-ts-beyond-window-401", now + 400, nil, http.StatusUnauthorized},
		{"wrong-caller-401", now, func(h http.Header) { h.Set("X-Internal-Caller", "cloud-bff") }, http.StatusUnauthorized},
		{"missing-header-401", now, func(h http.Header) { h.Del("X-Aster-Nonce") }, http.StatusUnauthorized},
		{"nan-ts-401", now, func(h http.Header) { h.Set("X-Aster-Timestamp", "abc") }, http.StatusUnauthorized},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r, body := buildRequest(t, testKey, "t1", "user", tc.ts, tc.tamper)
			// ★NaN-ts 用例改了 header 里的 ts 但签名用的是原始有效 ts——本用例意在 ts parse 守卫
			//   先于 crypto 触发（stub:34-36）。故直接断言 status，不要求签名对这条也成立。
			gotTenant, gotRole, gotStatus := VerifyHMAC(r, body)
			if gotStatus != tc.wantStatus {
				t.Fatalf("status=%d want=%d", gotStatus, tc.wantStatus)
			}
			if tc.wantStatus == 0 {
				if gotTenant != "t1" || gotRole != "user" {
					t.Fatalf("tenant/role=%q/%q want t1/user", gotTenant, gotRole)
				}
			}
		})
	}
}

// 未配置 key → 500（launcher 绝不裸 panic；handler 会把 500 转结构化——见 Unit 3）。
func TestVerifyHMAC_MissingKey(t *testing.T) {
	t.Setenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY", "")
	r, body := buildRequest(t, testKey, "t1", "user", time.Now().Unix(), nil)
	if _, _, status := VerifyHMAC(r, body); status != http.StatusInternalServerError {
		t.Fatalf("status=%d want 500", status)
	}
}
