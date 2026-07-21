// Package orchestrator 负责把一次 launch 请求编排成一个 digest-pinned runner Job，
// watch 到终态并读回 runner envelope。launcher 只编排不产证据——透传 runner 产的字段不改。
package orchestrator

// RunnerRequest 逐字对齐 aster-api runner 的 RunnerRequest.java（JSON 字段名一致）。
// role 不在此结构（role 只在 HMAC header，不进 runner request body——见 F 契约）。
type RunnerRequest struct {
	TenantID     string              `json:"tenantId"`
	Source       string              `json:"source"`
	Input        any                 `json:"input"`
	Locale       string              `json:"locale"`
	FunctionName string              `json:"functionName"`
	AliasSet     map[string][]string `json:"aliasSet"` // 可为 null（omitempty 不加——须显式发 null 对齐 Java 侧）
}

// ReplayMetadata 是 5 个 replay-critical 字段 + 可选 runtimeToolchainId（仅诊断，不进 parity）。
// 逐字对齐 cloud F client 的 LaunchReplayMetadata（runner-launcher-client.ts:15-22）。
type ReplayMetadata struct {
	CanonicalInputHash      *string `json:"canonicalInputHash"`
	CanonicalOutputHash     *string `json:"canonicalOutputHash"`
	CanonicalizationVersion *string `json:"canonicalizationVersion"`
	ReplayabilityStatus     *string `json:"replayabilityStatus"`
	TraceHash               *string `json:"traceHash"`
	RuntimeToolchainID      *string `json:"runtimeToolchainId,omitempty"`
}

// RunnerEnvelope 逐字对齐 aster-api runner 的 RunnerEnvelope.java（NON_NULL 序列化：
// 成功承 replayMetadata，错误承 errorCode/message/phase，二者不共存）。launcher 原样透传。
type RunnerEnvelope struct {
	Outcome        string          `json:"outcome"` // "SUCCESS" | "ERROR"
	ReplayMetadata *ReplayMetadata `json:"replayMetadata,omitempty"`
	ErrorCode      string          `json:"errorCode,omitempty"`
	Message        string          `json:"message,omitempty"`
	Phase          string          `json:"phase,omitempty"`
}
