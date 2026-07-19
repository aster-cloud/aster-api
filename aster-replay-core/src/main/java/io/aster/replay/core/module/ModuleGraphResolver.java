package io.aster.replay.core.module;

import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.module.ModuleGraph;
import java.util.List;

/**
 * module 图解析的 core seam。aster-api 提供 DB-backed 实现（ModuleResolver）；
 * 未来 β runner 提供受签 ModuleClosure 实现。
 *
 * <p>★返回纯 aster-lang {@link ModuleGraph}，参数全纯——PolicyVersion/Panache
 * 不得跨此 seam（保证 core 无 DB 依赖）。
 */
public interface ModuleGraphResolver {
    ModuleGraph resolveGraph(String tenantId, CoreModel.Module rootCore,
                             List<Decl.Import> rootImports, String locale);
}
