package io.aster.policy.cache;

import io.aster.policy.compiler.CompilationMetadata;

import java.time.Instant;

/**
 * 编译后的策略缓存结构
 *
 * 缓存编译后的策略，避免重复编译。
 * 包含版本信息、源码哈希、Core JSON 和元数据。
 */
public class CompiledPolicy {
    private final String versionId;
    private final String sourceHash;
    private final String coreJson;
    private final CompilationMetadata metadata;
    private final Instant compiledAt;

    public CompiledPolicy(
        String versionId,
        String sourceHash,
        String coreJson,
        CompilationMetadata metadata
    ) {
        this.versionId = versionId;
        this.sourceHash = sourceHash;
        this.coreJson = coreJson;
        this.metadata = metadata;
        this.compiledAt = Instant.now();
    }

    public String getVersionId() {
        return versionId;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public String getCoreJson() {
        return coreJson;
    }

    public CompilationMetadata getMetadata() {
        return metadata;
    }

    public Instant getCompiledAt() {
        return compiledAt;
    }

    @Override
    public String toString() {
        return "CompiledPolicy{" +
            "versionId='" + versionId + '\'' +
            ", sourceHash='" + sourceHash + '\'' +
            ", compiledAt=" + compiledAt +
            '}';
    }
}
