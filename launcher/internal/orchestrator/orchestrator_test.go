package orchestrator

import (
	"context"
	"errors"
	"testing"
	"time"

	batchv1 "k8s.io/api/batch/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
)

// K8sOrchestrator.Run 应把 digest 透传给 runJob，并把 runJob 的 (env,err) 原样返回。
// 用 fake clientset：Job 超时 → Run 返回 ErrUnavailable（验证 seam 不吞错、digest 透传）。
//
// ★brief 原测试在 Run 返回后 List Jobs 断言——但 runJob（Task 2.2，Codex 已加固）在任何 error
// 路径都会 best-effort 删已建的 Job（防超时/失败泄漏，见 run.go 的 defer 清理），fake clientset
// 的 Delete 同步生效，故返回后 List 恒为 0，post-return 断言必然失败。改用与
// run_test.go#TestRunJob_ConfigMapOwnerRefUIDBackfilled 一致的 PrependReactor 模式：在
// create 那一刻捕获 Job 镜像串，不依赖 Run 返回后仍存在——这才是可靠断言点。
func TestK8sOrchestrator_Run_TimeoutPropagates(t *testing.T) {
	const digest = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
	cs := fake.NewSimpleClientset()

	var capturedImage string
	var capturedCount int
	cs.PrependReactor("create", "jobs", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if createAction, ok := action.(k8stesting.CreateAction); ok {
			if job, ok := createAction.GetObject().(*batchv1.Job); ok {
				capturedCount++
				capturedImage = job.Spec.Template.Spec.Containers[0].Image
			}
		}
		return false, nil, nil // 不 short-circuit：交给默认 tracker 完成真正的 create/持久化。
	})

	o := &K8sOrchestrator{Clientset: cs, Digest: digest}

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, err := o.Run(ctx, testReq()); err == nil {
		t.Fatal("期望超时 error（Job 永不终态），得 nil")
	} else if !errors.Is(err, ErrUnavailable) {
		t.Fatalf("期望 ErrUnavailable，得 %v", err)
	}

	// 断言 Run 确实建了 1 个 digest-pin 的 Job（seam 未丢 digest；create 时刻捕获，不受
	// 超时后 best-effort 清理影响）。
	if capturedCount != 1 {
		t.Fatalf("期望建 1 个 Job，得 %d", capturedCount)
	}
	if wantSuffix := "@" + digest; len(capturedImage) < len(wantSuffix) || capturedImage[len(capturedImage)-len(wantSuffix):] != wantSuffix {
		t.Fatalf("Job image 未用注入 digest: %q", capturedImage)
	}
}
