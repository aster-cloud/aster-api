package io.aster.policy.security.rbac;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法或类级别的角色要求注解
 * 标注后，请求必须携带满足最低角色等级的 X-User-Role header
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    Role value() default Role.MEMBER;
}
