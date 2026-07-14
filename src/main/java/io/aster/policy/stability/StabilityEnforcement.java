package io.aster.policy.stability;

import aster.core.ir.CoreModel;
import aster.core.lexicon.SemanticTokenKind;
import aster.core.lowering.CoreLowering;
import aster.core.stability.StabilityGate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.policy.compiler.CompilationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 稳定性门禁服务（ADR 0031 P0-C）——把 core {@link StabilityGate} 接进 aster-api 生产路径。
 *
 * <p>集中「strict 分环境默认」（ADR §3.4）+ Core IR 扫描 + W600 诊断映射 + strict 拒绝，
 * 让各 surface 只声明意图（哪个 surface），不散落 strict 判定：
 * <ul>
 *   <li>dev/playground/裸 compile → warn（不阻断探索）</li>
 *   <li>普通 tenant 保存 → warn；regulated tenant 保存 → strict</li>
 *   <li>激活/审批（activate/approve）→ strict（批准=托付生产，Experimental 不该无声进批准链）</li>
 * </ul>
 *
 * <p>★为何扫 Core IR 而非 typecheck：aster-api 生产路径不走 typecheck，gate 挂 typecheck
 * =服务端看不到=假门禁（ADR §3.2）。这里对 compile 产出的 coreModule / 已存 coreJson
 * （反序列化，堵 §3.7 cache 盲区）扫描。
 */
@ApplicationScoped
public class StabilityEnforcement {

    private static final Logger LOG = Logger.getLogger(StabilityEnforcement.class);

    /**
     * 宽松 mapper：读旧 coreJson 产物做扫描，容忍未知字段（产物结构可能随版本演进，
     * 扫描不应因新增字段而崩）。Jackson 多态由 CoreModel 的 @JsonTypeInfo/@JsonSubTypes 驱动。
     */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 受监管租户列表（保存路径 strict）。M2 用配置；后续接真实 tenant profile/plan。 */
    @ConfigProperty(name = "aster.stability.regulated-tenants", defaultValue = "")
    String regulatedTenantsCsv;

    /** enforcement surface —— 决定 strict 默认。 */
    public enum Surface {
        COMPILE_PLAYGROUND,
        SAVE,
        APPROVE,
        ACTIVATE
    }

    /** 扫描结果：诊断 + 是否被 strict 拒。 */
    public record Result(List<CompilationResult.Diagnostic> diagnostics, boolean blocked) {}

    /** 某 surface + tenant 是否 strict（ADR §3.4）。 */
    public boolean strictFor(Surface surface, String tenantId) {
        return switch (surface) {
            case APPROVE, ACTIVATE -> true;
            case SAVE -> isRegulatedTenant(tenantId);
            case COMPILE_PLAYGROUND -> false;
        };
    }

    private boolean isRegulatedTenant(String tenantId) {
        if (tenantId == null || regulatedTenantsCsv == null || regulatedTenantsCsv.isBlank()) {
            return false;
        }
        Set<String> regulated = Set.of(regulatedTenantsCsv.split("\\s*,\\s*"));
        return regulated.contains(tenantId);
    }

    /** 扫已 lower 的 coreModule（compile(source) 路径）。 */
    public Result scan(CoreModel.Module coreModule, boolean strict) {
        List<StabilityGate.Diagnostic> diags = StabilityGate.scan(
            coreModule, new StabilityGate.Options(strict, false));
        return toResult(diags, strict);
    }

    /**
     * strict 门禁因「无法判断稳定性」被拒时的哨兵结果（fail-closed）。
     * ★warn-mode 可放行（不阻断探索）；strict-mode 必须 fail-closed（Codex 审查 L3：
     * strict 把「无法判断」当「稳定」= 漏报 Experimental 进生产）。
     */
    public static final class UnscannableException extends RuntimeException {
        public UnscannableException(String message) {
            super(message);
        }
    }

    /**
     * 扫已存 coreJson 产物（★compile(versionId) cache / approve / activate 路径，堵 ADR §3.7 盲区）。
     * 反序列化 coreJson → CoreModel 再扫，不 re-lower（re-lower 会重解释旧版本，违背冻结产物语义）。
     *
     * <p>★fail 语义按 strict 分（Codex 审查 L3）：coreJson 空/损坏时——warn-mode 放行（返回空），
     * strict-mode 抛 {@link UnscannableException}（fail-closed，不把「无法判断」当「稳定」）。
     */
    public Result scanCoreJson(String coreJson, boolean strict) {
        if (coreJson == null || coreJson.isBlank()) {
            if (strict) {
                throw new UnscannableException("版本无 Core IR（coreJson 空），strict 门禁无法确认稳定性，拒绝放行");
            }
            return new Result(List.of(), false);
        }
        CoreModel.Module module;
        try {
            module = LENIENT_MAPPER.readValue(coreJson, CoreModel.Module.class);
        } catch (Exception e) {
            if (strict) {
                throw new UnscannableException("Core IR 无法反序列化（strict 门禁 fail-closed）: " + e.getMessage());
            }
            LOG.warnf("stability 扫描无法反序列化 coreJson（warn-mode 跳过 stability 检查）: %s", e.getMessage());
            return new Result(List.of(), false);
        }
        return scan(module, strict);
    }

