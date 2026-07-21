package orchestrator

import (
	"context"

	"k8s.io/client-go/kubernetes"
)

// Orchestrator 是 handler 依赖的编排 seam：把一次 RunnerRequest 跑成 RunnerEnvelope。
// ★接口而非直接调 runJob——handler 单测注入 mock（不需真集群），生产注入 K8sOrchestrator。
type Orchestrator interface {
	// Run 编排一次 runner 执行。系统性失败返回 (zero, err)（其中 ErrUnavailable 归 F 契约 unavailable）；
	// runner 业务错（outcome=="ERROR"）经 env 正常返回（err==nil）——由 handler 按 outcome 映射。
	Run(ctx context.Context, req RunnerRequest) (RunnerEnvelope, error)
}

// K8sOrchestrator 是 Orchestrator 的生产实现：用 in-cluster clientset 建 digest-pinned Job。
// Digest 由 main.go 从 RUNNER_IMAGE_DIGEST env 注入（Fork A：runner digest 载体）。
type K8sOrchestrator struct {
	Clientset kubernetes.Interface
	Digest    string
}

// Run 透传 Digest 给 runJob（Task 2.2）——不改字段、不产证据，只编排。
func (o *K8sOrchestrator) Run(ctx context.Context, req RunnerRequest) (RunnerEnvelope, error) {
	return runJob(ctx, o.Clientset, req, o.Digest)
}
