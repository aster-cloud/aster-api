package io.aster.security.apikey;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static io.aster.security.apikey.InternalCallerFilter.Classification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R27-Minor-3：InternalCallerFilter 单元测试。
 *
 * <p>测试 path 分类 + 旁路决策的纯函数 {@link InternalCallerFilter#classify}，
 * 以及 HMAC canonical 形式（method + path + ts）。不依赖 Quarkus 上下文。
 *
 * <p>覆盖的关键回归点：
 * <ul>
 *   <li>R23-Critical-2: AI 端点未签名时必须 REQUIRE_HMAC</li>
 *   <li>R25-Critical-1: matrix-param 不应绕过分类</li>
 *   <li>R25-Major-3: aster.security.ai.sse.public 只放过 SSE 三段，/complete 仍要 HMAC</li>
 *   <li>aster.security.ai.public 全量旁路（粗粒度）</li>
 * </ul>
 */
class InternalCallerFilterTest {

    // ============================================================
    // Classification: 路径不在管辖范围 → NOT_PROTECTED
    // ============================================================

    @Test
    void unrelatedPathsAreNotProtected() {
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/lexicons", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/policies/evaluate", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/q/health", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/", false, false, false, false));
    }

    @Test
    void aiPathWithoutSubResourceNotProtected() {
        // /api/v1/ai 本身（没有 sub-resource）不属于 LLM 端点，不在管辖
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai", false, false, false, false));
        assertEquals(Classification.NOT_PROTECTED,
            InternalCallerFilter.classify("/api/v1/ai/", false, false, false, false));
    }

    // ============================================================
    // Classification: evaluate-source 默认需要 HMAC
    // ============================================================

    @Test
    void evaluateSourceRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                false, false, false, false));
    }

    @Test
    void evaluateSourcePublicFlagBypasses() {
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, /*evaluateSourceTrial*/ false, false, false));
    }

    @Test
    void evaluateSourceTrialFlagBypassesAsTrial() {
        // .trial 单独打开时返回 BYPASS_TRIAL —— 与 BYPASS_OK 区分，确保下游 filter
        // (TrialEndpointGuard) 才是真正的限流闸门，避免 operator 把 .trial=true
        // 当成 .public=true 用。
        assertEquals(Classification.BYPASS_TRIAL,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ false, /*evaluateSourceTrial*/ true, false, false));
    }

    @Test
    void evaluateSourceTrialDoesNotAffectAiEndpoints() {
        // .trial 是 evaluate-source 专属的：AI 路径仍要 HMAC
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, /*evaluateSourceTrial*/ true, false, false));
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, true, false, false));
    }

    @Test
    void evaluateSourcePublicTakesPrecedenceOverTrial() {
        // 同时设两个开关时，.public 是更粗粒度的旁路，优先生效（operator 应当
        // 显式选择只设一个）。
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/policies/evaluate-source",
                /*evaluateSourcePublic*/ true, /*evaluateSourceTrial*/ true, false, false));
    }

    // ============================================================
    // Classification: AI 端点 — 默认 / ai.public / ai.sse.public 三个层级
    // ============================================================

    @Test
    void aiCompleteRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete", false, false, false, false));
    }

    @Test
    void aiGenerateRequiresHmacByDefault() {
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/generate", false, false, false, false));
    }

    @Test
    void aiPublicBypassesEverything() {
        // 粗粒度全量旁路 —— /complete 也放过
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, /*aiPublic*/ true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, false, true, false));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/translate", // 未来端点
                false, false, true, false));
    }

    @Test
    void aiSsePublicBypassesOnlySseEndpoints() {
        // R25-Major-3: ai.sse.public 只放 generate/suggest（explain 端点已移除）
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/generate",
                false, false, false, /*aiSsePublic*/ true));
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/suggest",
                false, false, false, true));
        // explain 端点已删除：即便 sse.public=true 也不在白名单 → 仍要求 HMAC。
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/explain",
                false, false, false, true));
    }

    @Test
    void aiSsePublicDoesNotAffectComplete() {
        // R25-Major-3 关键不变式：/complete 永远不被 sse.public 影响
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, false, /*aiSsePublic*/ true));
    }

    @Test
    void aiSsePublicDoesNotAffectUnknownFutureAiEndpoint() {
        // 未来加一个 /api/v1/ai/translate —— 不在 SSE 白名单 set 里，
        // 设了 ai.sse.public 也不放过，必须显式扩展 AI_SSE_PATHS
        assertEquals(Classification.REQUIRE_HMAC,
            InternalCallerFilter.classify("/api/v1/ai/translate",
                false, false, false, true));
    }

    @Test
    void aiPublicTakesPrecedenceOverSsePublic() {
        // ai.public 是更粗粒度的旁路；同时设 sse.public 时它优先（reviewer 确认这是
        // 当前实现的优先级顺序，operator 应当显式选择）
        assertEquals(Classification.BYPASS_OK,
            InternalCallerFilter.classify("/api/v1/ai/complete",
                false, false, /*aiPublic*/ true, /*aiSsePublic*/ true));
    }

    // ============================================================
    // Classification: matrix-param 不应改变分类
    // 这里 classify 收到的是 normalized path（PathNormalizer.normalize 已剥离 ;...）
    // 但保险起见也防御 NOT_PROTECTED 的 matrix-param case
    // ============================================================

    @Test
    void classifyAssumesAlreadyNormalizedPath() {
        // classify 不做 normalization —— 调用方（filter()）必须先用 PathNormalizer。
        // 如果有人把 raw path 传进来，matrix params 会让分类变化。
        // 这个测试记录"约定 = 必须先 normalize"。
        Classification rawPath = InternalCallerFilter.classify(
            "/api/v1/ai/complete;jsessionid=abc", false, false, false, false);
        // 因为 isAi 用 startsWith，所以 /api/v1/ai/complete;x 仍被识别 isAi=true。
        // 但更重要的：filter() 入口已 normalize → 实际调用 classify 时不会有 ;。
        // 此处仅断言"原始 raw 路径形态下行为是 REQUIRE_HMAC"，即未 normalize 不会
        // 引入 BYPASS_OK / BYPASS_TRIAL 这样的危险错分类。
        assertNotEquals(Classification.BYPASS_OK, rawPath);
        assertNotEquals(Classification.BYPASS_TRIAL, rawPath);
    }

    // ============================================================
    // HMAC sign(): canonical 形式 method + "\n" + path + "\n" + ts
    // ============================================================

    @Test
    void signProducesDeterministicHex() {
        // 同样输入产生同样输出
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        assertEquals(a, b);
        // 64 hex chars (HmacSHA256 → 32 bytes)
        assertEquals(64, a.length());
        // 仅 hex 字符
        org.junit.jupiter.api.Assertions.assertTrue(a.matches("[0-9a-f]+"));
    }

    @Test
    void signWithDifferentPathProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/generate\n1700000000");
        assertNotEquals(a, b, "path 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentTimestampProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000001");
        assertNotEquals(a, b, "ts 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentKeyProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k1", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k2", "POST\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "key 不同必须产生不同签名");
    }

    @Test
    void signWithDifferentMethodProducesDifferentHex() {
        String a = InternalCallerFilter.sign("k", "POST\n/api/v1/ai/complete\n1700000000");
        String b = InternalCallerFilter.sign("k", "GET\n/api/v1/ai/complete\n1700000000");
        assertNotEquals(a, b, "method 不同必须产生不同签名");
    }

    // ============================================================
    // R22 后处理：HMAC startup 校验必须由 StartupEvent 触发
    //
    // 历史背景：曾经用 @PostConstruct，但 @ApplicationScoped 在 Quarkus 是 CDI
    // 懒初始化 —— @PostConstruct 直到首个请求到达才会跑，于是配置错误的生产
    // 部署可能在收到流量之前一直吞掉警告（podman /ccg:test 实测确认）。
    // 改成观察 StartupEvent 后，Quarkus 启动期间一定会触发该方法。
    //
    // 这里用反射钉住契约：任何把它改回 @PostConstruct 或去掉 @Observes 的
    // 重构都会让这个测试失败，避免静默回归。
    // ============================================================

    @Test
    void hmacValidatorIsWiredAsStartupEventObserver() {
        Method observer = null;
        for (Method m : InternalCallerFilter.class.getDeclaredMethods()) {
            if (!"validateHmacKeyConfig".equals(m.getName())) continue;
            observer = m;
            break;
        }
        assertNotNull(observer,
            "validateHmacKeyConfig 方法必须存在 —— 它是 HMAC 配置缺失的启动期可见性");

        Parameter[] params = observer.getParameters();
        assertEquals(1, params.length,
            "validateHmacKeyConfig 必须仅接收一个 @Observes StartupEvent 参数");

        Parameter p = params[0];
        assertEquals(StartupEvent.class, p.getType(),
            "参数类型必须是 io.quarkus.runtime.StartupEvent —— 这是 Quarkus 启动期触发器，"
                + "不要换成 @PostConstruct（@ApplicationScoped 懒初始化 = 启动期不触发）");

        boolean hasObserves = false;
        for (var annotation : p.getAnnotations()) {
            if (annotation.annotationType().equals(Observes.class)) {
                hasObserves = true;
                break;
            }
        }
        assertTrue(hasObserves,
            "StartupEvent 参数必须标 @Observes —— 否则 CDI 不会把该方法注册为观察者");
    }

    @Test
    void hmacValidatorIsNotAnnotatedWithPostConstruct() {
        // 配套防御：直接确认没有 @PostConstruct 注解残留。
        // 即便参数签名对了，如果同时还挂着 @PostConstruct，行为会变得难以预测。
        Method observer;
        try {
            observer = InternalCallerFilter.class
                .getDeclaredMethod("validateHmacKeyConfig", StartupEvent.class);
        } catch (NoSuchMethodException e) {
            fail("validateHmacKeyConfig(StartupEvent) 必须存在");
            return;
        }
        try {
            Class<?> postConstruct = Class.forName("jakarta.annotation.PostConstruct");
            @SuppressWarnings({"unchecked", "rawtypes"})
            boolean hasPostConstruct = observer.isAnnotationPresent(
                (Class) postConstruct);
            assertTrue(!hasPostConstruct,
                "validateHmacKeyConfig 不应再带 @PostConstruct —— "
                    + "@ApplicationScoped + @PostConstruct 在 Quarkus 是懒触发，"
                    + "已迁移到 StartupEvent 以保证启动期一定执行");
        } catch (ClassNotFoundException ignored) {
            // PostConstruct 不在 classpath 上，更不可能用到它 —— 通过
        }
    }
}
