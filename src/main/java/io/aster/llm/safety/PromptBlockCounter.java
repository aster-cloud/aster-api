package io.aster.llm.safety;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UI 路径"前 N 次拦截不扣配额"的计数器（按 tenantId × YYYY-MM 维度）
 *
 * 单进程内存即可——Pod 重启时计数器清零等于"重新给一次容错"，PM 视角可接受。
 * 跨 Pod 共享需要 Redis，列入 API-7 一并升级。
 */
@ApplicationScoped
public class PromptBlockCounter {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /** 自增并返回新值（即"这是第几次拦截"，从 1 开始） */
    public int incrementAndGet(String tenantId) {
        String key = key(tenantId);
        return counters.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 仅用于测试 */
    void reset() {
        counters.clear();
    }

    private static String key(String tenantId) {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        return tenantId + ":" + ym;
    }
}
