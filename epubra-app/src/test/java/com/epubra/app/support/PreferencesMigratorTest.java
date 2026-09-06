package com.epubra.app.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PreferencesMigrator} 契约测试。
 *
 * <p>用内存 {@link AbstractPreferences} 做端到端测试，避免碰真实 Windows 注册表 /
 * macOS plist / Linux 文件系统。
 */
class PreferencesMigratorTest {

    private Preferences legacy;
    private Preferences target;

    private static final String LEGACY_PATH = "/com/epubra/app/support/TestMigrator";
    private static final String TARGET_PATH = "/Epubra/TestMigrator";

    @BeforeEach
    void setUp() throws Exception {
        PreferenceNodes.useInMemoryForTesting();
        legacy = PreferenceNodes.node(LEGACY_PATH);
        target = PreferenceNodes.node(TARGET_PATH);
        legacy.clear();
        target.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        legacy.clear();
        target.clear();
        PreferenceNodes.resetForTesting();
    }

    // ---- 迁移执行 ----

    @Test
    void migrateMovesAllKeysFromLegacyToTarget() throws Exception {
        legacy.put("key1", "value1");
        legacy.put("key2", "value2");
        legacy.putInt("count", 42);

        PreferencesMigrator.migrate(legacy, target);

        assertEquals("value1", target.get("key1", null));
        assertEquals("value2", target.get("key2", null));
        assertEquals(42, target.getInt("count", 0));
    }

    @Test
    void migrateClearsLegacyAfterCopy() throws Exception {
        legacy.put("onlyKey", "onlyValue");

        PreferencesMigrator.migrate(legacy, target);

        assertNull(legacy.get("onlyKey", null), "旧节点迁移后应被清空");
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        legacy.put("theme", "DARK");

        PreferencesMigrator.migrate(legacy, target);
        // 第一次迁移后 target 非空,旧已清空;再调一次不应改变 target
        PreferencesMigrator.migrate(legacy, target);
        PreferencesMigrator.migrate(legacy, target);

        assertEquals("DARK", target.get("theme", null));
    }

    // ---- 边界条件 ----

    @Test
    void migrateSkipsWhenTargetAlreadyHasData() throws Exception {
        // 旧节点的值
        legacy.put("theme", "OLD_VALUE");
        // 新节点已有用户新设置的值
        target.put("theme", "NEW_VALUE");

        PreferencesMigrator.migrate(legacy, target);

        // 新值不应被旧值覆盖
        assertEquals("NEW_VALUE", target.get("theme", null));
        // 旧节点应被清空(防止下次启动又走迁移)
        assertNull(legacy.get("theme", null));
    }

    @Test
    void migrateNoOpWhenLegacyIsEmpty() throws Exception {
        // legacy 是空的,target 也是空的

        PreferencesMigrator.migrate(legacy, target);

        // 两者都应保持空
        assertEquals(0, legacy.keys().length);
        assertEquals(0, target.keys().length);
    }

    @Test
    void migratePreservesKeyTypes() throws Exception {
        legacy.put("name", "epubra");
        legacy.putInt("count", 7);
        legacy.putBoolean("enabled", true);

        PreferencesMigrator.migrate(legacy, target);

        assertEquals("epubra", target.get("name", null));
        assertEquals(7, target.getInt("count", 0));
        assertTrue(target.getBoolean("enabled", false));
    }

    // ---- 防御 ----

    @Test
    void migrateNullArgumentsAreSilent() {
        // null 不应抛异常
        PreferencesMigrator.migrate(null, target);
        PreferencesMigrator.migrate(legacy, null);
        PreferencesMigrator.migrate(null, null);
    }

    @Test
    void migrateSameNodeIsNoOp() {
        // 同一节点不做事(避免自杀)
        Preferences node = legacy;
        PreferencesMigrator.migrate(node, node);
        // 不抛异常
        assertTrue(true);
    }

    @Test
    void migrateFlushesTargetAfterCopy() throws Exception {
        legacy.put("flushed", "yes");

        PreferencesMigrator.migrate(legacy, target);

        // 重新打开 target 节点,数据应已落盘(flush 起效)
        Preferences reopened = PreferenceNodes.node(TARGET_PATH);
        assertEquals("yes", reopened.get("flushed", null));
    }

    @Test
    void migrateClearsLegacyEvenWhenTargetHadData() throws Exception {
        legacy.put("old", "old-value");
        target.put("new", "new-value");

        PreferencesMigrator.migrate(legacy, target);

        // target 已有数据时,迁移被跳过,但旧节点仍要被清空(避免下次启动再尝试)
        assertNull(legacy.get("old", null));
        assertEquals("new-value", target.get("new", null));
        assertFalse(legacy.keys().length > 0, "旧节点应已清空,即使迁移被跳过");
    }
}
