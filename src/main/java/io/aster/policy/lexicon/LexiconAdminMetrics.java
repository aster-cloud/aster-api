package io.aster.policy.lexicon;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * R16-Architectural-5 + R17-Major-1 + R18-Major-1: micrometer 指标 + 结构化审计日志，
 * 用于热插语言包 admin 端点。
 *
 * <p>指标命名遵循 micrometer 惯例（snake_case + _total 后缀）：
 * <ul>
 *   <li>{@code aster_lexicon_upload_total{outcome=...}} — 上传终态计数</li>
 *   <li>{@code aster_lexicon_delete_total{outcome=...}} — 删除终态计数</li>
 *   <li>{@code aster_lexicon_availability_total{op=enable|disable, outcome=...}} —
 *       enable/disable 终态计数（R17-Major-1）</li>
 * </ul>
 *
 * <p>审计日志：每次终态打 1 行 INFO，logger 名 {@code io.aster.audit.lexicon}，
 * 结构化 key=value 便于 Loki / ELK 查询。字段：
 * <ul>
 *   <li>{@code op} — {@code upload} / {@code delete} / {@code enable} / {@code disable}</li>
 *   <li>{@code outcome} — 终态标识（{@code null} → {@code unknown}）</li>
 *   <li>{@code actor} — 当前固定 {@code hmac}（HMAC 鉴权只验密钥本身，没有 caller
 *       identity 概念；如未来引入 key-id 或 mTLS 客户端证书再扩展）</li>
 *   <li>{@code fileName} — upload/delete 路径：sanitized .jar 文件名</li>
 *   <li>{@code localeId} — enable/disable 路径：BCP-47 locale ID（R18-Major-1：与
 *       {@code fileName} 互斥，避免下游消费者把 locale 当文件名）</li>
 *   <li>{@code sha256} — upload 路径：jar 内容哈希</li>
 *   <li>{@code recoveryId} — backup-restore 失败路径：opaque UUID，用于 grep server log
 *       找到实际 target/backup 路径</li>
 *   <li>{@code registered} — upload ok/unchanged 路径：本次操作后注册的 locale 集合，
 *       逗号分隔无空格（R17-Minor-2）</li>
 * </ul>
 */
@ApplicationScoped
public class LexiconAdminMetrics {

    private static final Logger LOG = Logger.getLogger("io.aster.audit.lexicon");

    @Inject
    MeterRegistry registry;

    /**
     * 上传终态计数。outcome 来源于 {@link HotPlugLexiconLoader.LoadResult#outcome()}
     * 或 admin 层增加的 outcome（如 {@code cross_replica_busy}）。
     */
    public void recordUpload(String outcome, String fileName, String sha256,
                             String recoveryId, Set<String> registered) {
        Counter.builder("aster_lexicon_upload_total")
            .description("Total lexicon admin upload terminal outcomes")
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry)
            .increment();
        auditLog("upload", outcome, fileName, /*localeId*/ null, sha256, recoveryId, registered);
    }

    /**
     * 删除终态计数。outcome 取值：{@code removed} / {@code not_found} / {@code cross_replica_busy}
     * / {@code io_error}。
     */
    public void recordDelete(String outcome, String fileName) {
        Counter.builder("aster_lexicon_delete_total")
            .description("Total lexicon admin delete terminal outcomes")
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry)
            .increment();
        auditLog("delete", outcome, fileName, /*localeId*/ null, null, null, null);
    }

    /**
     * R17-Major-1 + R18-Major-1：enable/disable 端点的可观测。
     *
     * <p>op 取值：{@code enable} / {@code disable}。outcome 取值：
     * <ul>
     *   <li>{@code enabled} / {@code disabled} —— markAvailable/markUnavailable 真切换</li>
     *   <li>{@code unchanged} —— locale 已是目标状态</li>
     * </ul>
     *
     * <p>计数器：{@code aster_lexicon_availability_total{op=..., outcome=...}}。
     * 审计日志：{@code op=enable outcome=enabled actor=hmac localeId=zh-CN} ——
     * R18-Major-1：locale ID 走显式 {@code localeId=} 字段，**不**塞进 {@code fileName=}，
     * 避免下游消费者把 {@code fileName=zh-CN} 当 .jar 文件名。
     */
    public void recordAvailabilityChange(String op, String outcome, String localeId) {
        Counter.builder("aster_lexicon_availability_total")
            .description("Total lexicon admin enable/disable terminal outcomes")
            .tag("op", op == null ? "unknown" : op)
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry)
            .increment();
        auditLog(op, outcome, /*fileName*/ null, localeId, null, null, null);
    }

    /**
     * R17-Minor-2 + R18-Major-1：稳定的 audit 序列化。
     *
     * <p>所有字段值不含空格 —— Loki/ELK 按空格分 token 时能稳定切。{@code registered}
     * 用逗号串而非 Set.toString，避免 {@code [en-US, de-DE]} 内部空格破坏切分。
     * null outcome 归一化为 "unknown"，与 metric tag 行为对齐。
     *
     * <p>{@code fileName} 与 {@code localeId} 是互斥字段：upload/delete 用 fileName，
     * enable/disable 用 localeId。下游消费者据此区分 ".jar 文件" 还是 "BCP-47 locale"。
     */
    private static void auditLog(String op, String outcome,
                                 String fileName, String localeId,
                                 String sha256, String recoveryId, Set<String> registered) {
        // 结构化单行示例：
        //   op=upload outcome=ok fileName=zh-CN.jar sha256=... [recoveryId=...] [registered=zh-CN,de-DE]
        //   op=enable outcome=enabled localeId=zh-CN
        StringBuilder sb = new StringBuilder(160);
        sb.append("audit.lexicon op=").append(op);
        sb.append(" outcome=").append(outcome == null ? "unknown" : outcome);
        sb.append(" actor=hmac");
        if (fileName != null) sb.append(" fileName=").append(fileName);
        if (localeId != null) sb.append(" localeId=").append(localeId);
        if (sha256 != null) sb.append(" sha256=").append(sha256);
        if (recoveryId != null) sb.append(" recoveryId=").append(recoveryId);
        if (registered != null && !registered.isEmpty()) {
            sb.append(" registered=").append(String.join(",", registered));
        }
        LOG.info(sb.toString());
    }
}
