package org.chobit.epubra.app.editor;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 格式化工具条的状态点亮。
 *
 * <p>搬迁自 {@code MainController}（拆分批次 B，纯搬运）。归属理由：这里只认「按钮集合」
 * 与「本次生效的格式名」，既不碰书也不碰 WebView，与编辑会话无耦合。
 *
 * <p>按钮的 {@code id} 就是格式名（见 {@code main-window.fxml}），所以不用为每个按钮维护
 * 一个字段——遍历子节点读 id 即可，加按钮时不必改 Java。
 */
public final class EditorToolbarController {

    /** 工具条按钮「当前格式生效」时挂的样式类，见 app.css 的 {@code .flat-button.active}。 */
    private static final String ACTIVE_CLASS = "active";

    /** 动作按钮的标记样式类（如「图片」）：没有「光标处格式生效」状态，不参与点亮。 */
    private static final String ACTION_CLASS = "toolbar-action";

    private final FlowPane toolbar;

    public EditorToolbarController(FlowPane toolbar) {
        this.toolbar = toolbar;
    }

    /**
     * 按 {@code window.epubraQuery()} 的返回值点亮工具条。
     *
     * @param active 空格分隔的生效格式名，如 {@code "bold italic"}；空串表示无
     */
    public void update(String active) {
        if (toolbar == null) {
            return;
        }
        Set<String> on = active == null || active.isBlank()
                ? Set.of()
                : new HashSet<>(List.of(active.trim().split("\\s+")));
        for (Node child : toolbar.getChildren()) {
            if (!(child instanceof Button button) || button.getId() == null) {
                continue;
            }
            // 动作按钮（如「图片」= 弹文件选择器）没有「生效格式」状态，
            // epubraQuery 永远不会返回它的 id；不跳过的话它只是一个永不点亮的摆设。
            if (button.getStyleClass().contains(ACTION_CLASS)) {
                continue;
            }
            boolean shouldBeOn = on.contains(button.getId());
            boolean isOn = button.getStyleClass().contains(ACTIVE_CLASS);
            if (shouldBeOn && !isOn) {
                button.getStyleClass().add(ACTIVE_CLASS);
            } else if (!shouldBeOn && isOn) {
                button.getStyleClass().remove(ACTIVE_CLASS);
            }
        }
    }

    /** 离开编辑 tab 时清掉工具条的高亮，避免切回来还留着上一章的状态。 */
    public void clear() {
        update("");
    }
}
