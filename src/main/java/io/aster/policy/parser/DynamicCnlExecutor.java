package io.aster.policy.parser;

import aster.core.ast.Module;
import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.module.LinkException;
import aster.core.module.ModuleGraphLinker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aster.common.JacksonMappers;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aster.policy.api.convert.NamedContextMapper;
import io.aster.policy.module.ModuleResolutionException;
import io.aster.policy.module.ModuleResolver;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * 动态 CNL 执行器
 *
 * 完整实现 CNL 源代码的动态编译和执行流程：
 * 1. 解析 CNL → AST
 * 2. 降级 AST → Core IR
 * 3. 序列化 Core IR → JSON
 * 4. 使用 GraalVM Polyglot 执行 JSON
 *
 * 适用于 Dashboard 执行场景，无需预先部署策略
 */
@jakarta.enterprise.context.ApplicationScoped
public class DynamicCnlExecutor {

    private static final Logger LOG = Logger.getLogger(DynamicCnlExecutor.class);
    private static final ObjectMapper MAPPER = JacksonMappers.PRETTY;

    @Inject
    ModuleResolver moduleResolver;

    @ConfigProperty(name = "aster.modules.enabled", defaultValue = "false")
    boolean modulesEnabled;

    /**
     * 进程级共享 Engine。GraalVM 推荐模式：Engine 持有 AST/字节码 cache
     * 与运行时元数据（重资源、线程安全），可被多个 Context 共享；
     * Context 仍按调用新建以保证隔离性，但走 Engine 时只分配运行时状态，
     * 不再每次重做 AST 装载与类初始化。在 evaluate-source 这种"每调用
     * 一份源码"的场景下，未共享 Engine 会让每个并发请求都付一份完整的
     * Truffle 初始化代价（GraalVM 文档明确警告"never create per-request
     * engines"），是 2GB 堆下并发 4 即 OOM 的根因。
     *
     * 关闭 WarnInterpreterOnly 警告——CE 版本无 JIT 是已知情况。
     */
    private static final Engine SHARED_ENGINE = Engine.newBuilder("aster")
        .option("engine.WarnInterpreterOnly", "false")
        .build();

    /**
     * 动态执行结果
     */
    public record ExecutionResult(
        Object result,
        String moduleName,
        String functionName,
        long executionTimeMs
    ) {}

    /**
     * 动态执行 CNL 源代码（使用默认 locale）
     *
     * @param source CNL 源代码
     * @param context 评估上下文参数
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @return 执行结果
     */
    public static ExecutionResult execute(String source, Object[] context, String functionName) {
        return execute(source, context, functionName, null);
    }

