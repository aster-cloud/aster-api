package io.aster.policy.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.text.MessageFormat;
import io.aster.common.JacksonMappers;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时文案写入校验（ADR 0021 + Codex 审查升级——不止 JSON 合法）。
 *
 * <p>纯静态工具，校验 admin 提交的 messages 树：
 * <ol>
 *   <li><b>JSON 对象合法</b>——可解析为 object（非数组/标量）。</li>
 *   <li><b>键集 parity</b>——叶子键路径集合须与 classpath 基线**完全一致**（不增不减），
 *       防误删/新增导致前端 next-intl 取 key 落空或残留。</li>
 *   <li><b>占位符 parity</b>——每个叶子文案的 ICU 占位符集合（{@code {name}} 等）须与基线
 *       同名文案一致，防 {@code {amount}} 被误删/改名导致运行时插值异常。</li>
 * </ol>
 *
 * <p>每个叶子用 ICU4J {@code MessageFormat} compile 校验（坏 ICU 语法 → 拒），挡住"坏文案
 * 让 SSR render 抛错"的 DoS。值类型须为 string（叶子）或 object（中间节点），拒数组/数字；
 * key 拒危险名（原型污染）/含点（路径碰撞）/空白。ICU4J 与 next-intl 的 ICU 语义大体兼容，
 * corpus 自校验测试（{@code MessagesValidatorTest.bundledMessagesSelfValidate}）兜住误拒。
 */
public final class MessagesValidator {

    private static final ObjectMapper MAPPER = JacksonMappers.DEFAULT;

    /** 简单占位符匹配：{@code {name}} / {@code {count, plural, ...}} 取首段名。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\s*([A-Za-z0-9_]+)");

    private MessagesValidator() {}

    /** 校验结果：ok=true 或带第一个错误原因。 */
    public record Result(boolean ok, String error) {
        static final Result OK = new Result(true, null);
        static Result fail(String error) { return new Result(false, error); }
    }

    /**
     * 校验 {@code candidateJson} 相对 {@code baselineJson}（classpath 基线）的合法性 + parity。
     *
     * @param candidateJson admin 提交的完整 messages 树
     * @param baselineJson  classpath 基线（locale 未发版时调用方应先拒，不该传 null）
     */
    public static Result validate(String candidateJson, String baselineJson) {
        JsonNode candidate;
        try {
            candidate = MAPPER.readTree(candidateJson);
        } catch (Exception e) {
            return Result.fail("invalid_json: " + e.getMessage());
        }
        if (candidate == null || !candidate.isObject()) {
            return Result.fail("not_object: messages 根必须是 JSON 对象");
        }
        JsonNode baseline;
        try {
            baseline = MAPPER.readTree(baselineJson);
        } catch (Exception e) {
            return Result.fail("baseline_unreadable: " + e.getMessage());
        }

        // 叶子路径 → 占位符集合。
        TreeMap<String, Set<String>> candLeaves = new TreeMap<>();
        TreeMap<String, Set<String>> baseLeaves = new TreeMap<>();
        try {
            collectLeaves("", candidate, candLeaves);
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        }
        try {
            collectLeaves("", baseline, baseLeaves);
        } catch (IllegalStateException e) {
            return Result.fail("baseline_malformed: " + e.getMessage());
        }

        // 键集 parity：candidate 与 baseline 叶子路径集合必须相等。
        if (!candLeaves.keySet().equals(baseLeaves.keySet())) {
            Set<String> missing = new LinkedHashSet<>(baseLeaves.keySet());
            missing.removeAll(candLeaves.keySet());
            Set<String> extra = new LinkedHashSet<>(candLeaves.keySet());
            extra.removeAll(baseLeaves.keySet());
            return Result.fail("key_parity_mismatch: missing=" + truncate(missing) + " extra=" + truncate(extra));
        }

        // 占位符 parity：每个叶子的占位符集合一致。
        for (var e : candLeaves.entrySet()) {
            Set<String> candPh = e.getValue();
            Set<String> basePh = baseLeaves.get(e.getKey());
            if (!candPh.equals(basePh)) {
                return Result.fail("placeholder_mismatch at '" + e.getKey()
                    + "': expected=" + basePh + " got=" + candPh);
            }
        }
        return Result.OK;
    }

    /** 危险/保留 key（防原型污染或前端对象消费踩坑，Codex 审查）。 */
    private static final Set<String> DANGEROUS_KEYS = Set.of("__proto__", "constructor", "prototype");

    /**
     * 递归收集叶子（string）路径 → 占位符集合。同时：①拒绝危险/含点/空白 key（防路径碰撞 +
     * 原型污染）；②对每个叶子做 ICU compile 校验（坏 ICU 语法 → 拒，防 SSR render DoS）。
     * 非 string/object 叶子 或 校验失败抛 IllegalStateException。
     */
    private static void collectLeaves(String prefix, JsonNode node, TreeMap<String, Set<String>> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                // key 安全：拒危险名、含点（路径碰撞）、空白。
                if (DANGEROUS_KEYS.contains(key)) {
                    throw new IllegalStateException("dangerous_key at '" + prefix + "': " + key);
                }
                if (key.indexOf('.') >= 0 || key.isBlank()) {
                    throw new IllegalStateException("invalid_key at '" + prefix + "': key 不得含点或空白: '" + key + "'");
                }
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                collectLeaves(path, entry.getValue(), out);
            });
        } else if (node.isTextual()) {
            String text = node.asText();
            // ICU compile 校验：坏 ICU 语法（括号不平衡、坏 plural/select）→ 拒。
            try {
                new MessageFormat(text, Locale.ROOT);
            } catch (Exception e) {
                throw new IllegalStateException("invalid_icu at '" + prefix + "': " + e.getMessage());
            }
            out.put(prefix, placeholders(text));
        } else {
            // 拒绝数组/数字/布尔等——messages 叶子只能是文案字符串。
            throw new IllegalStateException("invalid_leaf_type at '" + prefix + "': 仅允许字符串叶子");
        }
    }

    /** 提取一段文案里的占位符名集合。 */
    private static Set<String> placeholders(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static String truncate(Set<String> s) {
        if (s.size() <= 5) return s.toString();
        return s.stream().limit(5).toList() + "…(+" + (s.size() - 5) + ")";
    }
}
