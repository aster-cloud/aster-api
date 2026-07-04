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

    /** 单次请求允许的最大 CNL 源码字符数（64 KiB）——认证路径（如 SourcePolicyRequest）。 */
    public static final int MAX_SOURCE_LENGTH = 65_536;

    /**
     * <b>匿名</b>端点（{@link SchemaRequest} → {@code /api/v1/policies/schema}）的更严
     * 源码上限（16 KiB，审计 #98 Medium 3）。
     *
     * <p>{@code /schema} 是 {@code @AnonymousAllowed}、豁免 API-key 边界，攻击者可无凭证
     * 提交 ≤上限的源码触发 canonicalize + ANTLR 解析（对长输入超线性：10KB≈1.5s、50KB>15s）。
     * 认证路径可信度更高、配额可追责，用 64 KiB；匿名路径把上限压到 16 KiB，把最坏单次
     * 解析耗时压进秒级，与 {@code PolicyEvaluationResource} 的每请求解析墙钟超时形成纵深防御。
     */
    public static final int MAX_ANON_SOURCE_LENGTH = 16_384;

    /**
     * JSON 策略定义（Core IR JSON）最大字符数（256 KiB）。比 CNL source 宽，
     * 因为序列化后的 Core IR 比源码冗长，但仍远高于任何真实策略。
     */
    public static final int MAX_JSON_POLICY_LENGTH = 262_144;

    /**
     * 单次评估请求 context 数组的最大元素数。评估是按位置传参，真实函数参数
     * 个数很小（个位数）；上限设 256 足以覆盖任何合法用例，同时防止超大数组
     * 在序列化/求值时放大成本。
     */
    public static final int MAX_CONTEXT_ELEMENTS = 256;

    /**
     * 模块名 / 函数名等标识符字段的最大长度。这类字段会进入 service / cache key
     * / 日志，限长防止超长"路径式"名字污染。256 远超任何真实命名。
     */
    public static final int MAX_IDENTIFIER_LENGTH = 256;

    /**
     * 自由文本字段（reason / notes 等会写入审计/日志）的最大长度（2 KiB）。
     */
    public static final int MAX_FREETEXT_LENGTH = 2_048;

    private CnlSourceLimits() {
    }
}
