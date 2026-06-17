package io.aster.policy.api.validation;

import io.aster.validation.metadata.ConstructorMetadataCache;
import io.aster.validation.schema.SchemaValidator;
import io.aster.validation.schema.SchemaValidator.MissingFieldPolicy;
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
        // aster-lang-validation #6 changed the no-arg SchemaValidator default to STRICT
        // (throw on missing fields). PolicyTypeConverter intentionally fills defaults for
        // missing fields (construct-from-map semantics), so opt into LENIENT to preserve
        // that contract.
        this.schemaValidator = new SchemaValidator(cache, MissingFieldPolicy.LENIENT);
        this.semanticValidator = new SemanticValidator(cache);
    }

    public SchemaValidator getSchemaValidator() {
        return schemaValidator;
    }

    public SemanticValidator getSemanticValidator() {
        return semanticValidator;
    }
}
