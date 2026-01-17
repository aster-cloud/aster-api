package io.aster.policy.graphql.types;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Input;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

/**
 * GraphQL 类型定义 - 策略 CRUD
 */
public final class PolicyTypes {

    private PolicyTypes() {
        // 工具类不需要实例化
    }

    @Type("Policy")
    @Description("策略实体 / Policy document")
    public static class Policy {

        @NonNull
        @Description("策略唯一标识 / Policy identifier")
        public String id;

        @NonNull
        @Description("策略名称 / Policy name")
        public String name;

        @NonNull
        @Description("允许规则集 / Allow rule set")
        public PolicyRuleSet allow;

        @NonNull
        @Description("拒绝规则集 / Deny rule set")
        public PolicyRuleSet deny;

        @Description("Aster CNL 源代码 / Aster CNL source code")
        public String cnl;

        public Policy() {
            this("", "", new PolicyRuleSet(), new PolicyRuleSet(), null);
        }

        // 向后兼容构造函数（不含 cnl）
        public Policy(String id, String name, PolicyRuleSet allow, PolicyRuleSet deny) {
            this(id, name, allow, deny, null);
        }

        public Policy(String id, String name, PolicyRuleSet allow, PolicyRuleSet deny, String cnl) {
            this.id = id;
            this.name = name;
            this.allow = allow != null ? allow : new PolicyRuleSet();
            this.deny = deny != null ? deny : new PolicyRuleSet();
            this.cnl = cnl;
        }
    }

    @Type("PolicyRuleSet")
    @Description("策略规则集 / Policy rule set")
    public static class PolicyRuleSet {

        @NonNull
        @Description("资源类型与匹配模式列表 / Resource rules")
        public List<PolicyRule> rules;

        public PolicyRuleSet() {
            this(new ArrayList<>());
        }

        public PolicyRuleSet(List<PolicyRule> rules) {
            this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        }
    }

    @Type("PolicyRule")
    @Description("资源策略规则 / Policy rule definition")
    public static class PolicyRule {

        @NonNull
        @Description("资源类型 / Resource type")
        public String resourceType;

        @NonNull
        @Description("匹配模式列表 / Pattern list")
        public List<String> patterns;

        public PolicyRule() {
            this("", new ArrayList<>());
        }

        public PolicyRule(String resourceType, List<String> patterns) {
            this.resourceType = resourceType;
            this.patterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
        }
    }

    @Input("PolicyInput")
    @Description("策略输入参数 / Policy input payload")
    public static class PolicyInput {

        @Description("策略标识 / Policy identifier")
        public String id;

        @NonNull
        @Description("策略名称 / Policy name")
        public String name;

        @Description("允许规则集 / Allow rule set")
        public PolicyRuleSetInput allow;

        @Description("拒绝规则集 / Deny rule set")
        public PolicyRuleSetInput deny;

        @Description("Aster CNL 源代码 / Aster CNL source code")
        public String cnl;

        public PolicyInput() {
            this(null, "", new PolicyRuleSetInput(), new PolicyRuleSetInput(), null);
        }

        // 向后兼容构造函数（不含 cnl）
        public PolicyInput(String id, String name, PolicyRuleSetInput allow, PolicyRuleSetInput deny) {
            this(id, name, allow, deny, null);
        }

        public PolicyInput(String id, String name, PolicyRuleSetInput allow, PolicyRuleSetInput deny, String cnl) {
            this.id = id;
            this.name = name;
            this.allow = allow != null ? allow : new PolicyRuleSetInput();
            this.deny = deny != null ? deny : new PolicyRuleSetInput();
            this.cnl = cnl;
        }
    }

    @Input("PolicyRuleSetInput")
    @Description("策略规则集输入 / Policy rule set input")
    public static class PolicyRuleSetInput {

        @Description("资源规则列表 / Resource rule list")
        public List<PolicyRuleInput> rules;

        public PolicyRuleSetInput() {
            this(new ArrayList<>());
        }

        public PolicyRuleSetInput(List<PolicyRuleInput> rules) {
            this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>();
        }
    }

    @Input("PolicyRuleInput")
    @Description("策略规则输入 / Policy rule input")
    public static class PolicyRuleInput {

        @NonNull
        @Description("资源类型 / Resource type")
        public String resourceType;

        @NonNull
        @Description("匹配模式列表 / Pattern list")
        public List<String> patterns;

        public PolicyRuleInput() {
            this("", new ArrayList<>());
        }

        public PolicyRuleInput(String resourceType, List<String> patterns) {
            this.resourceType = resourceType;
            this.patterns = patterns != null ? new ArrayList<>(patterns) : new ArrayList<>();
        }
    }
}
