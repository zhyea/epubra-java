package org.chobit.epubra.app.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题枚举与主题管理器的持久化。
 *
 * <p>Preferences 通过 {@link PreferenceNodes} 切到内存根节点，测试只验证持久化契约，
 * 不触碰真实用户配置。
 */
class ThemeTest {

    @BeforeEach
    void setUpPreferences() {
        PreferenceNodes.useInMemoryForTesting();
    }

    @AfterEach
    void tearDownPreferences() {
        PreferenceNodes.resetForTesting();
    }

    @Test
    @DisplayName("三个主题的样式类名与 app.css 中的 .root.theme-* 规则对应")
    void styleClassMapping() {
        assertEquals("theme-light", Theme.LIGHT.styleClass());
        assertEquals("theme-dark", Theme.DARK.styleClass());
        assertEquals("theme-sepia", Theme.SEPIA.styleClass());
    }

    @Test
    @DisplayName("主题带中文显示名，供菜单与状态栏提示使用")
    void displayNames() {
        assertEquals("浅色", Theme.LIGHT.displayName());
        assertEquals("深色", Theme.DARK.displayName());
        assertEquals("护眼米黄", Theme.SEPIA.displayName());
    }

    @Test
    @DisplayName("持久化取值可还原为枚举，未知或空取值一律回退浅色")
    void parseFromStorage() {
        assertSame(Theme.LIGHT, Theme.of("LIGHT"));
        assertSame(Theme.DARK, Theme.of("DARK"));
        assertSame(Theme.SEPIA, Theme.of("SEPIA"));
        assertSame(Theme.LIGHT, Theme.of(null));
        assertSame(Theme.LIGHT, Theme.of("   "));
        assertSame(Theme.LIGHT, Theme.of("rainbow"));
        assertEquals("LIGHT", Theme.LIGHT.storageKey());
    }

    @Test
    @DisplayName("保存后再读取应拿到同一个主题")
    void saveAndRestoreRoundTrip() {
        Theme original = ThemeManager.current();
        try {
            for (Theme theme : Theme.values()) {
                ThemeManager.save(theme);
                assertSame(theme, ThemeManager.current());
            }
        } finally {
            ThemeManager.save(original);
        }
        assertSame(original, ThemeManager.current());
    }

    @Test
    @DisplayName("深色预览样式用深底浅字，浅色相反，三套互不相同")
    void previewStyleDiffersByTheme() {
        assertTrue(Theme.LIGHT.previewStyleCss().contains(Theme.LIGHT.previewBackground()));
        assertTrue(Theme.DARK.previewStyleCss().contains(Theme.DARK.previewBackground()));
        assertTrue(Theme.SEPIA.previewStyleCss().contains(Theme.SEPIA.previewBackground()));
        assertNotEquals(Theme.DARK.previewStyleCss(), Theme.LIGHT.previewStyleCss());
        assertNotEquals(Theme.SEPIA.previewStyleCss(), Theme.LIGHT.previewStyleCss());
        // 预览 CSS 会被当作 XML 文本解析，不能出现会破坏结构的字符
        for (Theme theme : Theme.values()) {
            String css = theme.previewStyleCss();
            assertTrue(css.indexOf('<') < 0 && css.indexOf('&') < 0 && css.indexOf('>') < 0,
                    "预览样式不应包含 XML 敏感字符：" + theme);
        }
    }

    @Test
    @DisplayName("Scene 尚未创建或主题为 null 时应用主题不抛异常")
    void applyIsDefensive() {
        assertDoesNotThrow(() -> ThemeManager.apply(null, Theme.DARK));
        assertDoesNotThrow(() -> ThemeManager.save(null));
    }
}
