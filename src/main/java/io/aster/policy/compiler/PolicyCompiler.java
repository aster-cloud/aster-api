package io.aster.policy.compiler;

import aster.core.ast.Module;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.policy.repository.PolicySourceRepository;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * PolicyCompiler 负责将策略版本映射到 Core IR JSON。
 *
 * 编译优先级：
 * 1. 优先从 PolicyArtifact 读取预编译的 Core JSON
 * 2. 其次从 PolicyVersion.coreJson 读取
 * 3. 最后从 PolicyVersion.content 动态编译 CNL 源代码
 */
@ApplicationScoped
public class PolicyCompiler {

    private static final Logger LOG = Logger.getLogger(PolicyCompiler.class);
    private static final ObjectMapper MAPPER = JacksonMappers.PRETTY;

    private final PolicySourceRepository policySourceRepository;

    @Inject
    public PolicyCompiler(PolicySourceRepository policySourceRepository) {
        this.policySourceRepository = policySourceRepository;
    }

    /**
     * 从 CNL 源代码编译为 Core IR JSON。
     *
     * @param sourceCnl CNL 源代码
     * @param locale    语言环境（如 "zh-CN"、"en-US"）
     * @return 编译结果
     */
    public CompilationResult compile(String sourceCnl, String locale) {
        if (sourceCnl == null || sourceCnl.isBlank()) {
            return CompilationResult.failure("CNL 源代码不能为空");
        }

        try {
            // 1. 解析 CNL → AST
            LOG.debugf("解析 CNL 源代码... locale=%s", locale);
            InProcessCnlParser.ParseResult parseResult = InProcessCnlParser.parse(sourceCnl, locale);
            Module astModule = parseResult.module();

            // 2. 降级 AST → Core IR
            LOG.debugf("降级 AST → Core IR...");
            CoreLowering lowering = new CoreLowering();
            CoreModel.Module coreModule = lowering.lowerModule(astModule);

            // 3. 序列化 Core IR → JSON
            String coreJson = MAPPER.writeValueAsString(coreModule);
            LOG.debugf("Core JSON 长度: %d 字符", coreJson.length());

            // 4. 提取元数据
            CompilationMetadata metadata = new CompilationMetadata(
                null,
                String.format("module=%s, function=%s", parseResult.moduleName(), parseResult.firstFunctionName()),
                null
            );

            return CompilationResult.success(coreJson, metadata);

        } catch (InProcessCnlParser.CnlParseException e) {
            String message = "CNL 解析失败: " + e.getMessage();
            LOG.warn(message);
            return CompilationResult.failure(message);
        } catch (Exception e) {
            String message = "CNL 编译失败: " + e.getMessage();
            LOG.error(message, e);
            return CompilationResult.failure(message);
        }
    }

    /**
     * 根据策略版本 ID 获取 Core IR JSON。
     *
     * 查找顺序：
     * 1. PolicyArtifact 中的预编译 Core JSON
     * 2. PolicyVersion.coreJson 字段
     * 3. 从 PolicyVersion.content 动态编译
     *
     * <p>结果按 versionId 缓存：策略版本一经创建，其 {@code content} 不可变
     *（任何修改都新建一条带新 ID 的版本；回滚是切换 active 指针、不改旧版本内容），
     * 故 {@code versionId → 编译结果} 是恒定映射，缓存零陈旧风险。这消除了
     * /validate 主路径（多数版本无预编译产物 → 走动态编译）的重复编译开销。
     * 失败结果同样被缓存——content 恒定则失败也恒定，缓存正确且避免反复编译坏源码。
     *
     * @param policyVersionId 策略版本 ID
     * @return 编译结果
     */
    @CacheResult(cacheName = "policy-compile-by-version")
    public CompilationResult compile(@CacheKey Long policyVersionId) {
        if (policyVersionId == null) {
            return CompilationResult.failure("策略版本ID不能为空");
        }

        // 1. 尝试从 PolicyArtifact 读取
        if (policySourceRepository != null) {
            Optional<PolicyArtifact> artifact = policySourceRepository.findCoreJsonArtifact(policyVersionId);
            if (artifact.isPresent()) {
                CompilationResult result = buildSuccessResultFromArtifact(artifact.get());
                if (result.isSuccess()) {
                    LOG.debugf("从 PolicyArtifact 读取 Core JSON: versionId=%d", policyVersionId);
                    return result;
                }
            }
        }

        // 2. 尝试从 PolicyVersion.coreJson 读取
        Optional<PolicyVersion> optVersion = policySourceRepository.findVersionById(policyVersionId);
        if (optVersion.isEmpty()) {
            return CompilationResult.failure(String.format("策略版本不存在: versionId=%d", policyVersionId));
        }
        PolicyVersion version = optVersion.get();

        if (version.coreJson != null && !version.coreJson.isBlank()) {
            LOG.debugf("从 PolicyVersion.coreJson 读取: versionId=%d", policyVersionId);
            return CompilationResult.success(version.coreJson, CompilationMetadata.empty());
        }

        // 3. 从 CNL 源代码动态编译
        if (version.content == null || version.content.isBlank()) {
            return CompilationResult.failure(String.format("策略版本 %d 没有可用的 CNL 源代码", policyVersionId));
        }

        LOG.infof("动态编译 CNL 源代码: versionId=%d, locale=%s", policyVersionId, version.locale);
        return compile(version.content, version.locale);
    }

    private CompilationResult buildSuccessResultFromArtifact(PolicyArtifact artifact) {
        if (artifact.content == null || artifact.content.length == 0) {
            return CompilationResult.failure("Core JSON 内容为空");
        }

        String coreJson = new String(artifact.content, StandardCharsets.UTF_8);
        CompilationMetadata metadata = extractMetadata(artifact);
        return CompilationResult.success(coreJson, metadata);
    }

    private CompilationMetadata extractMetadata(PolicyArtifact artifact) {
        if (artifact.compilerOpts == null || artifact.compilerOpts.isBlank()) {
            return CompilationMetadata.empty();
        }
        return new CompilationMetadata(null, artifact.compilerOpts, null);
    }
}
