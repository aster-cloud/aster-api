package io.aster.policy.module;

import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import io.aster.audit.PostgresAnalyticsTestProfile;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.DynamicCnlExecutor;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.test.PostgresTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@TestProfile(ModuleResolverIT.ModulesEnabledProfile.class)
@Transactional
class ModuleResolverIT {

    private static final String TENANT = "module-resolver-it";
    private static final String OTHER_TENANT = "module-resolver-other";

    @Inject
    ModuleResolver resolver;

    @Inject
    DynamicCnlExecutor executor;

    @BeforeEach
    void cleanDb() {
        PolicyVersion.delete("tenantId = ?1 or tenantId = ?2", TENANT, OTHER_TENANT);
    }

    @Test
    void missingImportVersionRaisesStructuredError() {
        ParsedRoot root = root("""
            Module app.Root.
            Use risk.Scoring as Score.

            Rule run given amount as Int, produce Int:
              Return amount.
            """);

        assertThatThrownBy(() -> resolver.resolveGraph(TENANT, root.core(), root.imports(), "en-US"))
            .isInstanceOf(ModuleResolutionException.class)
            .extracting(e -> ((ModuleResolutionException) e).code())
            .isEqualTo(ModuleResolutionException.Code.IMPORT_VERSION_REQUIRED);
    }

    @Test
    void missingModuleVersionRaisesNotFoundWithCandidateVersions() {
        persistLibrary(TENANT, "risk.Scoring", 1L, true, scoringSource(1));
        ParsedRoot root = root("""
            Module app.Root.
            Use risk.Scoring version 2 as Score.

            Rule run given amount as Int, produce Int:
              Return amount.
            """);

        assertThatThrownBy(() -> resolver.resolveGraph(TENANT, root.core(), root.imports(), "en-US"))
            .isInstanceOf(ModuleResolutionException.class)
            .satisfies(e -> {
                ModuleResolutionException err = (ModuleResolutionException) e;
                assertThat(err.code()).isEqualTo(ModuleResolutionException.Code.MODULE_VERSION_NOT_FOUND);
                assertThat(err.candidates()).containsExactly("1");
            });
    }

    @Test
    void sameTenantHiddenLibraryRaisesNotVisible() {
        persistLibrary(TENANT, "risk.Scoring", 1L, false, scoringSource(1));
        ParsedRoot root = root("""
            Module app.Root.
            Use risk.Scoring version 1 as Score.

            Rule run given amount as Int, produce Int:
              Return amount.
            """);

        assertThatThrownBy(() -> resolver.resolveGraph(TENANT, root.core(), root.imports(), "en-US"))
            .isInstanceOf(ModuleResolutionException.class)
            .extracting(e -> ((ModuleResolutionException) e).code())
            .isEqualTo(ModuleResolutionException.Code.MODULE_NOT_VISIBLE);
    }

    @Test
    void importCycleRaisesStructuredCycleError() {
        // 注：模块名/别名用多字母（risk.Alpha 非 risk.A）——Canonicalizer 会吞掉
        // 单字母大写标识符段（risk.A→risk.），是独立既存局限，不在跨模块特性范围。
        persistLibrary(TENANT, "risk.Alpha", 1L, true, """
            Module risk.Alpha.
            Use risk.Beta version 1 as Beta.

            Rule alpha given x as Int, produce Int:
              Return Beta.beta(x).
            """);
        persistLibrary(TENANT, "risk.Beta", 1L, true, """
            Module risk.Beta.
            Use risk.Alpha version 1 as Alpha.

            Rule beta given x as Int, produce Int:
              Return Alpha.alpha(x).
            """);
        ParsedRoot root = root("""
            Module app.Root.
            Use risk.Alpha version 1 as Alpha.

            Rule run given amount as Int, produce Int:
              Return Alpha.alpha(amount).
            """);

        assertThatThrownBy(() -> resolver.resolveGraph(TENANT, root.core(), root.imports(), "en-US"))
            .isInstanceOf(ModuleResolutionException.class)
            .extracting(e -> ((ModuleResolutionException) e).code())
            .isEqualTo(ModuleResolutionException.Code.MODULE_CYCLE);
    }

    @Test
    void normalImportProducesModuleGraphWithEdges() {
        persistLibrary(TENANT, "risk.Scoring", 1L, true, scoringSource(1));
        ParsedRoot root = root("""
            Module app.Root.
            Use risk.Scoring version 1 as Score.

            Rule run given amount as Int, produce Int:
              Return amount.
            """);

        var graph = resolver.resolveGraph(TENANT, root.core(), root.imports(), "en-US");

        assertThat(graph.modules()).hasSize(2);
        assertThat(graph.imports()).hasSize(1);
        assertThat(graph.imports().get(0).importAlias()).isEqualTo("Score");
    }

    @Test
    void truffleExecutionResolvesPinnedLibraryImportAndCallsDottedAlias() {
        persistLibrary(TENANT, "risk.Scoring", 1L, true, scoringSource(2));
        String source = """
            Module app.Root.
            Use risk.Scoring version 1 as Score.

            Rule run given amount as Int, produce Int:
              Return Score.f(amount).
            """;

        DynamicCnlExecutor.ExecutionResult result = executor.executeWithTenantContext(
            TENANT,
            source,
            Map.of("amount", 40),
            "run",
            "en-US",
            null,
            false
        );

        assertThat(result.result()).isEqualTo(42);
    }

    @Test
    void staticExecutorKeepsSingleModuleBehaviorWhenModuleFeatureIsNotUsed() {
        DynamicCnlExecutor.ExecutionResult result = DynamicCnlExecutor.executeWithContext(
            """
            Module app.Single.

            Rule run given amount as Int, produce Int:
              Return amount plus 1.
            """,
            Map.of("amount", 41),
            "run",
            "en-US"
        );

        assertThat(result.result()).isEqualTo(42);
    }

    private ParsedRoot root(String source) {
        try {
            InProcessCnlParser.ParseResult parseResult = InProcessCnlParser.parse(source, "en-US", null);
            CoreModel.Module core = new CoreLowering().lowerModule(parseResult.module());
            List<Decl.Import> imports = parseResult.module().decls().stream()
                .filter(Decl.Import.class::isInstance)
                .map(Decl.Import.class::cast)
                .toList();
            return new ParsedRoot(core, imports);
        } catch (InProcessCnlParser.CnlParseException e) {
            throw new AssertionError(e);
        }
    }

    void persistLibrary(String tenantId, String moduleName, Long version, boolean visible, String content) {
        PolicyVersion policyVersion = new PolicyVersion(
            "policy-" + tenantId + "-" + moduleName + "-" + version,
            moduleName,
            "f",
            content,
            "module-test",
            null
        );
        policyVersion.tenantId = tenantId;
        policyVersion.version = version;
        policyVersion.libraryVisible = visible;
        policyVersion.persist();
    }

    private String scoringSource(int increment) {
        return """
            Module risk.Scoring.

            Rule f given amount as Int, produce Int:
              Return amount plus %d.
            """.formatted(increment);
    }

    private record ParsedRoot(CoreModel.Module core, List<Decl.Import> imports) {}

    public static class ModulesEnabledProfile extends PostgresAnalyticsTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var overrides = new java.util.HashMap<>(super.getConfigOverrides());
            overrides.put("aster.modules.enabled", "true");
            return overrides;
        }
    }
}
