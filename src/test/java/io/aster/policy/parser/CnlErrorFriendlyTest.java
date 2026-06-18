package io.aster.policy.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CNL 解析错误友好化（ADR 0013 错误信息友好化）。
 *
 * 锁住：用户拿到的错误是人话、只报根因（首个错误），不再暴露 ANTLR 内部术语
 * （softComparator failed predicate / mismatched input / token 名 / &lt;INDENT&gt;）。
 */
class CnlErrorFriendlyTest {

    private static String parseErr(String src) {
        try {
            InProcessCnlParser.parse(src, "en-US");
            return "<no error>";
        } catch (InProcessCnlParser.CnlParseException e) {
            return e.getMessage();
        }
    }

    @Test
    void unknownComparator_givesActionableHint_notPredicateName() {
        // 此前：rule softComparator failed predicate: {...}? —— 对用户毫无意义
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is wobbly 5\n    Return 1.");
        assertThat(err)
            .doesNotContain("failed predicate")
            .doesNotContain("softComparator")
            .contains("无法识别")
            .contains("is less than"); // 给出可用的比较词建议
    }

    @Test
    void indentError_translated_notExtraneousDedent() {
        String err = parseErr("Module m.\nRule r given x, produce:\n      If x < 5\n  Return 1.");
        assertThat(err)
            .doesNotContain("<INDENT>")
            .doesNotContain("<DEDENT>")
            .doesNotContain("extraneous input");
    }

    @Test
    void noViableAlternative_stripsEmbeddedLayoutMarkers() {
        // ADR 0019 G2a 内联 if grammar 让畸形 if（缩进但缺 then/冒号）产生
        // `no viable alternative at input 'Ifx<5\n<DEDENT>'`——group(1) 是多 token
        // 串，内嵌 <DEDENT> 必须被剥掉，不能泄露给用户。
        String err = parseErr("Module m.\nRule r given x, produce:\n      If x < 5\n  Return 1.");
        assertThat(err)
            .doesNotContain("<INDENT>")
            .doesNotContain("<DEDENT>")
            .doesNotContain("DEDENT")
            .doesNotContain("INDENT");
    }

    @Test
    void onlyFirstRootCause_notCascadeOfDozens() {
        // 一个真实错误常引发几十条级联；用户只该看到根因。
        String err = parseErr("Module m.\nRule r given x, produce:\n  If x is wobbly 5\n    Return 1.");
        // 友好消息以"行 N 第 M 列"开头，且不应包含十几条用 ; 串起来的原始错误
        assertThat(err).startsWith("CNL 语法错误 — 行 ");
        long rawSeparators = err.chars().filter(c -> c == ';').count();
        assertThat(rawSeparators).isZero();
    }

    @Test
    void humanize_directUnit() {
        assertThat(CnlErrorListener.humanize("rule softComparator failed predicate: {x}?"))
            .contains("无法识别");
        assertThat(CnlErrorListener.humanize("mismatched input '.' expecting {':', ',', NEWLINE}"))
            .contains("意外的")
            .doesNotContain("NEWLINE");
        assertThat(CnlErrorListener.humanize("extraneous input '<INDENT>' expecting {<EOF>}"))
            .contains("缩进");
        assertThat(CnlErrorListener.humanize("no viable alternative at input 'Definehas'"))
            .contains("无法解析");
    }
}
