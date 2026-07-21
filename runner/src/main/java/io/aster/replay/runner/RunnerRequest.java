package io.aster.replay.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * runner 请求（spec schema ②）。字段与 launcher 透传的执行元组对应；
 * aliasSet 是 raw（未建 index），index/aliasSet 在 :replay 内建以保 byte-parity 单源。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerRequest(
        String tenantId,
        String source,
        Object input,
        String locale,
        String functionName,
        Map<String, List<String>> aliasSet
) {}