    /**
     * 动态执行 CNL 源代码（支持多语言）
     *
     * @param source CNL 源代码
     * @param context 评估上下文参数
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 执行结果
     */
    public static ExecutionResult execute(String source, Object[] context, String functionName, String locale) {
        return executeInternal(source, context, functionName, locale, false, null, true, null, null, false);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式）
     *
     * 支持两种上下文格式：
     * 1. 命名格式: { "申请": {...}, "年龄": 25 } - 参数名与函数定义匹配
     * 2. 位置格式: [{...}, 25] - 按位置顺序传参
     *
     * @param source CNL 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名（可选，默认使用第一个函数）
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(String source, Object context, String functionName, String locale) {
        return executeWithContext(source, context, functionName, locale, null);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式 + 领域词汇翻译）
     *
     * <p>ADR 0014 线C：发布的策略可携带其快照领域词汇，使执行端的规范化阶段
     * 能把用户自定义术语翻译为规范化名称。{@code identifierIndex} 为 null 时
     * 行为与仅内置一致。
     *
     * @param source 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(
            String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex) {
        return executeWithContext(source, context, functionName, locale, identifierIndex, true);
    }

    /**
     * 动态执行 CNL 源代码（支持命名参数格式 + 领域词汇翻译 + 入口兼容开关）
     *
     * @param source 源代码
     * @param context 评估上下文（Map 或 List/Array）
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @param legacyEvaluateSentinel 是否把显式 evaluate 视为历史自动入口哨兵
     * @return 执行结果
     */
    public static ExecutionResult executeWithContext(
            String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel) {
        return executeInternal(
            source, context, functionName, locale, true, identifierIndex, legacyEvaluateSentinel,
            null, null, false);
    }

    /**
     * CDI entry point used by callers that have tenant context and module feature flags.
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel) {
        return executeWithTenantContext(tenantId, source, context, functionName, locale,
            identifierIndex, legacyEvaluateSentinel, null);
    }

    /**
     * 带用户别名（ADR 0022）的租户上下文执行。aliasSet 为已发布版本冻结的可信快照，
     * 按 allowStructural=true 应用（冻结版本 = 已授权+校验+进 envelope，执行时信任）。
     * null/空 aliasSet 时行为与无别名一致（向后兼容）。
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet) {
        // 兼容重载：aliasSet 视为可信冻结快照（既有内部调用方语义）。
        return executeWithTenantContext(tenantId, source, context, functionName, locale,
            identifierIndex, legacyEvaluateSentinel, aliasSet, true);
    }

    /**
     * 带用户别名 + 显式信任标志的租户上下文执行（ADR 0022 安全边界）。
     *
     * @param aliasesTrusted true=aliasSet 是已发布版本冻结的可信快照（allowStructural=true，
     *   结构词别名放行，因创建时已授权+校验）；false=未冻结的现场用户输入（allowStructural=false，
     *   结构词别名需授权，被 UserAliasValidator 拒）。区分「存储版本执行」与「trial 源码预览」。
     */
    public ExecutionResult executeWithTenantContext(
            String tenantId, String source, Object context, String functionName, String locale,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        return executeInternal(
            source, context, functionName, locale, true, identifierIndex, legacyEvaluateSentinel,
            tenantId, moduleResolver, modulesEnabled, aliasSet, aliasesTrusted);
    }

    /**
     * 内部执行方法
     *
     * @param source CNL 源代码
     * @param context 评估上下文
     * @param functionName 要执行的函数名
     * @param locale 语言代码
     * @param mapNamedContext 是否需要映射命名上下文
     * @param identifierIndex 领域词汇索引（null 表示不做用户词翻译）
     * @param legacyEvaluateSentinel 是否把显式 evaluate 视为历史自动入口哨兵
     * @return 执行结果
     */
    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleResolver moduleResolver, boolean modulesEnabled) {
        return executeInternal(source, context, functionName, locale, mapNamedContext, identifierIndex,
            legacyEvaluateSentinel, tenantId, moduleResolver, modulesEnabled, null, true);
    }

    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleResolver moduleResolver, boolean modulesEnabled,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet) {
        // 兼容重载：默认 aliasSet 可信（既有调用方语义）。
        return executeInternal(source, context, functionName, locale, mapNamedContext, identifierIndex,
            legacyEvaluateSentinel, tenantId, moduleResolver, modulesEnabled, aliasSet, true);
    }

    private static ExecutionResult executeInternal(
            String source, Object context, String functionName, String locale, boolean mapNamedContext,
            aster.core.identifier.IdentifierIndex identifierIndex, boolean legacyEvaluateSentinel,
            String tenantId, ModuleResolver moduleResolver, boolean modulesEnabled,
            Map<aster.core.lexicon.SemanticTokenKind, List<String>> aliasSet, boolean aliasesTrusted) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 解析 CNL → AST（传入 locale 以支持多语言，传入 index 以翻译用户词）。
            //    带用户别名（ADR 0022）时走 parseWithUserAliases。allowStructural 按 aliasesTrusted：
            //    冻结版本可信=true（结构词已授权）；trial 现场输入=false（结构词需授权，防绕过）。
            LOG.debugf("解析 CNL 源代码... locale=%s, vocab=%s, aliases=%s, trusted=%s",
                locale, identifierIndex != null, aliasSet != null && !aliasSet.isEmpty(), aliasesTrusted);
            InProcessCnlParser.ParseResult parseResult = (aliasSet != null && !aliasSet.isEmpty())
                ? InProcessCnlParser.parseWithUserAliases(source, locale, identifierIndex, aliasSet, aliasesTrusted)
                : InProcessCnlParser.parse(source, locale, identifierIndex);
            Module astModule = parseResult.module();

            // 2. 确定要执行的函数名（优先级：显式 functionName > @entry 注解 > 单 Rule > 诊断）
            EntryPointSelector.Selection selection = EntryPointSelector.select(
                functionName, parseResult.functionNames(), parseResult.entryFunctionName(),
                legacyEvaluateSentinel);
            String targetFunction;
            if (selection instanceof EntryPointSelector.Selected selected) {
                targetFunction = selected.function();
            } else if (selection instanceof EntryPointSelector.Ambiguous ambiguous) {
                throw new AmbiguousEntryException(ambiguous.candidates());
            } else if (selection instanceof EntryPointSelector.NotFound notFound) {
                throw new DynamicExecutionException(
                    "未找到指定函数 '" + notFound.requested() + "',可用: " + notFound.candidates());
            } else {
                throw new DynamicExecutionException("CNL 中未找到可执行的函数");
            }

