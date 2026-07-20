package io.aster.replay.runner;

/**
 * runner 侧 toolchainId 复现（仿 aster-api {@code ToolchainIdentityProvider}）。
 * 格式 {@code abi=V1;core=<ver>;validator=<ver>;build=<runtimeBuild>}。
 * ★build 字段来自 env {@code ASTER_RUNTIME_BUILD}——与 aster-api 镜像天然不同，正因如此
 * parity 比对必须排除 toolchainId（见 spec toolchainId 归一）。仅诊断，不进比对判定。
 */
public final class RunnerToolchainId {
    private RunnerToolchainId() {}

    public static String current() {
        String build = System.getenv().getOrDefault("ASTER_RUNTIME_BUILD", "dev");
        return "abi=" + aster.core.lexicon.LexiconAbiVersion.V1.version
            + ";core=" + coreEngineVersion()
            + ";validator=" + io.aster.policy.parser.UserAliasValidator.VERSION
            + ";build=" + build;
    }

    private static String coreEngineVersion() {
        String v = aster.core.canonicalizer.Canonicalizer.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }
}
