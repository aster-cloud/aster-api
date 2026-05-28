package io.aster.policy.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 策略序列化器 - 支持 JSON 和 Aster CNL 格式互转
 * <p>
 * 作为 @ApplicationScoped bean 可在 Quarkus 环境中注入使用，
 * 也可独立实例化用于非 CDI 环境。
 * <p>
 * 性能优化：
 * - 使用 Caffeine 缓存 CNL 编译结果（基于内容 SHA-256 哈希）
 * - 缓存容量：1000 条目，过期时间：60 分钟
 * - 缓存命中时耗时 < 1ms（相比 ProcessBuilder 的 500-2000ms）
 */
@ApplicationScoped
public class PolicySerializer {
    private static final Logger LOG = LoggerFactory.getLogger(PolicySerializer.class);
    private static final int CACHE_MAX_SIZE = 1000;
    private static final int CACHE_EXPIRE_MINUTES = 60;
    private static final int PROCESS_TIMEOUT_SECONDS = 5;

    private final ObjectMapper objectMapper;
    private final Cache<String, Object> compilationCache;

    /**
     * 默认构造函数 - 用于 CDI 注入
     */
    public PolicySerializer() {
        this(createDefaultObjectMapper(), createDefaultCache());
    }

    /**
     * 可配置构造函数 - 用于测试或自定义场景
     */
    PolicySerializer(ObjectMapper objectMapper, Cache<String, Object> cache) {
        this.objectMapper = objectMapper;
        this.compilationCache = cache;
        LOG.info("PolicySerializer initialized with cache (maxSize={}, expireMinutes={})",
                CACHE_MAX_SIZE, CACHE_EXPIRE_MINUTES);
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private static Cache<String, Object> createDefaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
                .recordStats() // 启用统计，便于监控
                .build();
    }

    /**
     * 将策略对象序列化为 JSON 字符串
     */
    public String toJson(Object policy) {
        try {
            return objectMapper.writeValueAsString(policy);
        } catch (JsonProcessingException e) {
            throw new PolicySerializationException("Failed to serialize policy to JSON", e);
        }
    }

