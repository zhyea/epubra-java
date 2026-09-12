package org.chobit.epubra.app.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.Map;

/**
 * 编辑器工具条的图标：把 FXML 里的文字按钮换成 SVG 线性图标。
 *
 * <p>为什么是自绘 SVG 而不是字符图标（B / I / ¶ / 🔗）：字符图标在不同字体 / 主题下
 * 粗细与观感不一致，链接、图片这类语义也没有可靠的纯文本字形；线性 SVG 与 WPS / Office
 * 的单色工具条观感一致，描边色走 {@code .toolbar-icon} 的 CSS 规则，跟随主题变化。
 *
 * <p>可发现性靠 {@link Tooltip} 补回：图标不表意时悬停即见原中文文案，
 * 同时写进 {@code accessibleText} 供辅助技术读取。<b>显示延迟压到 200ms</b>——
 * JavaFX 默认约 1 秒，图标没有文字表意，等 1 秒等于「没有提示」（用户实测反馈）；
 * 200ms 快到接近即时，又不至于鼠标扫过一排按钮时提示一闪而过。
 *
 * <p>路径统一按 24×24 视口、描边风格设计（参考 Feather / Lucide 的几何语言），
 * 实际渲染经 {@code Group} 包裹缩放到约 17px——直接 scale 会把 layoutBounds 一起
 * 缩掉，Group 的 bounds 包含子节点变换，按钮尺寸才能算对。
 */
public final class ToolbarIcons {

    /** 渲染缩放：24×24 视口 → 约 17px 的工具条图标。 */
    private static final double ICON_SCALE = 0.72;

    /** Tooltip 显示延迟：默认 1s 对无文字的图标按钮太迟钝，压到 200ms。 */
    private static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(100);

    /** 按钮 id → SVG 路径（24×24，描边风格）。id 与 FXML 按钮及 epubraQuery 格式名一致。 */
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("paragraph", "M4 4h9a4.5 4.5 0 0 1 0 9H4V4 M16 4v16 M20 4v16"),
            Map.entry("heading", "M6 5v14 M18 5v14 M6 12h12"),
            Map.entry("quote", "M5 11h4v5H5z M5 11c0-3 2-5.5 4.5-6.5 M14 11h4v5h-4z M14 11c0-3 2-5.5 4.5-6.5"),
            Map.entry("list", "M9 6h11 M9 12h11 M9 18h11 M4.5 6h.01 M4.5 12h.01 M4.5 18h.01"),
            Map.entry("ol", "M11 6h9 M11 12h9 M11 18h9 "
                    + "M6.5 4 5 5 M6.5 4v5 "
                    + "M4.5 11c.2-.8 1-1.2 1.8-1 .8.2 1.2 1 .6 1.7L4.5 15h2.8 "
                    + "M4.5 15.8h2.8L5.6 18c.9-.2 2 .4 2 1.5 0 1.1-1 1.7-2.1 1.7-.7 0-1.4-.3-1.8-.7"),
            Map.entry("rule", "M4 12h16"),
            Map.entry("bold", "M7 4h5.5a3.5 3.5 0 0 1 0 7H7V4 M7 11h6.5a3.5 3.5 0 0 1 0 7H7V11"),
            Map.entry("italic", "M10 4h9 M5 20h9 M14 4l-4 16"),
            Map.entry("underline", "M6 4v6a6 6 0 0 0 12 0V4 M5 20h14"),
            Map.entry("strike", "M4 12h16 M16.5 7c-.9-1.9-2.7-3-5-3-2.9 0-5 1.4-5 3.2 0 1.7 1.6 2.6 4 3.2 "
                    + "M7.5 17c.9 1.7 2.7 2.7 5 2.7 2.9 0 5-1.3 5-3.2 0-1.2-.7-2.1-2-2.8"),
            Map.entry("code", "M8 6l-6 6 6 6 M16 6l6 6-6 6"),
            Map.entry("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71 "
                    + "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"),
            Map.entry("image", "M3 5h18v14H3z M7.5 12a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3 M21 15l-5-5-11 9"));

    /** 按钮 id → 悬停提示（沿用 FXML 里的原中文文案）。 */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("paragraph", "段落"),
            Map.entry("heading", "标题"),
            Map.entry("quote", "引用"),
            Map.entry("list", "列表"),
            Map.entry("ol", "编号列表"),
            Map.entry("rule", "分隔线"),
            Map.entry("bold", "加粗"),
            Map.entry("italic", "斜体"),
            Map.entry("underline", "下划线"),
            Map.entry("strike", "删除线"),
            Map.entry("code", "行内代码"),
            Map.entry("link", "链接"),
            Map.entry("image", "插入图片（从本机选择）"));

    private ToolbarIcons() {
    }

    /**
     * 把工具条里的文字按钮换成图标按钮：清空文字、挂图形与 Tooltip。
     * 已处理过（无文字且有图形）的按钮直接跳过，重复调用无副作用。
     */
    public static void install(Pane toolbar) {
        if (toolbar == null) {
            return;
        }
        for (Node child : toolbar.getChildren()) {
            if (!(child instanceof Button button) || button.getId() == null) {
                continue;
            }
            String path = PATHS.get(button.getId());
            if (path == null) {
                continue;
            }
            button.setText(null);
            button.setGraphic(graphic(path));
            String label = LABELS.getOrDefault(button.getId(), button.getId());
            Tooltip tooltip = new Tooltip(label);
            tooltip.setShowDelay(TOOLTIP_SHOW_DELAY);
            button.setTooltip(tooltip);
            button.setAccessibleText(label);
        }
    }

    static Node graphic(String svgPath) {
        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(null);
        icon.getStyleClass().add("toolbar-icon");
        icon.setScaleX(ICON_SCALE);
        icon.setScaleY(ICON_SCALE);
        // Group 的 layoutBounds 包含子节点变换：缩放后按钮按 ~17px 计尺寸，
        // 直接缩放 SVGPath 则仍按 24px 占位，按钮会被撑高。
        return new Group(icon);
    }

    /** 供接线测试断言「工具条里的每个按钮 id 都有图标与提示」。 */
    public static boolean covers(String buttonId) {
        return PATHS.containsKey(buttonId) && LABELS.containsKey(buttonId);
    }
}
