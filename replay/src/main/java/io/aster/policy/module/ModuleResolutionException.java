package io.aster.policy.module;

import java.util.List;

/**
 * Structured module resolution failure surfaced to evaluate-source diagnostics.
 */
public class ModuleResolutionException extends RuntimeException {

    public enum Code {
        IMPORT_VERSION_REQUIRED,
        MODULE_VERSION_NOT_FOUND,
        MODULE_NOT_VISIBLE,
        MODULE_CYCLE
    }

    private final Code code;
    private final List<String> candidates;

    public ModuleResolutionException(Code code, String message) {
        this(code, message, List.of(), null);
    }

    public ModuleResolutionException(Code code, String message, List<String> candidates) {
        this(code, message, candidates, null);
    }

    public ModuleResolutionException(Code code, String message, Throwable cause) {
        this(code, message, List.of(), cause);
    }

    public ModuleResolutionException(Code code, String message, List<String> candidates, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public Code code() {
        return code;
    }

    public List<String> candidates() {
        return candidates;
    }
}
