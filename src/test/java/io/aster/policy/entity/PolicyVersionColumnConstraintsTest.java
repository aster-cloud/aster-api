package io.aster.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 #98（Low + Medium 2）：PolicyVersion 列约束的纯反射回归。
 *
 * <p>不依赖 Quarkus/DB，只读注解，可在无 Docker 的沙箱运行：
 * <ul>
 *   <li>Low：冻结版本的 {@code content} 必须 {@code @Column(updatable=false)}，
 *       与同族 {@code aliasSet}/{@code sourceEnvelopeSha256}/{@code sourceToolchainId}
 *       对齐，使冻结成为预防性（JPA 不发 UPDATE）而非仅检测性约束。</li>
 *   <li>Medium 2：{@code lockVersion} 必须带 {@code @Version} 乐观锁，保证激活/回滚
 *       双重激活竞态被拦截（至多一条 active 行）。</li>
 * </ul>
 */
class PolicyVersionColumnConstraintsTest {

    private static Field field(String name) {
        try {
            return PolicyVersion.class.getField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("PolicyVersion 必须有字段 " + name, e);
        }
    }

    @Test
    void contentIsNonUpdatable() {
        Column col = field("content").getAnnotation(Column.class);
        assertNotNull(col, "content 必须有 @Column");
        assertFalse(col.updatable(),
            "审计 #98 Low：content 必须 updatable=false —— 冻结版本内容不可再改，"
                + "让冻结成为预防性约束");
    }

    @Test
    void frozenSiblingColumnsRemainNonUpdatable() {
        // 防回归：content 与其同族冻结列保持一致的不可变语义。
        for (String f : new String[]{"aliasSet", "sourceEnvelopeSha256", "sourceToolchainId"}) {
            Column col = field(f).getAnnotation(Column.class);
            assertNotNull(col, f + " 必须有 @Column");
            assertFalse(col.updatable(), f + " 必须保持 updatable=false");
        }
    }

    @Test
    void lockVersionHasOptimisticLock() {
        Field lock = field("lockVersion");
        assertTrue(lock.isAnnotationPresent(Version.class),
            "审计 #98 Medium 2：lockVersion 必须带 @Version 乐观锁，"
                + "拦截双重激活竞态");
        Column col = lock.getAnnotation(Column.class);
        assertNotNull(col, "lockVersion 必须有 @Column");
        assertFalse(col.nullable(),
            "lock_version 列 NOT NULL（迁移 DEFAULT 0）");
    }
}
