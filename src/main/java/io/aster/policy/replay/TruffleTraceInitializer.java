package io.aster.policy.replay;

import aster.truffle.trace.TraceAccess;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * 进程级开启 truffle trace 记录入口。
 *
 * <p>实际采集仍由 evaluateSource 在当前线程 arm/drain 控制；这里仅打开全局 PE gate，
 * 避免每次请求走 lazy enable，也保证 trace=true 首次请求就能采集步骤。
 */
@ApplicationScoped
class TruffleTraceInitializer {

    void onStart(@Observes StartupEvent event) {
        TraceAccess.setEnabled(true);
    }
}
