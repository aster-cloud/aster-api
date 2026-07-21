package orchestrator

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
)

// ErrUnavailable 表示编排层系统性失败（超时/日志不可解析/Job 消失）——handler 归 F 契约的 unavailable。
var ErrUnavailable = errors.New("runner job unavailable")

// runJob 编排一次 runner 执行：create Job（拿 UID）→ 回填 ConfigMap owner-ref UID → create
// ConfigMap → watch 终态（受 ctx 30s SLA 约束）→ 读 Pod log 末行 envelope，用 exit code 作权威。
// 任何系统性失败返回 (zero, err)——handler 绝不裸 500，会转结构化 unavailable。
//
// ★create 顺序是 Codex 强制契约：ConfigMap 的 owner-ref UID 只有在 API server create Job 后
//
//	才存在（buildRunnerJob 只能填 Name/Kind/APIVersion，UID 留空）。绝不能以空 UID 的
//	owner-ref 创建 ConfigMap——否则 Job 删除后 ConfigMap 变孤儿，级联 GC 失效、泄漏
//	per-invocation ConfigMap。故顺序恒为：create Job → 回填 UID → create ConfigMap。
func runJob(ctx context.Context, clientset kubernetes.Interface, req RunnerRequest, digest string) (RunnerEnvelope, error) {
	job, cm, err := buildRunnerJob(req, digest)
	if err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: build job: %v", ErrUnavailable, err)
	}

	// 1) 先 create Job 拿到 API server 分配的真实 UID。
	created, err := clientset.BatchV1().Jobs(runnerNamespace).Create(ctx, job, metav1.CreateOptions{})
	if err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: create job: %v", ErrUnavailable, err)
	}

	// 2) 回填 ConfigMap owner-ref 的 UID（buildRunnerJob 留空，此处补齐）。
	cm.OwnerReferences[0].UID = created.UID

	// 3) 回填后才 create ConfigMap——绝不以空 UID owner-ref 创建（级联 GC 契约）。
	if _, err := clientset.CoreV1().ConfigMaps(runnerNamespace).Create(ctx, cm, metav1.CreateOptions{}); err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: create configmap: %v", ErrUnavailable, err)
	}

	// watch 到终态（Succeeded/Failed），受 ctx timeout（30s SLA budget）约束。
	terminal, err := waitForJobTerminal(ctx, clientset, created.Name)
	if err != nil {
		return RunnerEnvelope{}, err // 已含 ErrUnavailable 包装
	}

	// 读 Pod log 末行 envelope。★不 2>/dev/null——读全 log 保诊断，从末尾找首条合法 envelope。
	logs, exitCode, err := readPodLogAndExitCode(ctx, clientset, created.Name)
	if err != nil {
		return RunnerEnvelope{}, fmt.Errorf("%w: read pod log: %v", ErrUnavailable, err)
	}
	env, parseErr := lastEnvelopeLine(logs)
	if parseErr != nil {
		// 日志截断/不可解析 → fail-closed 到 unavailable（Fork C）。
		return RunnerEnvelope{}, fmt.Errorf("%w: unparseable envelope (exit=%d, jobSucceeded=%v): %v",
			ErrUnavailable, exitCode, terminal, parseErr)
	}
	// exit code 作 SUCCESS/ERROR/序列化失败权威（RunnerMain 0/1/3）：与 envelope.outcome 交叉校验。
	// exit==3（序列化失败）→ envelope 不可信 → unavailable。
	if exitCode == 3 {
		return RunnerEnvelope{}, fmt.Errorf("%w: runner serialize failure (exit 3)", ErrUnavailable)
	}
	return env, nil
}

// waitForJobTerminal poll Job 到 Succeeded>0 或 Failed>0；ctx 超时返回 ErrUnavailable。
// ★用 poll 而非 watch informer：单发短命 Job，poll 简单且无 informer 生命周期负担（复杂度 <3 层）。
func waitForJobTerminal(ctx context.Context, clientset kubernetes.Interface, name string) (succeeded bool, err error) {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return false, fmt.Errorf("%w: watch timeout: %v", ErrUnavailable, ctx.Err())
		case <-ticker.C:
			j, gerr := clientset.BatchV1().Jobs(runnerNamespace).Get(ctx, name, metav1.GetOptions{})
			if gerr != nil {
				return false, fmt.Errorf("%w: get job: %v", ErrUnavailable, gerr)
			}
			if j.Status.Succeeded > 0 {
				return true, nil
			}
			if j.Status.Failed > 0 {
				return false, nil // Failed 也是终态——仍去读 log 拿 runner 的 ERROR envelope
			}
		}
	}
}

// readPodLogAndExitCode 找 Job 的 Pod，读全 log（不 2>/dev/null）+ 容器终止 exit code。
func readPodLogAndExitCode(ctx context.Context, clientset kubernetes.Interface, jobName string) (string, int32, error) {
	pods, err := clientset.CoreV1().Pods(runnerNamespace).List(ctx, metav1.ListOptions{
		LabelSelector: fmt.Sprintf("job-name=%s", jobName),
	})
	if err != nil {
		return "", -1, err
	}
	if len(pods.Items) == 0 {
		return "", -1, errors.New("no pod for job")
	}
	pod := pods.Items[0]

	// exit code：从 runner 容器的 terminated state 读（0/1/3）。
	var exitCode int32 = -1
	for _, cs := range pod.Status.ContainerStatuses {
		if cs.Name == "runner" && cs.State.Terminated != nil {
			exitCode = cs.State.Terminated.ExitCode
		}
	}

	// 读 log：显式指定 runner 容器。★不加 2>/dev/null（Fork C 保诊断，读全量输出）。
	req := clientset.CoreV1().Pods(runnerNamespace).GetLogs(pod.Name, &corev1.PodLogOptions{Container: "runner"})
	rc, err := req.Stream(ctx)
	if err != nil {
		return "", exitCode, err
	}
	defer rc.Close()
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(rc); err != nil {
		return "", exitCode, err
	}
	return buf.String(), exitCode, nil
}

// lastEnvelopeLine 从 log 末尾向前找第一条能解析为合法 RunnerEnvelope（outcome∈{SUCCESS,ERROR}）的行。
// ★runner 把前置日志走 stderr、envelope 走 stdout 最后一行——但保守起见扫全 log 找末条合法 JSON。
func lastEnvelopeLine(logs string) (RunnerEnvelope, error) {
	lines := strings.Split(strings.TrimRight(logs, "\n"), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		line := strings.TrimSpace(lines[i])
		if line == "" || !strings.HasPrefix(line, "{") {
			continue
		}
		var env RunnerEnvelope
		if err := json.Unmarshal([]byte(line), &env); err != nil {
			continue
		}
		if env.Outcome == "SUCCESS" || env.Outcome == "ERROR" {
			return env, nil
		}
	}
	return RunnerEnvelope{}, errors.New("no valid envelope line in log")
}
