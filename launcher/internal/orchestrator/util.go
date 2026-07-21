package orchestrator

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
)

func boolPtr(b bool) *bool    { return &b }
func int32Ptr(v int32) *int32 { return &v }
func int64Ptr(v int64) *int64 { return &v }

// newJobName 生成 per-invocation 唯一 Job 名（k8s 名须小写 DNS-1123；用 8 字节随机 hex）。
func newJobName() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("runner-%s", hex.EncodeToString(b))
}
