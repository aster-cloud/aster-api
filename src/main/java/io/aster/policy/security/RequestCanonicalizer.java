package io.aster.policy.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 请求规范化工具
 *
 * 提供统一的请求规范化逻辑，用于签名验证和防重放哈希计算
 * 确保 HmacSignatureService 和 RequestSignatureFilter 使用相同的规范化规则
 */
public class RequestCanonicalizer {

    /**
     * 构建规范化请求字符串
     *
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param query     查询参数（可为 null）
     * @param timestamp 时间戳
     * @param nonce     Nonce 值
     * @param body      请求体
     * @return 规范化字符串 method|path|query|timestamp|nonce|bodyHash
     */
    public static String canonicalize(String method, String path, String query, String timestamp, String nonce, byte[] body) {
        String baseCanonical = buildBaseCanonicalString(method, path, query);
        String bodyHash = sha256Hex(body);
        return baseCanonical + "|" + timestamp + "|" + nonce + "|" + bodyHash;
    }

    /**
     * 构建基础规范化字符串（method|path|query）
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @param query  查询参数（可为 null）
     * @return 基础规范化字符串
     */
    private static String buildBaseCanonicalString(String method, String path, String query) {
        String queryPart = (query != null && !query.isEmpty()) ? query : "";
        return method + "|" + path + "|" + queryPart;
    }

    /**
     * 计算请求哈希（用于 Nonce 防重放）
     *
     * @param method HTTP 方法
     * @param path   请求路径
     * @param query  查询参数（可为 null）
     * @param body   请求体
     * @return SHA-256 哈希（十六进制）
     */
    public static String computeRequestHash(String method, String path, String query, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 复用基础规范化逻辑
            String baseCanonical = buildBaseCanonicalString(method, path, query);
            digest.update(baseCanonical.getBytes(StandardCharsets.UTF_8));
            digest.update("|".getBytes(StandardCharsets.UTF_8));
            digest.update(body);

            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute request hash", e);
        }
    }

    /**
     * 计算 SHA-256 哈希
     *
     * @param data 数据
     * @return 十六进制哈希字符串
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute SHA256", e);
        }
    }
}
