package io.aster.policy.security.rbac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级匿名豁免标记。
 *
 * <p>标注后，{@link RoleEnforcementFilter} 跳过该方法的 RBAC 校验，即使其所在类
 * 带有类级 {@link RequireRole}。用于本应匿名的只读元数据端点（如 /schema、
 * /validate）—— 它们无副作用、不写数据、自带 DoS 兜底（@Size + 并发闸 + 限流），
 * 故无需角色，且必须对未认证调用方开放。
 *
 * <p>与 {@link RequireRole} 互斥语义：方法同时标两者时本注解优先（filter 先判它）。
 * 仅作标记用，由 filter 反射读取，不参与 CDI 拦截绑定。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AnonymousAllowed {
}
