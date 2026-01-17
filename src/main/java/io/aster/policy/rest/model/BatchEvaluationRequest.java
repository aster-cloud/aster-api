package io.aster.policy.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * REST API请求：批量策略评估
 *
 * 用于一次性评估多个策略，提高吞吐量。
 */
public record BatchEvaluationRequest(
    @NotNull(message = "requests不能为null")
    @Size(min = 1, max = 100, message = "requests数量必须在1到100之间")
    @Valid
    @JsonProperty("requests")
    List<EvaluationRequest> requests
) {
}
