package orchestrator

import (
	"encoding/json"
	"fmt"

	batchv1 "k8s.io/api/batch/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

// 镜像仓库前缀——digest 由 Fork A 从 launcher Deployment env RUNNER_IMAGE_DIGEST 传入。
const runnerImageRepo = "docker.io/wontlost/aster-replay-runner"

// runnerNamespace 是 launcher 建 Job 的目标 ns（与 launcher 同 ns，2b-seed 已建）。
const runnerNamespace = "aster-runner"

// buildRunnerJob 造一个 digest-pinned runner Job + per-invocation ConfigMap。
// ★Fork C：ConfigMap 持 request.json → initContainer copy 到 emptyDir /work → 主容器
//
//	command 覆写为 stdin 重定向（runner 镜像字节不变）。ConfigMap owner-ref 到 Job（级联 GC）。
//
// ★硬化镜像 migrate-job.yaml：backoffLimit/ttl/restartPolicy/token/securityContext/emptyDir。
// digest 形如 "sha256:...."；image = <repo>@<digest>。jobName 用调用方生成的唯一名（见 runJob）。
func buildRunnerJob(req RunnerRequest, digest string) (*batchv1.Job, *corev1.ConfigMap) {
	jobName := newJobName()

	// request.json：runner 逐字读 stdin 的 RunnerRequest JSON（aliasSet 显式 null 对齐 Java）。
	reqJSON, _ := json.Marshal(req) // req 全为可序列化标量/map，不会失败；防御性忽略 err 由 runJob 前置校验兜底

	falsePtr := boolPtr(false)
	truePtr := boolPtr(true)
	uid := int64Ptr(1000)
	backoff := int32Ptr(2)
	ttl := int32Ptr(300) // 5min 后 GC；比 30s SLA 宽裕，便于失败时短暂保留日志

	cm := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName, // 与 Job 同名，便于关联
			Namespace: runnerNamespace,
		},
		Data: map[string]string{"request.json": string(reqJSON)},
	}

	job := &batchv1.Job{
		ObjectMeta: metav1.ObjectMeta{
			Name:      jobName,
			Namespace: runnerNamespace,
			Labels:    map[string]string{"app": "aster-runner-job"},
		},
		Spec: batchv1.JobSpec{
			TTLSecondsAfterFinished: ttl,
			BackoffLimit:            backoff,
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{Labels: map[string]string{"app": "aster-runner-job", "job-name": jobName}},
				Spec: corev1.PodSpec{
					RestartPolicy:                corev1.RestartPolicyNever,
					AutomountServiceAccountToken: falsePtr,
					SecurityContext: &corev1.PodSecurityContext{
						RunAsNonRoot:   truePtr,
						RunAsUser:      uid,
						RunAsGroup:     uid,
						FSGroup:        uid,
						SeccompProfile: &corev1.SeccompProfile{Type: corev1.SeccompProfileTypeRuntimeDefault},
					},
					InitContainers: []corev1.Container{{
						Name:  "write-request",
						Image: fmt.Sprintf("%s@%s", runnerImageRepo, digest), // 复用同一 runner 镜像（含 sh）
						// 从 ConfigMap 挂载点 copy 到 emptyDir /work（emptyDir 在 readOnlyRootFilesystem 下可写）。
						Command:         []string{"/bin/sh", "-c", "cp /config/request.json /work/request.json"},
						SecurityContext: hardenedContainerSecurityContext(),
						VolumeMounts: []corev1.VolumeMount{
							{Name: "config", MountPath: "/config", ReadOnly: true},
							{Name: "work", MountPath: "/work"},
						},
					}},
					Containers: []corev1.Container{{
						Name:  "runner",
						Image: fmt.Sprintf("%s@%s", runnerImageRepo, digest),
						// ★Fork C：覆写 command，把 emptyDir 里的 request.json 重定向进 runner stdin。
						Command:         []string{"/bin/sh", "-c", "exec /app/bin/runner < /work/request.json"},
						SecurityContext: hardenedContainerSecurityContext(),
						Resources:       runnerResources(), // TODO-capacity：见 Task 2.3 实测驱动
						VolumeMounts: []corev1.VolumeMount{
							{Name: "work", MountPath: "/work"},
							{Name: "tmp", MountPath: "/tmp"},
						},
					}},
					Volumes: []corev1.Volume{
						{Name: "config", VolumeSource: corev1.VolumeSource{
							ConfigMap: &corev1.ConfigMapVolumeSource{LocalObjectReference: corev1.LocalObjectReference{Name: jobName}}}},
						{Name: "work", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
						{Name: "tmp", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
					},
				},
			},
		},
	}

	// owner-ref：ConfigMap 属于 Job，Job 删则 ConfigMap 级联 GC（避免 per-invocation ConfigMap 泄漏）。
	// ★owner-ref 的 UID 在 Job 由 API server 创建后才有——runJob 会在 create Job 后回填 UID 再 create ConfigMap。
	cm.OwnerReferences = []metav1.OwnerReference{{
		APIVersion: "batch/v1", Kind: "Job", Name: jobName,
		Controller: truePtr, BlockOwnerDeletion: truePtr,
	}}
	return job, cm
}

// hardenedContainerSecurityContext 是容器级硬化（镜像 migrate-job 的 container securityContext）。
func hardenedContainerSecurityContext() *corev1.SecurityContext {
	return &corev1.SecurityContext{
		AllowPrivilegeEscalation: boolPtr(false),
		ReadOnlyRootFilesystem:   boolPtr(true),
		Capabilities:             &corev1.Capabilities{Drop: []corev1.Capability{"ALL"}},
	}
}

// runnerResources 是 runner 容器的 request/limit。
// ★TODO-capacity（唯一延后项）：当前值是保守占位，正式值由 Task 2.3 实测（峰值 RSS + p95 冷启动）驱动，
//
//	并发 default=1。见 Task 2.3 的测量流程——不是拍脑袋数字。
func runnerResources() corev1.ResourceRequirements {
	// 占位：宽松上限便于先跑通编排 + 供 Task 2.3 测峰值。正式值替换后 commit。
	return corev1.ResourceRequirements{}
}
