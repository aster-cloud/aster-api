package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntryPointSelectorTest {

    @Test
    void single_rule_without_function_selects_the_rule() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of("main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        EntryPointSelector.Selected selected = (EntryPointSelector.Selected) selection;
        assertThat(selected.function()).isEqualTo("main");
        assertThat(selected.reason()).isEqualTo("unspecified");
    }

    @Test
    void multiple_rules_without_function_is_ambiguous() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of("helper", "main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Ambiguous.class);
        assertThat(((EntryPointSelector.Ambiguous) selection).candidates())
            .containsExactly("helper", "main");
    }

    @Test
    void explicit_evaluate_with_legacy_flag_uses_auto_for_single_rule() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("evaluate", List.of("main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        EntryPointSelector.Selected selected = (EntryPointSelector.Selected) selection;
        assertThat(selected.function()).isEqualTo("main");
        assertThat(selected.reason()).isEqualTo("legacy-evaluate-sentinel");
    }

    @Test
    void explicit_evaluate_with_legacy_flag_uses_auto_for_multiple_rules() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("evaluate", List.of("helper", "main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Ambiguous.class);
        assertThat(((EntryPointSelector.Ambiguous) selection).candidates())
            .containsExactly("helper", "main");
    }

    @Test
    void explicit_evaluate_without_legacy_flag_selects_real_evaluate_rule() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("evaluate", List.of("helper", "evaluate"), false);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        assertThat(((EntryPointSelector.Selected) selection).function()).isEqualTo("evaluate");
        assertThat(((EntryPointSelector.Selected) selection).reason()).isEqualTo("explicit");
    }

    @Test
    void explicit_evaluate_without_legacy_flag_reports_not_found_when_absent() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("evaluate", List.of("helper", "main"), false);

        assertThat(selection).isInstanceOf(EntryPointSelector.NotFound.class);
        EntryPointSelector.NotFound notFound = (EntryPointSelector.NotFound) selection;
        assertThat(notFound.requested()).isEqualTo("evaluate");
        assertThat(notFound.candidates()).containsExactly("helper", "main");
    }

    @Test
    void no_rules_reports_no_rule() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of(), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.NoRule.class);
    }

    @Test
    void explicit_existing_function_is_selected() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("main", List.of("helper", "main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        assertThat(((EntryPointSelector.Selected) selection).function()).isEqualTo("main");
        assertThat(((EntryPointSelector.Selected) selection).reason()).isEqualTo("explicit");
    }

    @Test
    void helper_and_main_without_function_does_not_select_first_rule() {
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("  ", List.of("helper", "main"), true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Ambiguous.class);
        assertThat(((EntryPointSelector.Ambiguous) selection).candidates())
            .containsExactly("helper", "main");
    }

    // ===== @entry 注解优先级（ADR 0015 阶段2）=====

    @Test
    void entry_annotation_selected_over_ambiguity() {
        // 多 Rule 本应 Ambiguous，但 @entry 标记了 main → 选 main
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of("helper", "main"), "main", true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        assertThat(((EntryPointSelector.Selected) selection).function()).isEqualTo("main");
        assertThat(((EntryPointSelector.Selected) selection).reason()).isEqualTo("entry-annotation");
    }

    @Test
    void explicit_function_overrides_entry_annotation() {
        // 显式 functionName 优先于 @entry
        EntryPointSelector.Selection selection =
            EntryPointSelector.select("helper", List.of("helper", "main"), "main", true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        assertThat(((EntryPointSelector.Selected) selection).function()).isEqualTo("helper");
        assertThat(((EntryPointSelector.Selected) selection).reason()).isEqualTo("explicit");
    }

    @Test
    void entry_annotation_not_in_rules_falls_through_to_ambiguity() {
        // @entry 指向不存在的 Rule（理论上 core 已校验）→ 回退到常规启发式
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of("helper", "main"), "ghost", true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Ambiguous.class);
    }

    @Test
    void single_rule_without_entry_still_selected() {
        // 无 @entry，单 Rule → 仍选它（@entry 为 null 不影响既有行为）
        EntryPointSelector.Selection selection =
            EntryPointSelector.select(null, List.of("only"), null, true);

        assertThat(selection).isInstanceOf(EntryPointSelector.Selected.class);
        assertThat(((EntryPointSelector.Selected) selection).function()).isEqualTo("only");
    }
}