            LOG.infof("目标函数: %s.%s", parseResult.moduleName(), targetFunction);

            // 3. 降级 AST → Core IR
            LOG.debugf("降级 AST → Core IR...");
            CoreLowering lowering = new CoreLowering();
            CoreModel.Module coreModule = lowering.lowerModule(astModule);
            List<Decl.Import> imports = importsOf(astModule);
            if (!imports.isEmpty() && modulesEnabled) {
                if (moduleResolver == null) {
                    throw new ModuleResolutionException(
                        ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                        "Module resolver is unavailable");
                }
                LOG.debugf("解析跨模块 imports: count=%d, tenant=%s", imports.size(), tenantId);
                var graph = moduleResolver.resolveGraph(tenantId, coreModule, imports, locale);
                coreModule = new ModuleGraphLinker().link(graph).merged();
                LOG.debugf("跨模块 imports 已 link: modules=%d, edges=%d", graph.modules().size(), graph.imports().size());
            }

            // 4. 映射命名上下文到位置参数（如需）
            Object[] positionalContext;
            if (mapNamedContext) {
                // 查找目标函数的参数定义
                List<CoreModel.Param> functionParams = findFunctionParams(coreModule, targetFunction);
                if (functionParams == null) {
                    throw new DynamicExecutionException("未找到函数参数定义: " + targetFunction);
                }

                // 使用 NamedContextMapper 映射
                NamedContextMapper.MappingResult mappingResult = NamedContextMapper.mapContext(context, functionParams);
                if (!mappingResult.success()) {
                    throw new DynamicExecutionException("参数映射失败: " + mappingResult.error());
                }

                positionalContext = mappingResult.positionalArgs();
                if (mappingResult.wasNamedFormat()) {
                    LOG.infof("命名上下文已映射为位置参数，参数数量: %d", positionalContext.length);
                }
                if (!mappingResult.warnings().isEmpty()) {
                    mappingResult.warnings().forEach(w -> LOG.warnf("参数映射警告: %s", w));
                }
            } else {
                // 直接使用位置参数
                positionalContext = context instanceof Object[] arr ? arr : new Object[] { context };
            }

            // 5. 序列化 Core IR → JSON
            LOG.debugf("序列化 Core IR → JSON...");
            String coreJson = MAPPER.writeValueAsString(coreModule);
            LOG.debugf("Core JSON 长度: %d 字符", coreJson.length());

            // 6. 使用 GraalVM Polyglot 执行
            LOG.debugf("使用 GraalVM Polyglot 执行...");
            Object result = executeWithPolyglot(coreJson, targetFunction, positionalContext);

            long executionTime = System.currentTimeMillis() - startTime;
            LOG.infof("动态执行完成: %s.%s, 耗时 %dms", parseResult.moduleName(), targetFunction, executionTime);

