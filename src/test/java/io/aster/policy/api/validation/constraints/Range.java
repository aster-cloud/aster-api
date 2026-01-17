package io.aster.policy.api.validation.constraints;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 测试环境下的占位注解，用于避免策略生成类引用缺失导致的编译警告。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Range {
    int min();
    int max();
}