    /**
     * 将策略对象转换为 Aster CNL 格式
     * 通过 ProcessBuilder 调用 aster-convert CLI
     *
     * @param policy 策略对象或 JSON 字符串
     */
    public String toCNL(Object policy) {
        try {
            // 如果输入已经是 JSON 字符串，直接使用；否则序列化
            String json = (policy instanceof String) ? (String) policy : toJson(policy);

            // 根据 CLI 路径类型构建命令
            String cliPath = resolveCliPath();
            ProcessBuilder pb;
            if (cliPath.endsWith(".js")) {
                // .js 文件需要通过 node 执行
                pb = new ProcessBuilder("node", cliPath, "json-to-cnl", "-");
            } else {
                // 可执行文件或 npm shim 直接执行
                pb = new ProcessBuilder(cliPath, "json-to-cnl", "-");
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try {
                // 写入 JSON 到 stdin
                try (OutputStream os = process.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                // 读取 CNL 输出 (P0-R19: try-with-resources 防止 FD 泄漏)
                String cnl;
                try (InputStream is = process.getInputStream()) {
                    cnl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                // 等待进程完成（带超时）
                boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new PolicySerializationException("CLI execution timed out after " + PROCESS_TIMEOUT_SECONDS + "s", null);
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new PolicySerializationException("CLI failed with exit code " + exitCode + ": " + cnl, null);
                }

                return cnl;
            } finally {
                // P0-R19: 兜底, 确保进程在所有 exception 路径都被回收, 避免 zombie
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (IOException e) {
            throw new PolicySerializationException("Failed to execute aster-convert CLI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolicySerializationException("CLI execution was interrupted", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为策略对象
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new PolicySerializationException("Failed to deserialize policy from JSON", e);
        }
    }

    /**
     * 从 Aster CNL 格式转换为策略对象
     * 通过 ProcessBuilder 调用 aster-convert CLI
     * <p>
     * 性能优化：使用 Caffeine 缓存基于 CNL 内容哈希的编译结果
     */
    public <T> T fromCNL(String cnl, Class<T> clazz) {
        // 计算 CNL 内容哈希作为缓存键
        String cacheKey = computeCacheKey(cnl, clazz);

        // 尝试从缓存获取
        @SuppressWarnings("unchecked")
        T cached = (T) compilationCache.getIfPresent(cacheKey);
        if (cached != null) {
            LOG.debug("Cache hit for CNL (hash={})", cacheKey.substring(0, 8));
            return cached;
        }

        // 缓存未命中，执行编译
        LOG.debug("Cache miss for CNL (hash={}), executing compilation", cacheKey.substring(0, 8));
        T result = compileCNLInternal(cnl, clazz);

        // 存入缓存
        compilationCache.put(cacheKey, result);

        return result;
    }

    /**
     * 内部 CNL 编译实现（不使用缓存）
     */
    private <T> T compileCNLInternal(String cnl, Class<T> clazz) {
        try {
            // 根据 CLI 路径类型构建命令
            String cliPath = resolveCliPath();
            ProcessBuilder pb;
            if (cliPath.endsWith(".js")) {
                // .js 文件需要通过 node 执行
                pb = new ProcessBuilder("node", cliPath, "compile-to-json", "-");
            } else {
                // 可执行文件或 npm shim 直接执行
                pb = new ProcessBuilder(cliPath, "compile-to-json", "-");
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try {
                // 写入 CNL 到 stdin
                try (OutputStream os = process.getOutputStream()) {
                    os.write(cnl.getBytes(StandardCharsets.UTF_8));
                }

                // 读取 JSON 输出 (P0-R19: try-with-resources 防止 FD 泄漏)
                String json;
                try (InputStream is = process.getInputStream()) {
                    json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                // 等待进程完成（带超时）
                boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new PolicySerializationException("CLI execution timed out after " + PROCESS_TIMEOUT_SECONDS + "s", null);
                }

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new PolicySerializationException("CLI failed with exit code " + exitCode + ": " + json, null);
                }

                // 反序列化 JSON 为对象
                return fromJson(json, clazz);
            } finally {
                // P0-R19: 兜底, 确保进程在所有 exception 路径都被回收, 避免 zombie
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (IOException e) {
            throw new PolicySerializationException("Failed to execute aster-convert CLI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PolicySerializationException("CLI execution was interrupted", e);
        }
    }

    /**
     * 计算缓存键：CNL 内容的 SHA-256 哈希 + 目标类名
     */
    private String computeCacheKey(String cnl, Class<?> clazz) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cnl.getBytes(StandardCharsets.UTF_8));
            String hexHash = HexFormat.of().formatHex(hash);
            return hexHash + ":" + clazz.getName();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 应该始终可用
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 获取缓存统计信息（用于监控）
     */
    public CacheStats getCacheStats() {
        var stats = compilationCache.stats();
        return new CacheStats(
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate(),
                compilationCache.estimatedSize()
        );
    }

    /**
     * 清空编译缓存（用于测试或故障排查）
     */
    public void clearCache() {
        compilationCache.invalidateAll();
        LOG.info("Compilation cache cleared");
    }

    /**
     * 解析 Aster CLI 路径
     * 优先级：
     * 1. 环境变量 ASTER_CLI_PATH
     * 2. 项目根目录 node_modules/.bin/aster-convert
     * 3. 项目根目录 dist/src/cli/policy-converter.js
     * 4. 全局 aster-convert
     */
    private String resolveCliPath() {
        // 1. 环境变量优先
        String envPath = System.getenv("ASTER_CLI_PATH");
        if (envPath != null && !envPath.isEmpty()) {
            return envPath;
        }

        // 2. 查找项目根目录（package.json 所在位置）
        Path projectRoot = findProjectRoot(Paths.get("").toAbsolutePath());

        if (projectRoot != null) {
            // 尝试 node_modules/.bin/aster-convert
            Path localCli = projectRoot.resolve("node_modules/.bin/aster-convert");
            if (Files.exists(localCli)) {
                return localCli.toAbsolutePath().toString();
            }

            // 尝试直接使用编译后的 CLI
            Path distCli = projectRoot.resolve("dist/src/cli/policy-converter.js");
            if (Files.exists(distCli)) {
                return distCli.toAbsolutePath().toString();
            }
        }

        // 3. 回退到全局命令（生产环境）
        return "aster-convert";
    }

    /**
     * 向上搜索项目根目录（package.json 所在目录）
     */
    private Path findProjectRoot(Path startDir) {
        Path current = startDir;
        while (current != null) {
            Path packageJson = current.resolve("package.json");
            if (Files.exists(packageJson)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 缓存统计信息
     */
    public record CacheStats(
            long hitCount,
            long missCount,
            double hitRate,
            long size
    ) {}

    /**
     * 策略序列化异常
     */
    public static class PolicySerializationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PolicySerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
