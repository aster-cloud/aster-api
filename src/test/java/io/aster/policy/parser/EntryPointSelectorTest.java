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
}
