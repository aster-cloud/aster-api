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
