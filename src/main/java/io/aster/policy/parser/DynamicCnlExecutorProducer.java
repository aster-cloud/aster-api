package io.aster.policy.parser;

import io.aster.policy.module.ModuleResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * aster-api CDI producer：把 core 的 POJO DynamicCnlExecutor 以 @ApplicationScoped
 * 暴露给 CDI（满足 @Inject DynamicCnlExecutor 的 TruffleRuntimeHealthCheck /
 * ReplayExecutorAdapter）。注入 DB-backed ModuleResolver（作 ModuleGraphResolver）
 * + 读 aster.modules.enabled。★scope 显式 @ApplicationScoped（保原 bean 生命周期）。
 */
@ApplicationScoped
public class DynamicCnlExecutorProducer {

    @Inject
    ModuleResolver moduleResolver;   // implements ModuleGraphResolver（Task 1）

    @ConfigProperty(name = "aster.modules.enabled", defaultValue = "false")
    boolean modulesEnabled;

    @Produces
    @ApplicationScoped
    DynamicCnlExecutor dynamicCnlExecutor() {
        return new DynamicCnlExecutor(moduleResolver, modulesEnabled);
    }
}