    private Result toResult(List<StabilityGate.Diagnostic> diags, boolean strict) {
        List<CompilationResult.Diagnostic> mapped = new ArrayList<>(diags.size());
        for (StabilityGate.Diagnostic d : diags) {
            mapped.add(toCompilationDiagnostic(d));
        }
        boolean blocked = StabilityGate.shouldReject(diags, strict);
        return new Result(mapped, blocked);
    }

    /** W600 → CompilationResult.Diagnostic（severity 恒 warning，即使 strict 拒）。 */
    private static CompilationResult.Diagnostic toCompilationDiagnostic(StabilityGate.Diagnostic d) {
        int line = 1;
        int col = 1;
        int endLine = 1;
        int endCol = 1;
        if (d.origin() != null) {
            if (d.origin().start != null) {
                line = d.origin().start.line;
                col = d.origin().start.col;
            }
            if (d.origin().end != null) {
                endLine = d.origin().end.line;
                endCol = d.origin().end.col;
            } else {
                endLine = line;
                endCol = col;
            }
        }
        String message = "Experimental feature '" + d.featureId().id()
            + "' is not part of the stable surface (1.x 内语义可能变更，不进兼容承诺)";
        return new CompilationResult.Diagnostic(
            line, col, endLine, endCol, message,
            StabilityGate.STABILITY_EXPERIMENTAL_CODE, "warning",
            d.featureId().id(), d.nodeKind(), d.blocking());
    }

    /**
     * 解析版本的 Core IR 用于扫描：优先已存 coreJson（冻结产物）；空时从 content 源码
     * 现编译（★createVersion 不填 coreJson，approve/activate 时常为空——Codex 审查 L1 P0：
     * 只读 coreJson 会漏报）。strict 下无法得到可扫 IR 则抛 UnscannableException（fail-closed）。
     */
    public Result resolveAndScan(String coreJson, String content, String locale, String aliasSetJson, boolean strict) {
        if (coreJson != null && !coreJson.isBlank()) {
            return scanCoreJson(coreJson, strict);
        }
        // coreJson 空 → 从 content 现编译到 Core IR（★带版本冻结 aliasSet，Codex 复审：
        // 依赖别名的版本用无别名重解释会编译失败，误 fail-closed 拒正常 Stable 版本）。
        if (content == null || content.isBlank()) {
            if (strict) {
                throw new UnscannableException("版本无 coreJson 也无 content，strict 门禁无法确认稳定性，拒绝放行");
            }
            return new Result(List.of(), false);
        }
        CoreModel.Module module;
        try {
            module = compileToCore(content, locale, aliasSetJson);
        } catch (Exception e) {
            if (strict) {
                throw new UnscannableException("版本源码无法编译到 Core IR（strict fail-closed）: " + e.getMessage());
            }
            LOG.warnf("stability 扫描无法编译 content（warn-mode 跳过）: %s", e.getMessage());
            return new Result(List.of(), false);
        }
        return scan(module, strict);
    }

    /**
     * 源码 → CoreModel（带版本冻结 aliasSet，复用 InProcessCnlParser 的 alias-aware parse，
     * 与 PolicyCompiler.compile 同口径——否则依赖别名的版本会因无别名重解释而编译失败）。
     */
    private static CoreModel.Module compileToCore(String source, String locale, String aliasSetJson) {
        Map<SemanticTokenKind, List<String>> aliasSet = io.aster.policy.compiler.PolicyCompiler.parseAliasSetJson(aliasSetJson);
        io.aster.policy.parser.InProcessCnlParser.ParseResult parseResult =
            (aliasSet == null || aliasSet.isEmpty())
                ? io.aster.policy.parser.InProcessCnlParser.parse(source, locale)
                : io.aster.policy.parser.InProcessCnlParser.parseWithUserAliases(source, locale, null, aliasSet);
        return new CoreLowering().lowerModule(parseResult.module());
    }

    public void enforceVersion(Long versionId, String policyId, String tenantId,
                               String coreJson, String content, String locale, String aliasSetJson,
                               Surface surface, String actor) {
        boolean strict = strictFor(surface, tenantId);
        Result result;
        try {
            result = resolveAndScan(coreJson, content, locale, aliasSetJson, strict);
        } catch (UnscannableException e) {
            // ★strict fail-closed：无法确认稳定性 → 拒（不放行）。message 直接进 422（GlobalExceptionMapper
            // 保留 exception.getMessage()，Codex 审查 L4：entity 会被丢弃，故关键信息放 message）。
            LOG.warnf("stability gate fail-closed 拒绝 %s：versionId=%d policyId=%s actor=%s reason=%s",
                surface, versionId, policyId, actor, e.getMessage());
            throw new WebApplicationException(
                "stability_unscannable: " + e.getMessage(), 422);
        }
        if (result.blocked()) {
            List<String> featureIds = result.diagnostics().stream()
                .map(CompilationResult.Diagnostic::featureId)
                .filter(f -> f != null)
                .distinct()
                .toList();
            LOG.warnf("stability gate 拒绝 %s：versionId=%d policyId=%s tenant=%s actor=%s features=%s",
                surface, versionId, policyId, tenantId, actor, featureIds);
            // ★featureIds 放进 message——GlobalExceptionMapper 丢弃 entity 只保留 getMessage()（Codex L4）。
            throw new WebApplicationException(
                "stability_experimental_blocked: Experimental features "
                    + featureIds + " are not allowed for " + surface.name().toLowerCase(),
                422);
        }
    }
}
