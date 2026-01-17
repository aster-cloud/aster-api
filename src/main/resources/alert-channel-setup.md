> 更新时间：2025-11-13 20:11 NZST  
> 执行者：Codex  
> 适用范围：Phase 3.8 AnomalyMetrics 告警通道配置

# 告警通道配置指南

本指南帮助团队在 Alertmanager 中配置 Slack 与 PagerDuty 告警通道，并给出本地或 Staging 环境的联调测试方法。

## 1. Slack Webhook 配置

1. 进入 Slack 管理后台，创建 **Incoming Webhook**，选择 Phase 3.8 运维频道（建议新建 `#phase38-anomaly-alerts`），复制生成的 Webhook URL。  
2. 在 Alertmanager 的 `receivers` 内新增 Slack 接收器，例如：

```yaml
receivers:
  - name: anomaly-slack
    slack_configs:
      - api_url: https://hooks.slack.com/services/XXX/YYY/ZZZ
        channel: "#phase38-anomaly-alerts"
        title: "[{{ .CommonLabels.severity | toUpper }}] {{ .CommonLabels.alertname }}"
        text: |
          *概要*: {{ .Annotations.summary }}
          *详情*: {{ .Annotations.description }}
          *实例*: {{ range .Alerts }}{{ .Labels.instance }} {{ end }}
```

3. 在 `route` 中为 AnomalyMetrics 告警添加匹配条件，确保 `component=anomaly-action` 的事件进入上述接收器。  
4. 重新加载 Alertmanager（`kill -HUP <pid>` 或调用 `/-/reload`），确认未出现配置错误。

## 2. PagerDuty Integration Key

1. 打开 PagerDuty → **Services → Service Directory**，为 Phase 3.8 告警创建或选择现有服务。  
2. 在 **Integrations** 选项卡中新建 **Events API v2** 集成，记录 Integration Key。  
3. 在 Alertmanager 中配置 PagerDuty 接收器，并与 Slack 形成级联通知：

```yaml
receivers:
  - name: anomaly-pagerduty
    pagerduty_configs:
      - routing_key: <PAGERDUTY_INTEGRATION_KEY>
        severity: "{{ default \"critical\" .CommonLabels.severity }}"
        description: "{{ .CommonAnnotations.summary }} - {{ .CommonAnnotations.description }}"
```

4. 在 `route` 的 `routes` 列表新增一条 critical 级别子路由，使 `severity=critical`（例如 RollbackSuccessRateLow）同时流向 PagerDuty 与 Slack。

## 3. 告警通知测试

1. **配置校验**：使用 `amtool check-config alertmanager.yml` 确认语法与引用有效。  
2. **Prometheus 规则热加载**：将 `prometheus-alerts.yml` 加入主 Prometheus 配置后，执行 `POST /-/reload` 并在 Web UI 的 *Alerts* 页面确认新规则处于 `INACTIVE` 状态。  
3. **模拟事件**：在测试环境运行以下命令模拟失败率升高：

```bash
watch -n 1 curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=rate(anomaly_rollback_failed_total[5m]) / rate(anomaly_rollback_attempts_total[5m])'
```

结合 Prometheus 的 *Graph* 或 `promtool test rules`（构造自定义 `series` 数据）触发告警，确认 Slack/PagerDuty 均能收到通知。  
4. **链路回归**：关闭模拟流量后，等待告警恢复（Alertmanager 会发送 `resolved` 事件），确保 Slack/PagerDuty 双方均有恢复通知并包含 runbook 链接。  
5. **记录结果**：在 `docs/testing.md` 与对应任务 `verification.md` 中记录测试窗口、告警 ID、通知截图/链接，供 Phase 4 审计。
