package io.aster.policy.api.validation;

import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.schema.SchemaValidator;
import io.aster.validation.semantic.SemanticValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Quarkus CDI 适配器，包装 aster-validation 模块。
 */
@ApplicationScoped
public class QuarkusValidationAdapter {

    private final SchemaValidator schemaValidator;
    private final SemanticValidator semanticValidator;

    @Inject
    public QuarkusValidationAdapter(ConstructorMetadataCache cache) {
        this.schemaValidator = new SchemaValidator(cache);
        this.semanticValidator = new SemanticValidator(cache);
    }

    public SchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    public SemanticValidator getSemanticValidator() {
        return semanticValidator;
    }
}
