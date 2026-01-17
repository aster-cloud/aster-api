package io.aster.policy.api.validation;

import io.aster.validation.metadata.ConstructorMetadataCache;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI 包装类，暴露 {@link ConstructorMetadataCache} 以便在 Quarkus 中注入使用。
 */
@ApplicationScoped
public class QuarkusConstructorMetadataCache extends ConstructorMetadataCache {
}
