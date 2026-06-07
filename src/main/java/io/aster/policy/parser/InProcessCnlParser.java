package io.aster.policy.parser;

import aster.core.ast.Decl;
import aster.core.ast.Module;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.identifier.IdentifierIndex;
import aster.core.lexicon.Lexicon;
import aster.core.lexicon.LexiconRegistry;
import aster.core.parser.AstBuilder;
import aster.core.parser.AsterCustomLexer;
import aster.core.parser.AsterParser;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * 内嵌 CNL 解析器
 *
 * 使用 ANTLR 直接解析 CNL 源代码，替代通过 CLI 工具调用的方式。
 * 这使得 Quarkus Policy API 可以在没有 aster-convert CLI 的环境中运行。
 */
public class InProcessCnlParser {

    private static final Logger LOG = Logger.getLogger(InProcessCnlParser.class);

    /**
     * CNL 解析结果
     */
    public record ParseResult(
        Module module,
        String moduleName,
        String firstFunctionName
    ) {}

    /**
     * 解析 CNL 源代码（使用默认 locale）
     *
     * @param source CNL 源代码
     * @return 解析结果，包含 AST 模块和提取的模块/函数名称
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source) {
        return parse(source, null);
    }

    /**
     * 解析 CNL 源代码（支持多语言）
     *
     * @param source CNL 源代码
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示自动检测或默认英语
     * @return 解析结果，包含 AST 模块和提取的模块/函数名称
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source, String locale) {
        return parse(source, locale, null);
    }

    /**
     * 解析 CNL 源代码（支持多语言 + 领域词汇翻译）
     *
     * <p>ADR 0014 线C：发布的策略在执行端也需识别用户自定义领域术语。调用方
     * 传入由策略快照词汇构建的 {@link IdentifierIndex} 时，规范化阶段会执行
     * 标识符翻译（step 8.5），把本地化术语翻成规范化名称；为 null 时行为与
     * 仅内置一致（不做用户词翻译）。
     *
     * @param source 源代码
     * @param locale 语言代码，null 表示默认英语
     * @param identifierIndex 领域词汇索引，null 表示不做用户词翻译
     * @return 解析结果
     * @throws CnlParseException 解析失败时抛出
     */
    public static ParseResult parse(String source, String locale, IdentifierIndex identifierIndex) {
        if (source == null || source.isBlank()) {
            throw new CnlParseException("CNL 源代码不能为空");
        }

        try {
            // 1. 根据 locale 获取对应的 Lexicon 并规范化源代码
            Lexicon lexicon = getLexiconForLocale(locale);

            // 所有语言都需要规范化，因为即使是英语也需要将关键词运算符（如 "greater than"）翻译为符号（如 ">"）
            // ANTLR 解析器只支持符号运算符，不支持关键词形式
            // 提供 identifierIndex 时启用领域标识符翻译（ADR 0014 线C）。
            Canonicalizer canonicalizer = identifierIndex != null
                ? new Canonicalizer(lexicon, identifierIndex)
                : new Canonicalizer(lexicon);
            String canonicalizedSource = canonicalizer.canonicalize(source);
            LOG.debugf("CNL 规范化完成: locale=%s, 原始长度=%d, 规范化后长度=%d",
                locale, source.length(), canonicalizedSource.length());
            // Debug: 输出完整规范化结果
            LOG.debugf("CNL 规范化输出:\n%s", canonicalizedSource);

            // 2. 创建词法分析器
            CharStream charStream = CharStreams.fromString(canonicalizedSource);
            AsterCustomLexer lexer = new AsterCustomLexer(charStream);
            // R30+ audit P1：ANTLR 默认 lexer 把错误打到 stderr 然后继续，
            // 漏掉的 token 会让 parser 看到一个"看起来合法但语义残缺"的流。
            // 显式挂同一份错误监听器，保证 lexer 错误能传给 CnlParseException。
            CnlErrorListener errorListener = new CnlErrorListener();
            lexer.removeErrorListeners();
            lexer.addErrorListener(errorListener);

            // 3. 创建语法分析器
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            tokens.seek(0);

            AsterParser parser = new AsterParser(tokens);
            // R30+ audit P1：ANTLR 默认 LL 模式在病理语法下会回溯 O(2^n)，
            // 给个 SLL 上限作为 DoS 防御。在正常语法上 SLL 与 LL 等价；
            // 解析失败时 ANTLR 会自动 fallback 到 LL，对正常输入零代价。
            parser.getInterpreter().setPredictionMode(
                org.antlr.v4.runtime.atn.PredictionMode.SLL);

            // 3. 添加错误监听器（与 lexer 共享同一份）
            parser.removeErrorListeners();
            parser.addErrorListener(errorListener);

            // 4. 解析模块
            AsterParser.ModuleContext moduleCtx = parser.module();

            // 检查解析错误：抛给用户的是友好化的首个错误（根因），原始 ANTLR
            // 级联错误仅记日志供调试，避免把几十条内部消息糊到用户脸上。
            if (errorListener.hasErrors()) {
                LOG.debugf("CNL 解析原始错误（全集）: %s", errorListener.getRawErrors());
                throw new CnlParseException("CNL 语法错误 — " + errorListener.getErrors());
            }

            // 5. 构建 AST
            AstBuilder builder = new AstBuilder();
            Module module = builder.visitModule(moduleCtx);

            if (module == null) {
                throw new CnlParseException("无法构建 AST 模块");
            }

            // 6. 提取第一个函数名称
            String firstFunctionName = extractFirstFunctionName(module);

            // 7. 提取模块名称（如果没有模块头，使用函数名作为默认）
            String moduleName = module.name();
            if (moduleName == null || moduleName.isBlank()) {
                if (firstFunctionName != null && !firstFunctionName.isBlank()) {
                    moduleName = firstFunctionName;
                    LOG.debugf("使用函数名作为默认模块名: %s", moduleName);
                } else {
                    throw new CnlParseException("无法从 CNL 中提取模块名称或函数名称");
                }
            }

            LOG.infof("CNL 解析成功: module=%s, function=%s", moduleName, firstFunctionName);

            return new ParseResult(module, moduleName, firstFunctionName);

        } catch (CnlParseException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "CNL 解析失败: %s", e.getMessage());
            throw new CnlParseException("CNL 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从模块中提取第一个函数名称
     */
    private static String extractFirstFunctionName(Module module) {
        if (module.decls() == null || module.decls().isEmpty()) {
            return null;
        }

        Optional<String> funcName = module.decls().stream()
            .filter(decl -> decl instanceof Decl.Func)
            .map(decl -> ((Decl.Func) decl).name())
            .findFirst();

        return funcName.orElse(null);
    }

    /**
     * 根据 locale 获取对应的 Lexicon
     *
     * @param locale 语言代码（如 "zh-CN"、"de-DE"、"en-US"），null 表示默认英语
     * @return 对应的 Lexicon 实例
     */
    private static Lexicon getLexiconForLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return LexiconRegistry.getInstance().getDefault();
        }

        String normalizedLocale = locale.toLowerCase().replace("_", "-");

        // 优先尝试从 LexiconRegistry 获取（支持动态注册的 Lexicon）
        LexiconRegistry registry = LexiconRegistry.getInstance();
        if (registry.has(normalizedLocale)) {
            return registry.getOrThrow(normalizedLocale);
        }

        // 回退到常见 locale 前缀匹配
        if (normalizedLocale.startsWith("zh")) {
            return registry.getOrThrow("zh-cn");
        } else if (normalizedLocale.startsWith("de")) {
            return registry.getOrThrow("de-de");
        } else {
            // 对于不支持的 locale，回退到英语
            LOG.warnf("Unsupported locale '%s', falling back to en-US", locale);
            return registry.getDefault();
        }
    }

    /**
     * CNL 解析异常
     */
    public static class CnlParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CnlParseException(String message) {
            super(message);
        }

        public CnlParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
