package org.chobit.epubra.app.support.document;

import org.chobit.epubra.app.ui.support.editor.ThemeManager;
import org.chobit.epubra.app.support.platform.PreferenceNodes;
import org.chobit.epubra.app.support.platform.PreferencesMigrator;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 自动暂存的偏好配置：通过 {@link Preferences} 持久化跨进程。
 *
 * <p>三项设置：
 * <ul>
 *   <li>{@link #enabled}：自动暂存开关</li>
 *   <li>{@link #debounceSeconds}：编辑停顿多少秒后落盘</li>
 *   <li>{@link #dirOverride}：自定义暂存目录；为 null 时走 {@code System.getProperty("user.dir") + "/epubra-autosave"}</li>
 * </ul>
 *
 * <p>模式参考 {@link ThemeManager}：静态方法 + {@link Preferences#userNodeForPackage(Class)}。
 * 单元测试可通过 {@link #write} 注入自定义值，再由 {@link #read} 读回验证。
 */
public final class AutosaveConfig {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_DEBOUNCE_SECONDS = 5;

    private static final String KEY_ENABLED = "autosave.enabled";
    private static final String KEY_DEBOUNCE = "autosave.debounceSeconds";
    private static final String KEY_DIR = "autosave.dir";

    private boolean enabled;
    private int debounceSeconds;
    private String dirOverride;

    public AutosaveConfig(boolean enabled, int debounceSeconds, String dirOverride) {
        this.enabled = enabled;
        this.debounceSeconds = debounceSeconds;
        this.dirOverride = dirOverride;
    }

    public boolean enabled() {
        return enabled;
    }

    public int debounceSeconds() {
        return debounceSeconds;
    }

    /** 自定义暂存目录；为 null 时调用方走 {@link AppPaths#autosaveDir()}。 */
    public String dirOverride() {
        return dirOverride;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setDebounceSeconds(int debounceSeconds) {
        this.debounceSeconds = debounceSeconds;
    }

    public void setDirOverride(String dirOverride) {
        this.dirOverride = dirOverride;
    }

    /** 读当前用户的配置（缺省值见 DEFAULT_* 常量）。 */
    public static AutosaveConfig read() {
        try {
            Preferences prefs = preferences();
            return new AutosaveConfig(
                    prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                    prefs.getInt(KEY_DEBOUNCE, DEFAULT_DEBOUNCE_SECONDS),
                    prefs.get(KEY_DIR, null));
        } catch (RuntimeException e) {
            return new AutosaveConfig(DEFAULT_ENABLED, DEFAULT_DEBOUNCE_SECONDS, null);
        }
    }

    /** 写配置到 Preferences 持久存储。 */
    public static void write(AutosaveConfig config) {
        try {
            Preferences prefs = preferences();
            prefs.putBoolean(KEY_ENABLED, config.enabled);
            prefs.putInt(KEY_DEBOUNCE, config.debounceSeconds);
            if (config.dirOverride == null || config.dirOverride.isBlank()) {
                prefs.remove(KEY_DIR);
            } else {
                prefs.put(KEY_DIR, config.dirOverride);
            }
            prefs.flush();
        } catch (BackingStoreException | RuntimeException ignored) {
            // 偏好不可写时放弃持久化，调用方仍可在内存中使用当前配置
        }
    }

    private static Preferences preferences() {
        Preferences prefs = PreferenceNodes.node("/Epubra/AutosaveConfig");
        // 一次性把旧节点 /com/epubra/app/support/AutosaveConfig 的所有键搬到新节点,再删旧节点
        PreferencesMigrator.migrate(
                PreferenceNodes.node("/com/epubra/app/support/AutosaveConfig"),
                prefs);
        return prefs;
    }
}
