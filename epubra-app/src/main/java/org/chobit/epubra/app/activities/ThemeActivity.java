package org.chobit.epubra.app.activities;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.RadioMenuItem;
import org.chobit.epubra.app.editor.Theme;
import org.chobit.epubra.app.editor.ThemeManager;

import java.util.function.Consumer;

/**
 * 主题切换编排：落盘偏好 → 换根节点样式类 → 联动状态栏与菜单选中态。
 *
 * <p>从 {@code MainController} 拆出：当前主题、三个单选菜单项、状态栏主题标签自成一组，
 * 与文档编辑无关。{@code MainController} 只保留 {@code onThemeXxx} 一行委派给
 * {@link #switchTo(Theme)}——FXML 的 {@code onAction} 只能绑主控制器的方法。
 *
 * <p>主题需要在 Scene 挂上之后才能应用到根节点，而 FXML 加载时 Scene 还没创建，
 * 所以首次应用借 {@code sceneAnchor}（状态栏标签）的 {@code sceneProperty} 做一次性回调。
 */
public class ThemeActivity {

    private final Label sceneAnchor;
    private final Label themeStatusLabel;
    private final RadioMenuItem lightItem;
    private final RadioMenuItem darkItem;
    private final RadioMenuItem sepiaItem;
    private final Consumer<String> setStatus;
    private final Runnable onApplied;

    private Theme currentTheme;

    public ThemeActivity(Label sceneAnchor, Label themeStatusLabel,
                         RadioMenuItem lightItem, RadioMenuItem darkItem, RadioMenuItem sepiaItem,
                         Consumer<String> setStatus, Runnable onApplied) {
        this.sceneAnchor = sceneAnchor;
        this.themeStatusLabel = themeStatusLabel;
        this.lightItem = lightItem;
        this.darkItem = darkItem;
        this.sepiaItem = sepiaItem;
        this.setStatus = setStatus;
        this.onApplied = onApplied;
        this.currentTheme = ThemeManager.current();
    }

    public Theme current() {
        return currentTheme;
    }

    /** 初始化：同步菜单选中态，并在 Scene 就绪后应用主题。 */
    public void initialize() {
        selectCurrent();
        applyWhenSceneReady();
    }

    /**
     * 切换主题。已是当前主题时直接返回——避免重复落盘与预览刷新。
     */
    public void switchTo(Theme theme) {
        if (theme == currentTheme) {
            return;
        }
        currentTheme = theme;
        ThemeManager.save(theme);
        Scene scene = sceneAnchor == null ? null : sceneAnchor.getScene();
        if (scene != null) {
            ThemeManager.apply(scene, theme);
        }
        if (onApplied != null) {
            onApplied.run();
        }
        if (themeStatusLabel != null) {
            themeStatusLabel.setText(theme.displayName());
        }
        setStatus.accept("已切换到" + theme.displayName() + "主题");
    }

    private void applyWhenSceneReady() {
        Scene scene = sceneAnchor == null ? null : sceneAnchor.getScene();
        if (scene != null) {
            ThemeManager.apply(scene, currentTheme);
            return;
        }
        if (sceneAnchor == null) {
            return;
        }
        sceneAnchor.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> observable, Scene oldScene, Scene newScene) {
                if (newScene == null) {
                    return;
                }
                sceneAnchor.sceneProperty().removeListener(this);
                ThemeManager.apply(newScene, currentTheme);
                if (onApplied != null) {
                    onApplied.run();
                }
            }
        });
    }

    /**
     * 让单选菜单项的选中态与当前主题一致；{@code setSelected} 不触发 onAction，不会递归。
     */
    private void selectCurrent() {
        RadioMenuItem target = switch (currentTheme) {
            case DARK -> darkItem;
            case SEPIA -> sepiaItem;
            case LIGHT -> lightItem;
        };
        // FXML 里万一漏了某个菜单项，宁可只是不高亮，也不要让整个界面起不来
        if (target != null) {
            target.setSelected(true);
        }
        if (themeStatusLabel != null) {
            themeStatusLabel.setText(currentTheme.displayName());
        }
    }
}
