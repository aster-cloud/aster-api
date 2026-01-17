package io.aster.policy.runtime;

import io.aster.policy.compiler.CompilationMetadata;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Truffle 策略运行时
 *
 * 管理 GraalVM Polyglot Context 池，提供策略执行 API。
 * Context 池大小 = CPU 核心数，平衡并发性能和内存占用。
 */
@ApplicationScoped
public class TrufflePolicyRuntime {

    private static final Logger LOG = Logger.getLogger(TrufflePolicyRuntime.class);

    private BlockingQueue<Context> contextPool;
    private Engine sharedEngine;
    private int poolSize;

    @PostConstruct
    void init() {
        LOG.info("初始化 TrufflePolicyRuntime...");

        // 1. 创建共享 Engine
        sharedEngine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

        // 2. 初始化 Context 池（大小 = CPU 核心数）
        poolSize = Runtime.getRuntime().availableProcessors();
        contextPool = new LinkedBlockingQueue<>(poolSize);

        LOG.infof("创建 Context 池，大小: %d", poolSize);

        for (int i = 0; i < poolSize; i++) {
            Context ctx = Context.newBuilder("aster")
                .engine(sharedEngine)
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();

            contextPool.offer(ctx);
        }

        LOG.info("TrufflePolicyRuntime 初始化完成");
    }

    /**
     * 执行策略
     *
     * @param coreJson Core IR JSON
     * @param contextArgs 上下文参数
     * @param metadata 编译元数据
     * @return 执行结果
     */
    public ExecutionResult execute(
        String coreJson,
        Object[] contextArgs,
        CompilationMetadata metadata
    ) {
        long startTime = System.currentTimeMillis();
        Context ctx = null;

        try {
            // 1. 从池中获取 Context
            ctx = contextPool.take();

            // 2. 评估 Core IR JSON
            Value evalResult = ctx.eval("aster", coreJson);

            // 3. 执行函数
            Object result = executeFunction(evalResult, contextArgs);

            long executionTime = System.currentTimeMillis() - startTime;
            return ExecutionResult.success(result, executionTime);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "获取 Context 被中断");
            return ExecutionResult.failure("执行被中断: " + e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "策略执行失败: %s", e.getMessage());
            return ExecutionResult.failure("执行失败: " + e.getMessage());
        } finally {
            // 4. 归还 Context 到池
            if (ctx != null) {
                contextPool.offer(ctx);
            }
        }
    }

    /**
     * 执行函数并转换结果
     */
    private Object executeFunction(Value evalResult, Object[] contextArgs) {
        if (evalResult.canExecute()) {
            // 返回值是可执行的函数，传参调用
            Value execResult;
            if (contextArgs == null || contextArgs.length == 0) {
                execResult = evalResult.execute();
            } else {
                execResult = evalResult.execute(contextArgs);
            }
            return convertValue(execResult);
        } else if (evalResult.isHostObject()) {
            // 尝试获取底层 Java 对象
            Object hostObject = evalResult.asHostObject();

            if (hostObject instanceof aster.truffle.nodes.LambdaValue lambdaValue) {
                // 直接调用 LambdaValue.apply()
                Object[] args = contextArgs != null ? contextArgs : new Object[0];
                return lambdaValue.apply(args, null);
            } else {
                return hostObject;
            }
        } else {
            // 直接返回结果
            return convertValue(evalResult);
        }
    }

    /**
     * 转换 Truffle Value 为 Java 对象
     */
    private Object convertValue(Value value) {
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
            } else if (value.fitsInDouble()) {
                return value.asDouble();
            }
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            long size = value.getArraySize();
            Object[] array = new Object[(int) size];
            for (int i = 0; i < size; i++) {
                array[i] = convertValue(value.getArrayElement(i));
            }
            return array;
        }
        if (value.hasMembers()) {
            // 返回 Value 本身，让调用方处理
            return value;
        }

        // 默认返回 host object
        return value.isHostObject() ? value.asHostObject() : value;
    }

    @PreDestroy
    void cleanup() {
        LOG.info("清理 TrufflePolicyRuntime...");

        if (contextPool != null) {
            contextPool.forEach(Context::close);
            contextPool.clear();
        }

        if (sharedEngine != null) {
            sharedEngine.close();
        }

        LOG.info("TrufflePolicyRuntime 清理完成");
    }
}
