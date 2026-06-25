package io.aster.policy.parser;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 架构约束（ADR 0022 §11.5，Codex 复核建议）：堵住"未经校验的 aliasSet 进入编译"的绕过。
 *
 * <p>{@link InProcessCnlParser#parseUnsafeWithAliases} 是 package-private 的 unsafe 原语
 * （不校验 aliasSet）。生产路径必须走 {@link InProcessCnlParser#parseWithUserAliases}（前置
 * {@link UserAliasValidator}）。package-private 不是安全边界——同包生产类仍可调用。本规则把
 * "禁止生产代码调 unsafe" 升级为可执行的架构约束：只有 parser 包内的受控入口/测试可调。
 */
class UserAliasArchTest {

    private static final JavaClasses PROD_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("io.aster");

    @Test
    void noProductionCodeOutsideParserMayCallUnsafeAliasParse() {
        // 仅 InProcessCnlParser 自身（受控入口 parseWithUserAliases → unsafe）可调 unsafe 原语。
        // 其它任何生产类调用 = 绕过 UserAliasValidator 强制校验 = 架构违规。
        ArchRule rule = noClasses()
                .that()
                .haveSimpleNameNotEndingWith("InProcessCnlParser")
                .should()
                .callMethod(InProcessCnlParser.class, "parseUnsafeWithAliases",
                        String.class, String.class,
                        aster.core.identifier.IdentifierIndex.class, java.util.Map.class)
                .allowEmptyShould(true)
                .because("生产路径必须走 parseWithUserAliases（强制 UserAliasValidator 校验），"
                        + "parseUnsafeWithAliases 仅供 parser 受控入口/测试使用（ADR 0022 §11.5）");
        rule.check(PROD_CLASSES);
    }
}
