package org.chobit.epubra.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
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
    private static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(200);

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
            Map.entry("image", "M3 5h18v14H3z M7.5 12a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3 M21 15l-5-5-11 9"),
            // 字号放大 / 缩小：字母 A + 右侧一个加号 / 减号（Office 惯用形态）。
            // 两个字形之间留 3 个视口单位的空档——17px 下 A 与 +/- 挨太近会糊成一团。
            Map.entry("size-up", "M1.5 19 L6 6 L10.5 19 M3.2 14.3 h5.6 M13.5 13 h8 M17.5 9 v8"),
            Map.entry("size-down", "M1.5 19 L6 6 L10.5 19 M3.2 14.3 h5.6 M13.5 13 h8"),
            Map.entry("undo", "M9 14 4 9l5-5 M4 9h10.5a5.5 5.5 0 0 1 0 11H11"),
            Map.entry("redo", "M15 14l5-5-5-5 M20 9H9.5a5.5 5.5 0 0 0 0 11H13"),
            // 对齐：工具条按当前生效值换用其中一张（见 EditorStyleControls#syncAlignIcon）
            Map.entry("align", "M4 6h16 M4 12h10 M4 18h13"),
            Map.entry("align-center", "M4 6h16 M7 12h10 M5.5 18h13"),
            Map.entry("align-right", "M4 6h16 M10 12h10 M7 18h13"),
            // 字体：一枚「A」字形（字体族＝字形选择）。与文字颜色图标的「A + 色条」同族但不同形——
            // 那一枚底下压着一条会随当前颜色上色的色条，这一枚没有，两者在 17px 下不会认错。
            Map.entry("font", "M2.6 19.4 L9.4 4.6 L16.2 19.4 M5 14.6 h8.8"));

    /** id → SVG 路径；不在 {@link #PATHS} 里的 id 返回 null。 */
    static String path(String id) {
        return PATHS.get(id);
    }

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
            Map.entry("image", "插入图片（从本机选择）"),
            Map.entry("size-up", "放大字号"),
            Map.entry("size-down", "缩小字号"),
            Map.entry("undo", "撤销"),
            Map.entry("redo", "重做"),
            Map.entry("align", "对齐"),
            Map.entry("font", "字体（可输入筛选）"),
            // 颜色控件是 ColorPicker，外观由 colorIcon() 换成「A + 色条」；
            // 提示文案仍从这里取，保证「提示只有一个来源」。
            Map.entry("color", "文字颜色（不透明度拖到 0 = 清除）"));

    private ToolbarIcons() {
    }

    /**
     * 按 id 造一个工具条提示（统一 200ms 延迟）。下拉框 / 取色器这类非图标控件也用它，
     * 保证「提示文案 + 延迟」只有一个来源。
     *
     * @return 未知 id 也会给一个提示（文案取 id 本身），不会是 null
     */
    public static Tooltip tooltip(String id) {
        return tooltipText(LABELS.getOrDefault(id, id));
    }

    /** 任意文案的工具条提示，延迟与 {@link #tooltip(String)} 一致（色板上的色块用它）。 */
    public static Tooltip tooltipText(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(TOOLTIP_SHOW_DELAY);
        return tooltip;
    }

    /**
     * 把工具条里的文字按钮换成图标按钮：清空文字、挂图形与 Tooltip。
     * 已处理过（无文字且有图形）的按钮直接跳过，重复调用无副作用。
     *
     * <p>只认 {@link #PATHS} 里登记过的 id，<b>未登记的控件原样跳过</b>——
     * 所以新增按钮必须同时补 PATHS / LABELS，否则它既没图标也没提示
     * （接线守卫 {@code VisualEditorTabUiTest} 会拦住这种漏配）。
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
            button.setTooltip(tooltip(button.getId()));
            button.setAccessibleText(LABELS.getOrDefault(button.getId(), button.getId()));
        }
    }

    /**
     * 给图标化的 {@link MenuButton}（「对齐」）挂图形、提示与无障碍文案；文字清空，
     * 弹出菜单里的条目仍保留文字。
     *
     * <p>单独一个入口而不是并进 {@link #install(Pane)}：对齐按钮的图形会随当前生效值
     * 变化（左/中/右三张），生命周期由 {@code EditorStyleControls} 管。
     */
    public static void installMenuButton(MenuButton button) {
        if (button == null || button.getId() == null) {
            return;
        }
        String path = PATHS.get(button.getId());
        if (path == null) {
            return;
        }
        button.setText(null);
        button.setGraphic(graphic(path));
        button.setTooltip(tooltip(button.getId()));
        button.setAccessibleText(LABELS.getOrDefault(button.getId(), button.getId()));
    }

    /** 按 id 取图标图形；id 未登记返回 null（调用方据此决定换不换图形）。 */
    public static Node graphicFor(String id) {
        String path = PATHS.get(id);
        return path == null ? null : graphic(path);
    }

    static Node graphic(String svgPath) {
        return graphic(svgPath, ICON_SCALE);
    }

    /**
     * 按指定缩放把路径包成图标节点。
     *
     * <p>{@link Group} 的 layoutBounds 包含子节点变换：缩放后父容器按实际像素尺寸排版。
     * 直接缩放 {@link SVGPath} 则仍按 24px 占位，按钮会被撑高——所以这里必须过一层 Group。
     */
    private static Group graphic(String svgPath, double scale) {
        SVGPath icon = new SVGPath();
        icon.setContent(svgPath);
        icon.setFill(null);
        icon.getStyleClass().add("toolbar-icon");
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        return new Group(icon);
    }

    // ---------------------------------------------------------------- 文字颜色图标

    /** 「A」字形：占满 24×24 视口，供色条图标按较小缩放使用。 */
    private static final String COLOR_LETTER_A = "M2 22 L12 2 L22 22 M6 15 h12";

    /** 色条图标的字母缩放：整体（字母 + 色条）要压到与其它图标同为 ~17px。 */
    private static final double COLOR_LETTER_SCALE = 0.54;

    /** 色条尺寸（px）：宽约等于字母宽度，高 3px 才在 17px 图标里看得见。 */
    private static final double COLOR_BAR_WIDTH = 12.0;
    private static final double COLOR_BAR_HEIGHT = 3.0;

    /**
     * 文字颜色图标：字母 A + 其下方一条色条（Office 惯用形态）。
     *
     * <p>色条单独交出去：当前颜色只有调用方（{@code EditorStyleControls}）知道，需要随
     * 「应用颜色 / 清除颜色」改写它。**必须用内联 style 写 `-fx-fill`**——JavaFX 的 CSS
     * 来源优先级是「内联 > 样式表 > 代码 setFill」，代码里设的 fill 会被样式表压掉。
     */
    public record ColorIcon(Node node, Rectangle bar) {
    }

    /** 造一个「A + 色条」图标；色条初始为空（由调用方按当前颜色上色）。 */
    public static ColorIcon colorIcon() {
        Group letter = graphic(COLOR_LETTER_A, COLOR_LETTER_SCALE);
        Rectangle bar = new Rectangle(COLOR_BAR_WIDTH, COLOR_BAR_HEIGHT);
        bar.getStyleClass().add("toolbar-color-bar");
        bar.setArcWidth(1.2);
        bar.setArcHeight(1.2);
        // 间距 1px：色条要紧贴字母底边，中间留白多了会看成两个控件
        VBox box = new VBox(1.0, letter, bar);
        box.setAlignment(Pos.CENTER);
        box.setMouseTransparent(true);
        return new ColorIcon(box, bar);
    }

    /** 供接线测试断言「工具条里的每个按钮 id 都有图标与提示」。 */
    public static boolean covers(String buttonId) {
        return PATHS.containsKey(buttonId) && LABELS.containsKey(buttonId);
    }
}
