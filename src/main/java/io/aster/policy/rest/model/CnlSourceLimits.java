package io.aster.policy.rest.model;

/**
 * CNL 源码输入的共享上限常量。
 *
 * <p>所有接受原始 CNL {@code source} 的请求 DTO（{@link SchemaRequest}、
 * {@link SourcePolicyRequest} 等）统一引用此上限，确保校验层在进入解析/
 * 规范化之前就拒绝病态大输入。
 *
 * <p>背景：CNL 词法/规范化在长输入上呈超线性耗时（实测 10KB≈1.5s，
 * 50KB 已 >15s），缺少上限时构成算法复杂度 DoS——少量大 body 即可耗尽
 * worker 线程池。真实策略普遍 < 几 KB，64KB 对合法用例绰绰有余。
 */
public final class CnlSourceLimits {

    /** 单次请求允许的最大 CNL 源码字符数（64 KiB）。 */
    public static final int MAX_SOURCE_LENGTH = 65_536;

    private CnlSourceLimits() {
    }
}
