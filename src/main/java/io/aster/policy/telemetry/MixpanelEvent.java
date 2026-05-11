package io.aster.policy.telemetry;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Mixpanel 单条事件载荷
 *
 * properties 在投递时会附加 token / distinct_id / time，调用方传业务属性即可。
 */
@RegisterForReflection
public record MixpanelEvent(String event, String distinctId, Map<String, Object> properties) {
}
