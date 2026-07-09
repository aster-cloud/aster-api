package io.aster.security.net;

/**
 * SSRF 出站目标校验失败。
 *
 * <p>异常消息只描述规则，不包含 apiKey、请求体或 provider 原始响应。调用方可以把它映射成
 * BYOK endpoint 配置错误，绝不回退平台凭证。
 */
public class SsrfViolation extends IllegalArgumentException {

    public SsrfViolation(String message) {
        super(message);
    }

    public SsrfViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