            return new ExecutionResult(
                result,
                parseResult.moduleName(),
                targetFunction,
                executionTime
            );

        } catch (InProcessCnlParser.CnlParseException e) {
            throw new DynamicExecutionException("CNL 解析失败: " + e.getMessage(), e);
        } catch (ModuleResolutionException e) {
            throw new ModuleExecutionException(e);
        } catch (LinkException e) {
            throw new ModuleExecutionException(new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_CYCLE,
                e.getMessage(),
                e
            ));
        } catch (DynamicExecutionException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "动态执行失败: %s", e.getMessage());
            throw new DynamicExecutionException("动态执行失败: " + e.getMessage(), e);
        }
    }

    private static List<Decl.Import> importsOf(Module module) {
        if (module == null || module.decls() == null) {
            return List.of();
        }
        return module.decls().stream()
            .filter(Decl.Import.class::isInstance)
            .map(Decl.Import.class::cast)
            .toList();
    }

    /**
     * 使用 GraalVM Polyglot 执行 Core IR JSON
     *
     * AsterLanguage.parse() 使用 Loader 构建程序，返回值取决于入口函数：
     * - 有参函数：返回 LambdaValue（可执行），需要传参调用
     * - 无参函数：直接返回执行结果
     */
    private static Object executeWithPolyglot(String coreJson, String functionName, Object[] context) {
        // 审计 #98（Low，DEFERRED）：此处构建的 Polyglot Context 无墙钟/CPU 看门狗
        // （context.close(true)）。当前安全，因为 DSL 无循环/迭代构造，执行必然有界。
        // 一旦语言引入循环/迭代，必须在此加执行超时看门狗。本 PR 不改，仅记录 defer（见 #98）。
        // 沙箱权限（红队 P1-D：从 allowPublicAccess(true) 收紧，向生产 TrufflePolicyRuntime
        // 的 HostAccess.EXPLICIT 靠拢）。
        // 真正的危险面是 allowPublicAccess(true)——它放开**所有** public 方法/字段/构造器的
        // guest→host 反射访问，一旦有 host 对象泄漏进 guest 值就有 Java 方法调用/类逃逸面。
        // 改用 EXPLICIT 作基线（仅 @HostAccess.Export 方法可被 guest 调用；Builtins 表 +
        // Truffle DSL 节点均已标注），再**仅**重新开放三类数据 interop 访问器：
        //   - allowArrayAccess / allowListAccess / allowMapAccess
        // 因为 DynamicCnlExecutor 把评估上下文作为 Java Map/List 直接传给 guest 函数，
        // guest 的 MemberAccessNode 要读 `context.age` 需要 map 成员访问（否则报
        // "HostObject 不支持成员访问"）。这三者只暴露"读结构化数据"，不暴露任意方法/类，
        // 与 allowPublicAccess(true) 的攻击面有本质区别。
        // 另加 allowHostClassLookup(name -> false) 显式禁止按名查找 host 类。
        // PolyglotAccess.NONE / IOAccess.NONE / 无进程·线程·native 保持不变。
        try (Context polyglotContext = Context.newBuilder("aster")
                .engine(SHARED_ENGINE)  // 复用进程级 Engine，避免 per-request AOT 重做
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                    .allowArrayAccess(true)   // 允许读数组元素（结构化上下文）
                    .allowListAccess(true)    // 允许读 List 元素
                    .allowMapAccess(true)     // 允许读 Map 成员（context.field）
                    .build())
                .allowHostClassLookup(name -> false)        // 禁止按名查找 host 类（防类逃逸）
                .allowPolyglotAccess(PolyglotAccess.NONE)  // 禁止跨语言访问
                .allowIO(IOAccess.NONE)                     // 禁止文件/网络 I/O
                .allowCreateProcess(false)                  // 禁止创建子进程
                .allowCreateThread(false)                   // 禁止创建线程
                .allowNativeAccess(false)                   // 禁止本地代码访问
                .build()) {

            // 内置函数（算术 / 比较 / 逻辑 / 字符串拼接）由 aster-lang-truffle 的
            // Builtins 静态初始化块权威注册——含 int/double 提升、浮点除法、`+`
            // dual-mode 字符串拼接、运算符符号→canonical 名归一化。此处不再重复注册：
            // 历史上 aster-api 维护了一份并行的、仅整数/仅数值的拷贝（registerBuiltins），
            // 它把字符串强转 Number 而抛 ClassCastException（如 `"Hello, " + name`），
            // 且 REGISTRY 是共享静态表，重复 register 会**覆盖**掉 truffle 的正确实现。
            // 删除后由 truffle 唯一负责。

            // 评估 Core IR JSON - 返回入口函数的 LambdaValue 或无参函数的执行结果
            Value evalResult = polyglotContext.eval("aster", coreJson);

            LOG.debugf("Polyglot eval 返回: canExecute=%b, isNull=%b, isHostObject=%b",
                evalResult.canExecute(), evalResult.isNull(), evalResult.isHostObject());

            Object result;
            if (evalResult.canExecute()) {
                // 返回值是可执行的函数，传参调用
                Value execResult;
                try {
                    if (context == null || context.length == 0) {
                        execResult = evalResult.execute();
                    } else {
                        execResult = evalResult.execute(context);
                    }
                } catch (NullPointerException | org.graalvm.polyglot.PolyglotException e) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("null")) {
                        // Polyglot 无法转换 null 返回值，视为函数无匹配结果
                        throw new DynamicExecutionException(
                            "函数返回 null（可能是 Match 语句无匹配分支或条件未覆盖所有情况）");
                    }
                    throw new DynamicExecutionException("Polyglot 执行失败: " + msg, e);
                }
                result = convertValue(execResult);
            } else if (evalResult.isHostObject()) {
                // 尝试获取底层 Java 对象
                Object hostObject = evalResult.asHostObject();
                LOG.debugf("Host object 类型: %s", hostObject.getClass().getName());

                if (hostObject instanceof aster.truffle.nodes.LambdaValue lambdaValue) {
                    // 直接调用 LambdaValue.apply()
                    LOG.infof("检测到 LambdaValue，直接调用 apply 方法");
                    Object[] args = context != null ? context : new Object[0];
                    result = lambdaValue.apply(args, null);
                } else {
                    // 其他 host object，直接返回
                    result = hostObject;
                }
            } else if (evalResult.hasMembers() && evalResult.hasMember("apply")) {
                // 检测到类 LambdaValue 对象（有 apply 成员），尝试调用 apply 方法
                LOG.infof("检测到具有 apply 成员的对象，尝试调用 apply");
                try {
                    Value applyMember = evalResult.getMember("apply");
                    if (applyMember.canExecute()) {
                        Value execResult;
                        if (context == null || context.length == 0) {
                            execResult = applyMember.execute();
                        } else {
                            execResult = applyMember.execute(context);
                        }
                        result = convertValue(execResult);
                    } else {
                        // apply 不可执行，尝试通过 as(Class) 获取 LambdaValue
                        result = invokeLambdaViaReflection(evalResult, context);
                    }
                } catch (Exception e) {
                    LOG.warnf("调用 apply 成员失败: %s, 尝试反射调用", e.getMessage());
                    result = invokeLambdaViaReflection(evalResult, context);
                }
            } else {
                // 返回值是无参函数的执行结果，直接使用
                result = convertValue(evalResult);
            }

            return result;

        } catch (DynamicExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new DynamicExecutionException("Polyglot 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过反射调用 LambdaValue（当 Polyglot 无法直接识别时）
     */
    private static Object invokeLambdaViaReflection(Value value, Object[] context) {
        try {
            // 尝试使用 as(Class) 获取 LambdaValue
            aster.truffle.nodes.LambdaValue lambdaValue = value.as(aster.truffle.nodes.LambdaValue.class);
            if (lambdaValue != null) {
                LOG.infof("通过 as(Class) 获取到 LambdaValue，调用 apply");
                Object[] args = context != null ? context : new Object[0];
                return lambdaValue.apply(args, null);
            }
        } catch (Exception e) {
            LOG.debugf("as(LambdaValue.class) 失败: %s", e.getMessage());
        }

        // 尝试通过反射直接调用 apply 方法
        try {
            // 获取底层对象
            Object underlyingObject = null;
            if (value.isProxyObject()) {
                underlyingObject = value.asProxyObject();
            }

            if (underlyingObject != null) {
                java.lang.reflect.Method applyMethod = underlyingObject.getClass().getMethod(
                    "apply", Object[].class, com.oracle.truffle.api.frame.VirtualFrame.class);
                Object[] args = context != null ? context : new Object[0];
                return applyMethod.invoke(underlyingObject, args, null);
            }
        } catch (Exception e) {
            LOG.debugf("反射调用失败: %s", e.getMessage());
        }

        throw new DynamicExecutionException("无法执行 LambdaValue: 不支持的 Polyglot 值类型");
    }

    /**
     * 转换 Polyglot Value 为 Java 对象
     */
    private static Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            int size = (int) value.getArraySize();
            Object[] array = new Object[size];
            for (int i = 0; i < size; i++) {
                array[i] = convertValue(value.getArrayElement(i));
            }
            return array;
        }
        if (value.hasMembers()) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValue(value.getMember(key)));
            }
            return map;
        }
        // 默认返回字符串表示
        return value.toString();
    }

    /**
     * 从 Core IR 模块中查找指定函数的参数列表
     *
     * @param module Core IR 模块
     * @param functionName 函数名
     * @return 参数列表，未找到返回 null
     */
    private static List<CoreModel.Param> findFunctionParams(CoreModel.Module module, String functionName) {
        if (module == null || module.decls == null) {
            return null;
        }

        for (CoreModel.Decl decl : module.decls) {
            if (decl instanceof CoreModel.Func func && func.name.equals(functionName)) {
                return func.params != null ? func.params : List.of();
            }
        }

        return null;
    }

    /**
     * 动态执行异常
     */
    public static class DynamicExecutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DynamicExecutionException(String message) {
            super(message);
        }

        public DynamicExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Structured module/linking failure.
     */
    public static class ModuleExecutionException extends DynamicExecutionException {
        private static final long serialVersionUID = 1L;

        private final ModuleResolutionException resolutionException;

        public ModuleExecutionException(ModuleResolutionException resolutionException) {
            super(resolutionException.getMessage(), resolutionException);
            this.resolutionException = resolutionException;
        }

        public ModuleResolutionException resolutionException() {
            return resolutionException;
        }
    }

    /**
     * 入口函数不唯一。
     */
    public static class AmbiguousEntryException extends DynamicExecutionException {
        private static final long serialVersionUID = 1L;
        // List.copyOf 产物是可序列化的不可变列表；编译器仅看接口类型故告警，此处明确抑制。
        @SuppressWarnings("serial")
        private final List<String> candidates;

        public AmbiguousEntryException(List<String> candidates) {
            super("未指定入口函数，候选函数不唯一: " + candidates);
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public List<String> getCandidates() {
            return candidates;
        }
    }
}
