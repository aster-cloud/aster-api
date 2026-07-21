package orchestrator

import (
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
)

func testReq() RunnerRequest {
	return RunnerRequest{
		TenantID: "t1", Source: "Module M ...", Input: map[string]any{"x": 1},
		Locale: "en-US", FunctionName: "f", AliasSet: nil,
	}
}

func TestBuildRunnerJob_ForkC_And_Hardening(t *testing.T) {
	const digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	job, cm := buildRunnerJob(testReq(), digest)

	// ConfigMap 持 request.json，且 owner-ref 到 Job（级联 GC）。
	if _, ok := cm.Data["request.json"]; !ok {
		t.Fatal("configmap 缺 request.json")
	}
	if len(cm.OwnerReferences) != 1 || cm.OwnerReferences[0].Kind != "Job" {
		t.Fatalf("configmap owner-ref 未指向 Job: %+v", cm.OwnerReferences)
	}

	spec := job.Spec.Template.Spec
	// Fork A：image = @digest（不可是 tag）。
	img := spec.Containers[0].Image
	if !strings.HasSuffix(img, "@"+digest) || !strings.Contains(img, "aster-replay-runner") {
		t.Fatalf("image 未 digest-pin: %q", img)
	}
	// Fork C：主容器 command 覆写 stdin 重定向。
	wantCmd := []string{"/bin/sh", "-c", "exec /app/bin/runner < /work/request.json"}
	if strings.Join(spec.Containers[0].Command, "\x00") != strings.Join(wantCmd, "\x00") {
		t.Fatalf("主容器 command 未覆写: %v", spec.Containers[0].Command)
	}
	// Fork C：initContainer copy configmap → /work/request.json。
	if len(spec.InitContainers) != 1 {
		t.Fatalf("缺 initContainer: %v", spec.InitContainers)
	}

	// 硬化断言（镜像 migrate-job.yaml）。
	if job.Spec.BackoffLimit == nil || *job.Spec.BackoffLimit != 2 {
		t.Fatal("backoffLimit != 2")
	}
	if job.Spec.TTLSecondsAfterFinished == nil {
		t.Fatal("缺 ttlSecondsAfterFinished")
	}
	if spec.RestartPolicy != corev1.RestartPolicyNever {
		t.Fatalf("restartPolicy=%v want Never", spec.RestartPolicy)
	}
	if spec.AutomountServiceAccountToken == nil || *spec.AutomountServiceAccountToken {
		t.Fatal("automountServiceAccountToken 应为 false")
	}
	if spec.SecurityContext == nil || spec.SecurityContext.RunAsNonRoot == nil || !*spec.SecurityContext.RunAsNonRoot {
		t.Fatal("runAsNonRoot 应为 true")
	}
	c := spec.Containers[0].SecurityContext
	if c == nil || c.ReadOnlyRootFilesystem == nil || !*c.ReadOnlyRootFilesystem {
		t.Fatal("readOnlyRootFilesystem 应为 true")
	}
	if c.AllowPrivilegeEscalation == nil || *c.AllowPrivilegeEscalation {
		t.Fatal("allowPrivilegeEscalation 应为 false")
	}
	if c.Capabilities == nil || len(c.Capabilities.Drop) == 0 || c.Capabilities.Drop[0] != "ALL" {
		t.Fatal("capabilities 应 drop ALL")
	}
	// emptyDir /work + /tmp 均可写（readOnlyRootFilesystem 下靠 emptyDir 提供可写路径）。
	var work, tmp bool
	for _, v := range spec.Volumes {
		if v.EmptyDir == nil {
			continue
		}
		switch v.Name {
		case "work":
			work = true
		case "tmp":
			tmp = true
		}
	}
	if !work || !tmp {
		t.Fatalf("缺 emptyDir work/tmp: work=%v tmp=%v", work, tmp)
	}
}
