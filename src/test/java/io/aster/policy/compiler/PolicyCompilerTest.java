package io.aster.policy.compiler;

import io.aster.policy.entity.ArtifactType;
import io.aster.policy.entity.PolicyArtifact;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.repository.PolicySourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PolicyCompiler 单元测试：验证读取 core_json 的简化流程。
 */
@ExtendWith(MockitoExtension.class)
class PolicyCompilerTest {

    @Mock
    PolicySourceRepository policySourceRepository;

    private PolicyCompiler policyCompiler;

    @BeforeEach
    void setUp() {
        policyCompiler = new PolicyCompiler(policySourceRepository);
    }

    @Test
    void compileShouldReturnStoredCoreJsonWhenArtifactExists() {
        PolicyArtifact artifact = createArtifact(100L, "{\"module\":\"aster.finance.loan\"}");
        when(policySourceRepository.findCoreJsonArtifact(100L)).thenReturn(Optional.of(artifact));

        CompilationResult result = policyCompiler.compile(100L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).contains("aster.finance.loan");
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void compileShouldFailWhenArtifactAndVersionMissing() {
        long versionId = 200L;
        when(policySourceRepository.findCoreJsonArtifact(versionId)).thenReturn(Optional.empty());
        when(policySourceRepository.findVersionById(versionId)).thenReturn(Optional.empty());

        CompilationResult result = policyCompiler.compile(versionId);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCoreJson()).isNull();
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0)).contains("不存在");
    }

    @Test
    void compileShouldUseCoreJsonFieldWhenArtifactMissing() {
        long versionId = 300L;
        String storedCoreJson = "{\"module\":\"aster.finance.risk\",\"functions\":[]}";
        PolicyVersion version = createVersion(versionId, storedCoreJson);
        when(policySourceRepository.findCoreJsonArtifact(versionId)).thenReturn(Optional.empty());
        when(policySourceRepository.findVersionById(versionId)).thenReturn(Optional.of(version));

        CompilationResult result = policyCompiler.compile(versionId);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCoreJson()).isEqualTo(storedCoreJson);
        assertThat(result.getErrors()).isEmpty();
    }

    private PolicyVersion createVersion(long versionId, String coreJson) {
        PolicyVersion version = new PolicyVersion();
        version.id = versionId;
        version.policyId = "test.policy";
        version.moduleName = "test";
        version.functionName = "policy";
        version.coreJson = coreJson;
        version.locale = "zh-CN";
        return version;
    }

    @Test
    void compileWithAliasSetProducesSameResultAsCanonical() {
        // 方案 D：带用户别名的动态编译与规范拼写产出同一 Core IR JSON
        String aliasSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x multiplied by 3.";
        String canonSrc = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        CompilationResult aliased = policyCompiler.compile(
            aliasSrc, "en-US", "{\"TIMES\":[\"multiplied by\"]}");
        CompilationResult canon = policyCompiler.compile(canonSrc, "en-US", null);
        assertThat(aliased.isSuccess()).isTrue();
        assertThat(canon.isSuccess()).isTrue();
        assertThat(aliased.getCoreJson()).isEqualTo(canon.getCoreJson());
    }

    @Test
    void compileFailsClosedOnCorruptAliasSetJson() {
        // C2-a fail-closed：损坏的 alias_set → 编译失败（不静默回落无别名成功编译）
        String src = "Module M.\n\nRule p given x as Int, produce Int:\n  Return x times 3.";
        CompilationResult result = policyCompiler.compile(src, "en-US", "{not valid json");
        assertThat(result.isSuccess()).isFalse();
    }

    private PolicyArtifact createArtifact(long versionId, String json) {
        PolicyArtifact artifact = new PolicyArtifact();
        artifact.id = UUID.randomUUID();
        artifact.policyVersionId = versionId;
        artifact.artifactType = ArtifactType.CORE_JSON.name();
        artifact.content = json.getBytes(StandardCharsets.UTF_8);
        artifact.contentSha256 = "placeholder";
        artifact.compilerOpts = "{\"functionSignature\":\"evaluate\"}";
        artifact.createdAt = Instant.now();
        return artifact;
    }
}
