package com.epubra.app.support;

import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 主题的切换与持久化。
 *
 * <p>切换动作本身只是往 Scene 根节点上加一个 {@code theme-*} 样式类，配色差异全在
 * {@code app.css} 的 {@code .root.theme-*} 规则组里；选择结果写入
 * {@link Preferences}，下次启动由 {@link #current()} 自动恢复。
 */
public final class ThemeManager {

    /** 主题在 Preferences 中的键名。 */
    private static final String PREF_KEY = "theme";

    /** 主题样式类的公共前缀，用于清理上一次选择留下的类。 */
    private static final String STYLE_CLASS_PREFIX = "theme-";

    private ThemeManager() {
    }

    /**
     * 把主题样式类应用到 Scene 的根节点上。
     *
     * <p>控制器在 {@code initialize()} 阶段就要处理主题，而那时 Scene 可能还没创建，
     * 因此这里对 null 入参一律静默返回，绝不抛异常。
     *
     * @param scene 目标场景；为 null 或根节点尚未设置时直接返回
     * @param theme 目标主题；为 null 时直接返回
     */
    public static void apply(Scene scene, Theme theme) {
        if (scene == null || theme == null) {
            return;
        }
        Parent root = scene.getRoot();
        if (root == null) {
            return;
        }
        // 复制一份再删：避免遍历过程中修改 ObservableList
        for (String existing : new ArrayList<>(root.getStyleClass())) {
            if (existing.startsWith(STYLE_CLASS_PREFIX)) {
                root.getStyleClass().remove(existing);
            }
        }
        String target = theme.styleClass();
        if (!root.getStyleClass().contains(target)) {
            root.getStyleClass().add(target);
        }
    }

    /** 读取上次选择的主题；从未选择过或取值非法时回退到 {@link Theme#LIGHT}。 */
    public static Theme current() {
        return Theme.of(preferences().get(PREF_KEY, Theme.LIGHT.storageKey()));
    }

    /**
     * 记住主题，下次启动由 {@link #current()} 恢复。
     *
     * <p>偏好存储不可用时只放弃持久化，不影响本次会话已经生效的切换。
     */
    public static void save(Theme theme) {
        if (theme == null) {
            return;
        }
        try {
            Preferences preferences = preferences();
            preferences.put(PREF_KEY, theme.storageKey());
            preferences.flush();
        } catch (BackingStoreException ignored) {
            // 无注册表 / 无家目录等环境下写不进去就算了，主题在本次会话里仍然有效
        }
    }

    private static Preferences preferences() {
        return Preferences.userRoot().node("/Epubra/ThemeManager");
    }
}
