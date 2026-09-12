package org.chobit.epubra.app.document;

import org.chobit.epubra.app.platform.PreferenceNodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link AutosaveConfig} 的偏好读写与<b>暂存目录隔离</b>契约。
 *
 * <p>重点覆盖 {@link AutosaveConfig#DIR_PROPERTY} 系统属性覆盖——它是"测试不得写进
 * 开发者真实 {@code ~/.Epubra/autosave/}"这条约定的唯一开关（见父 pom 的 surefire 配置）。
 * 回归价值：一旦有人把优先级改回去，本测试会红，而 GUI 测试则会在下次启动真实应用时
 * 莫名弹出「恢复草稿」——那种症状很难反推到「测试污染了用户数据目录」。
 */
class AutosaveConfigTest {

    private String originalProperty;

    @BeforeEach
    void setUp() {
        originalProperty = System.getProperty(AutosaveConfig.DIR_PROPERTY);
        PreferenceNodes.useInMemoryForTesting();
    }

    @AfterEach
    void tearDown() {
        if (originalProperty == null) {
            System.clearProperty(AutosaveConfig.DIR_PROPERTY);
        } else {
            System.setProperty(AutosaveConfig.DIR_PROPERTY, originalProperty);
        }
        PreferenceNodes.resetForTesting();
    }

    @Test
    void systemPropertyOverridesPreferences() {
        AutosaveConfig.write(new AutosaveConfig(true, 5, "D:/from-preferences"));
        System.setProperty(AutosaveConfig.DIR_PROPERTY, "D:/from-property");

        assertEquals("D:/from-property", AutosaveConfig.read().dirOverride(),
                "系统属性必须压过 Preferences，否则 surefire 的隔离配置形同虚设");
    }

    @Test
    void preferencesUsedWhenPropertyAbsent() {
        System.clearProperty(AutosaveConfig.DIR_PROPERTY);
        AutosaveConfig.write(new AutosaveConfig(true, 7, "D:/from-preferences"));

        AutosaveConfig config = AutosaveConfig.read();

        assertEquals("D:/from-preferences", config.dirOverride());
        assertEquals(7, config.debounceSeconds());
    }

    @Test
    void blankPropertyFallsBackToPreferences() {
        AutosaveConfig.write(new AutosaveConfig(true, 5, "D:/from-preferences"));
        System.setProperty(AutosaveConfig.DIR_PROPERTY, "   ");

        assertEquals("D:/from-preferences", AutosaveConfig.read().dirOverride(),
                "空白的系统属性不应被当成有效目录，否则会 resolve 出一个无意义路径");
    }

    @Test
    void noDirAnywhereYieldsNull() {
        System.clearProperty(AutosaveConfig.DIR_PROPERTY);
        AutosaveConfig.write(new AutosaveConfig(true, 5, ""));

        assertNull(AutosaveConfig.read().dirOverride(),
                "都没有时应返回 null，让调用方走 AppPaths.autosaveDir()");
    }

    @Test
    void writeThenReadRoundTripsThroughInMemoryPreferences() {
        System.clearProperty(AutosaveConfig.DIR_PROPERTY);
        AutosaveConfig.write(new AutosaveConfig(false, 12, "D:/round-trip"));

        AutosaveConfig config = AutosaveConfig.read();

        assertEquals(false, config.enabled());
        assertEquals(12, config.debounceSeconds());
        assertEquals("D:/round-trip", config.dirOverride());
        // 直接核对底层存储用的是新节点（历史节点 /com/epubra/app/support/... 必须留空）
        assertEquals("D:/round-trip",
                PreferenceNodes.node("/Epubra/AutosaveConfig").get("autosave.dir", null));
    }
}
