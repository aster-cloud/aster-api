// Command launcher 是 in-cluster runner-launcher 微服务入口：
// 收 cloud（经 Cloudflare Tunnel）的 HMAC 请求 → 建 digest-pinned runner Job → 回传 envelope。
// ★in-cluster：用 rest.InClusterConfig（由 launcher Pod 的 SA token 提供 API 凭据）。
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"github.com/aster-cloud/aster-api/launcher/internal/httpapi"
	"github.com/aster-cloud/aster-api/launcher/internal/orchestrator"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

func main() {
	// ★fail-loud 预检：HMAC key 未配置则拒绝启动（绝不带缺失 key 上线——VerifyHMAC 会对每个
	//   请求返回内部错，但启动即崩比运行时静默拒绝所有请求更诚实，也让 readiness 探针失败快速暴露）。
	if os.Getenv("ASTER_RUNNER_LAUNCHER_HMAC_KEY") == "" {
		log.Fatal("ASTER_RUNNER_LAUNCHER_HMAC_KEY 未配置——拒绝启动（HMAC key 隔离铁律）")
	}

	// Fork A：runner digest 从 launcher Deployment env 读（image-pin 脚本第三写目标 patch 之）。
	digest := os.Getenv("RUNNER_IMAGE_DIGEST")
	if digest == "" {
		log.Fatal("RUNNER_IMAGE_DIGEST 未配置——拒绝启动（无 digest 无法 pin runner 镜像）")
	}

	// RUNNER_NAMESPACE 与 orchestrator 常量一致性校验：不一致则拒绝启动（防 manifest 与代码漂移）。
	if ns := os.Getenv("RUNNER_NAMESPACE"); ns != "" && ns != orchestrator.Namespace() {
		log.Fatalf("RUNNER_NAMESPACE=%q 与编排器常量 %q 不一致——拒绝启动", ns, orchestrator.Namespace())
	}

	// in-cluster k8s clientset：由 launcher Pod 挂载的 SA token（automountServiceAccountToken:true）提供。
	cfg, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("获取 in-cluster 配置失败（launcher 须跑在 Pod 内 + 挂载 SA token）: %v", err)
	}
	clientset, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		log.Fatalf("构建 k8s clientset 失败: %v", err)
	}

	orch := &orchestrator.K8sOrchestrator{Clientset: clientset, Digest: digest}
	mux := buildMux(orch)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	log.Printf("runner-launcher 监听 :%s（runner digest=%s ns=%s）", port, digest, orchestrator.Namespace())
	// ★显式 http.Server 配超时（非默认零值 http.Server，防 slow-loris——最终 review 抓）：
	//   NetworkPolicy 本 slice 禁用，ClusterIP 集群内任意 workload 可达（非仅 cloudflared），
	//   零值 Server 的 header 读无上限=慢速连接可耗尽 goroutine。ReadHeaderTimeout 关此面；
	//   ReadTimeout/IdleTimeout 兜整请求与空闲连接。★不设 WriteTimeout——runJob 可能跑满 30s
	//   SLA（建 Job+冷启动+读 log），过短 WriteTimeout 会截断合法慢响应。
	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	// ListenAndServe 阻塞；返回即致命（Pod 会被 k8s 重启）。
	if err := srv.ListenAndServe(); err != nil {
		log.Fatalf("ListenAndServe 退出: %v", err)
	}
}

// buildMux 装配路由：/healthz（探针）+ /api/v1/runner/launch（LaunchHandler）。
// ★抽出便于单测（main 只做 clientset 构造 + 监听，不可单测；路由装配可单测）。
func buildMux(orch orchestrator.Orchestrator) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", httpapi.Healthz)
	mux.Handle("/api/v1/runner/launch", &httpapi.LaunchHandler{Orch: orch})
	return mux
}
