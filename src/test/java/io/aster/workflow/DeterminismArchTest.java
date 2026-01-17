package io.aster.workflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 使用 ArchUnit 验证 workflow 代码在确定性上下文中不直接调用非确定性 API。
 */
class DeterminismArchTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES)
            .importPackages("io.aster.workflow");

    @Test
    void workflowCodeShouldNotCallInstantNow() {
        // Workflow 必须通过 DeterminismContext.clock() 获取时间以保持可重放
        // 排除基础设施类：事件存储、调度器、实体、查询构建器等
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("io.aster.workflow..")
                .and()
                .areNotAssignableTo(ReplayDeterministicClock.class)
                .and()
                .haveSimpleNameNotEndingWith("EventStore")
                .and()
                .haveSimpleNameNotEndingWith("WorkflowRuntime")
                .and()
                .haveSimpleNameNotEndingWith("SchedulerService")
                .and()
                .haveSimpleNameNotEndingWith("Entity")
                .and()
                .haveSimpleNameNotEndingWith("QueryProjectionBuilder")
                .should()
                .callMethod(Instant.class, "now")
                .allowEmptyShould(true)
                .because("workflow 代码必须经由 DeterminismContext.clock() 提供确定性时间");
        rule.check(CLASSES);
    }

    @Test
    void workflowCodeShouldNotCallUUIDRandomUUID() {
        // Workflow 必须通过 DeterminismContext.uuid() 生成 ID 避免随机漂移
        // 排除基础设施类：事件存储、调度器、实体、查询构建器等
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("io.aster.workflow..")
                .and()
                .areNotAssignableTo(ReplayDeterministicUuid.class)
                .and()
                .haveSimpleNameNotEndingWith("EventStore")
                .and()
                .haveSimpleNameNotEndingWith("WorkflowRuntime")
                .and()
                .haveSimpleNameNotEndingWith("SchedulerService")
                .and()
                .haveSimpleNameNotEndingWith("Entity")
                .and()
                .haveSimpleNameNotEndingWith("QueryProjectionBuilder")
                .should()
                .callMethod(UUID.class, "randomUUID")
                .allowEmptyShould(true)
                .because("workflow 代码必须经由 DeterminismContext.uuid() 生成确定性 UUID");
        rule.check(CLASSES);
    }

    @Test
    void workflowCodeShouldNotInstantiateRandom() {
        // Workflow Random 必须由 DeterminismContext.random() 统一管理
        // 排除基础设施类：事件存储、调度器、实体、查询构建器等
        DescribedPredicate<JavaConstructorCall> callsRandomConstructor =
                new DescribedPredicate<>("调用 java.util.Random 构造函数") {
                    @Override
                    public boolean test(JavaConstructorCall input) {
                        return input.getTarget()
                                .getOwner()
                                .isAssignableTo(Random.class);
                    }
                };

        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("io.aster.workflow..")
                .and()
                .areNotAssignableTo(ReplayDeterministicRandom.class)
                .and()
                .haveSimpleNameNotEndingWith("EventStore")
                .and()
                .haveSimpleNameNotEndingWith("WorkflowRuntime")
                .and()
                .haveSimpleNameNotEndingWith("SchedulerService")
                .and()
                .haveSimpleNameNotEndingWith("Entity")
                .and()
                .haveSimpleNameNotEndingWith("QueryProjectionBuilder")
                .should()
                .callConstructorWhere(callsRandomConstructor)
                .allowEmptyShould(true)
                .because("workflow 代码必须通过 DeterminismContext.random() 获取随机性");
        rule.check(CLASSES);
    }
}
