package io.aster.test.telemetry;

import io.aster.policy.telemetry.NsmTelemetry;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * NsmTelemetry mock 断言工具
 *
 * 用法：
 * <pre>
 *   @InjectMock NsmTelemetry nsmTelemetry;
 *
 *   // 触发被测代码后
 *   NsmTelemetryAssertions.assertEvent(nsmTelemetry, NsmEvents.DRAFT_PUBLISHED, "user-1")
 *       .withProp("rule_id", "p1")
 *       .withProp("source_kind", "ai_draft_edited")
 *       .emittedByServer();
 *
 *   NsmTelemetryAssertions.assertNoEvent(nsmTelemetry, NsmEvents.DRAFT_PUBLISHED);
 * </pre>
 *
 * 设计意图：
 *   - 把 ArgumentCaptor 的样板代码 + emitted_by 的统一约束抽出，避免每个 IT 自己重写
 *   - 强制 emitted_by 字段的存在与值对齐 NSM 03-telemetry-spec 中的契约
 */
public final class NsmTelemetryAssertions {

    private NsmTelemetryAssertions() {
    }

    /** 断言 telemetry 收到了至少一次指定事件，并返回流式构建器做属性级断言 */
    public static EventVerification assertEvent(NsmTelemetry mock, String event, String distinctId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(mock, atLeastOnce()).track(eq(distinctId), eq(event), captor.capture());
        Map<String, Object> props = captor.getValue();
        assertNotNull(props, "props 必须非空");
        return new EventVerification(props, event, distinctId);
    }

    /** 断言 telemetry 没有触发指定事件 */
    public static void assertNoEvent(NsmTelemetry mock, String event) {
        verify(mock, never()).track(anyString(), eq(event), any());
    }

    /** 单条事件的属性断言流式构建器 */
    public static final class EventVerification {
        private final Map<String, Object> props;
        private final String event;
        private final String distinctId;

        private EventVerification(Map<String, Object> props, String event, String distinctId) {
            this.props = props;
            this.event = event;
            this.distinctId = distinctId;
        }

        public EventVerification withProp(String key, Object expected) {
            assertEquals(expected, props.get(key),
                String.format("event=%s distinctId=%s prop[%s] mismatch",
                    event, distinctId, key));
            return this;
        }

        /** 强制断言 emitted_by="server"（后端权威事件的硬性约束） */
        public EventVerification emittedByServer() {
            return withProp("emitted_by", "server");
        }

        /** 自定义断言：当属性需要含某子串而非精确匹配时使用 */
        public EventVerification withPropMatching(String key, java.util.function.Predicate<Object> predicate) {
            Object actual = props.get(key);
            if (!predicate.test(actual)) {
                throw new AssertionError(String.format(
                    "event=%s prop[%s]=%s 不满足谓词", event, key, actual));
            }
            return this;
        }

        public Map<String, Object> props() {
            return props;
        }
    }
}
