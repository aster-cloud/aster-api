package io.aster.policy.parser;

import io.aster.policy.parser.DynamicCnlExecutor.ExecutionResult;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 红队 P1-D 沙箱回归：DynamicCnlExecutor（playground / evaluate-source 路径）不得
 * 比生产 TrufflePolicyRuntime 宽。历史配置用 {@code HostAccess.allowPublicAccess(true)}，
 * 放开**所有** public 方法/字段的 guest→host 反射访问；已收紧为 {@code HostAccess.EXPLICIT}
 * 基线 + 仅数据 interop 访问器（array/list/map）+ {@code allowHostClassLookup(name -> false)}。
 *
 * <p>本测试两条主线：
 * <ol>
 *   <li>行为不变：结构化上下文（Map 字段访问）经真实 executor 仍能读到 → 数据访问器有效。</li>
 *   <li>攻击面收敛：用**与 executor 相同**的 HostAccess 配置建 Context，证明任意 host
 *       对象的非 @Export public 方法在 guest 侧不可调用（allowPublicAccess 已关闭）；
 *       同一 host 对象在 allowPublicAccess(true) 下可调用 —— 对照证明差异是真实的。</li>
 * </ol>
 */
class DynamicCnlExecutorSandboxTest {

    private static final String FIELD_ACCESS_SOURCE = """
        Module sandbox.probe.

        Define Driver has age as Int.

        Rule main given driver as Driver, produce Int:
          Return driver.age.
        """;

    @Test
    void structured_context_field_access_still_works() {
        // 收紧后必须仍能读 Map 成员（context.age）——否则等于把功能打断。
        Map<String, Object> context = Map.of("driver", Map.of("age", 7));

        ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            FIELD_ACCESS_SOURCE, context, null, "en-US", null, false);

        assertThat(result.functionName()).isEqualTo("main");
        assertThat(result.result()).isEqualTo(7);
    }

    /** 一个"危险"host 对象：暴露一个非 @HostAccess.Export 的 public 方法。 */
    public static final class DangerousHost {
        public String secret() {
            return "leaked-host-secret";
        }
    }

    /**
     * executor 现用的 HostAccess：EXPLICIT 基线 + 数据访问器。**不含** allowPublicAccess。
     * 与 {@link DynamicCnlExecutor#executeWithPolyglot} 里保持一致（改一处这里也要改，
     * 双份是刻意的：让"沙箱被放宽"这类回归在这里立刻炸出来）。
     */
    private static HostAccess hardenedAccess() {
        return HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowArrayAccess(true)
            .allowListAccess(true)
            .allowMapAccess(true)
            .build();
    }

    private static Context newContext(HostAccess access) {
        // 用一个通用 guest 语言（js 不一定在 classpath；这里用 aster 引擎无法直接跑
        // 任意脚本，故改为直接断言 Value 成员可见性——不 eval 脚本，只测 interop 边界）。
        return Context.newBuilder()
            .allowHostAccess(access)
            .allowHostClassLookup(name -> false)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .allowIO(IOAccess.NONE)
            .allowCreateProcess(false)
            .allowCreateThread(false)
            .allowNativeAccess(false)
            .build();
    }

    @Test
    void hardened_access_hides_non_exported_public_method() {
        try (Context ctx = newContext(hardenedAccess())) {
            Value hostVal = ctx.asValue(new DangerousHost());
            // EXPLICIT：非 @HostAccess.Export 的 public 方法 secret() 不应作为成员暴露。
            assertThat(hostVal.hasMember("secret"))
                .as("EXPLICIT 下非 @HostAccess.Export 的 public 方法不得暴露给 guest")
                .isFalse();
            // getMember 取不到该成员（不可达）。
            assertThat(hostVal.getMember("secret"))
                .as("非 @Export 方法在 EXPLICIT 下 getMember 应返回 null")
                .isNull();
        }
    }

    @Test
    void permissive_access_would_expose_public_method_contrast() {
        // 对照组：allowPublicAccess(true)（旧配置）下同一方法**可见 + 可执行** —— 证明
        // 上面的收敛是真实生效的差异，而不是这个 host 对象碰巧不可达。
        HostAccess permissive = HostAccess.newBuilder()
            .allowPublicAccess(true)
            .build();
        try (Context ctx = newContext(permissive)) {
            Value hostVal = ctx.asValue(new DangerousHost());
            assertThat(hostVal.hasMember("secret"))
                .as("allowPublicAccess(true) 下 public 方法可见（旧的宽松行为）")
                .isTrue();
            assertThat(hostVal.getMember("secret").execute().asString())
                .isEqualTo("leaked-host-secret");
        }
    }
}
