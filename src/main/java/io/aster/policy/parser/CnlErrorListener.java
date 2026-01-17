package io.aster.policy.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * ANTLR 错误监听器
 *
 * 收集 CNL 解析过程中的语法错误
 */
public class CnlErrorListener extends BaseErrorListener {

    private final List<String> errors = new ArrayList<>();

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        Object offendingSymbol,
        int line,
        int charPositionInLine,
        String msg,
        RecognitionException e
    ) {
        errors.add(String.format("行 %d:%d - %s", line, charPositionInLine, msg));
    }

    /**
     * 是否有解析错误
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * 获取所有错误信息
     */
    public String getErrors() {
        return String.join("; ", errors);
    }

    /**
     * 获取错误列表
     */
    public List<String> getErrorList() {
        return new ArrayList<>(errors);
    }
}
