package org.chobit.epubra.app.editor;

import javafx.scene.Node;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import org.chobit.epubra.app.ui.ToolbarIcons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 工具条上的「样式」控件组：字体、文字颜色、对齐。
 *
 * <p><b>字号不在本类里</b>——它由工具条上一对「放大 / 缩小」按钮驱动，按固定档位表跳档
 * （见 {@code editor-script.js} 的 {@code SIZE_STEPS} / {@code stepFontSize}）。
 * 用按钮而不是下拉框：一档一档点比「展开 → 找档 → 选中」少两次操作，结果也是整齐值。
 *
 * <p>归属理由与 {@link EditorToolbarController} 对称——它只管「按钮按格式名点亮」，
 * 这里只管「字体 / 颜色 / 对齐这三类没有对应 XHTML 标签、必须落到受控内联样式上的属性」。
 * 两者都不碰书，也不碰 WebView；命令一律经构造期注入的 {@code applyFormat} 走出去
 * （{@code MainController.applyVisualFormat} → {@code VisualEditorSession.format}）。
 *
 * <p>五条口径：
 * <ol>
 *   <li><b>XHTML 没有字体 / 颜色 / 对齐标签</b>，只能写内联 {@code style}。
 *       属性白名单在 JS 侧（{@code editor-script.js} 的 {@code INLINE_STYLE_PROPS} /
 *       {@code BLOCK_STYLE_PROPS}），这里只负责给值；值全是受控字面量，
 *       作者从本机粘贴进来的外来样式仍在 sanitize 阶段被剥掉——「外来样式不进正文」
 *       与「作者自己选的样式进正文」是两件事，别混。</li>
 *   <li><b>字体下拉列的是本机全部字体族</b>（{@code Font.getFamilies()}，实测约 258 个），
 *       常用档置顶，全量跟随；可输入筛选，也能直接手输列表外的族名——换台机器打开同一本书时，
 *       装不到的族名仍要原样显示，而不是被强行归到「默认」档（那会静默改掉作者的排版意图）。</li>
 *   <li><b>字体值必须加引号</b>：{@code Font.getFamilies()} 返回的族名里过半数带空格
 *       （实测 167/258），CSS 里不加引号会被当成字体栈拆开。写进正文的一律是
 *       {@code "Microsoft YaHei"} 这种带双引号形态；回显时再剥掉引号取首段。</li>
 *   <li><b>颜色用取色器</b>（{@link ColorPicker}），自带标准色板与「自定义颜色…」，
 *       外观经 {@code ToolbarIcons.colorIcon()} 换成「A + 色条」图标（色条随当前颜色上色）。
 *       {@code value == null} = 正文没有 color 声明；把不透明度滑到 0 同样归一成「清除」——
 *       {@code rgba(…,0)} 与「没设颜色」视觉上无异，塞进正文只是一条无意义声明。</li>
 *   <li><b>回显必须防回环</b>：{@code update()} 是程序化设值，会触发 ComboBox 的
 *       value / editor 监听器 → 又一次 {@code applyFormat} → 又回显……用 {@code syncing} /
 *       {@code fontFiltering} 两个标志挡住（程序化改 items 与文本时也会走同一批监听器）。</li>
 * </ol>
 *
 * <p>控件全部 {@code setFocusTraversable(false)}：Tab 键留给编辑器内的列表缩进
 * （见 JS 的 Tab 处理），不要被工具条控件截走。鼠标点击不受影响。
 */
public final class EditorStyleControls {

    /**
     * 「默认」档：字体跟随正文（= 从 style 里删掉 font-family 声明）。
     *
     * <p>对外可见：工具条接线测试（{@code VisualEditorTabUiTest}）要拿它断言初始值，
     * 而那个测试在 {@code controller} 包，跨包访问必须 public。
     */
    public static final String FONT_DEFAULT = "默认（跟随正文）";

    /** 常用字体档：置顶显示。机器上没装的自动跳过，不会列出点不到的项。 */
    private static final List<String> COMMON_FONTS = List.of(
            "SimSun", "Microsoft YaHei", "SimHei", "KaiTi", "FangSong",
            "Microsoft JhengHei", "SimSun-ExtB",
            "Arial", "Times New Roman", "Georgia", "Courier New", "Verdana");

    /** 字体下拉展开时一次显示多少行（全量 258 项靠输入筛选，不用滚到底）。 */
    private static final int FONT_VISIBLE_ROWS = 14;

    /** 本机字体族缓存（{@code Font.getFamilies()} 廉价但没必要反复枚举）。 */
    private static List<String> systemFonts;

    private final ComboBox<String> font;
    private final ColorPicker color;
    private final MenuButton align;
    private final BiConsumer<String, String> applyFormat;

    /** 文字颜色图标的色条：随当前颜色改写填充（空 = 正文没有 color 声明）。 */
    private Rectangle colorBar;

    /** 回显期间为 true：挡住「程序化设值 → 监听器 → 又下发一次命令」的回环（口径 5）。 */
    private boolean syncing;

    /** 过滤期间为 true：{@code setItems} / 写回编辑器文本会触发文本监听器，同样要挡。 */
    private boolean fontFiltering;

    /** 当前生效的字体显示名：用来判断「手输的值是否真的变了」，避免重复下发。 */
    private String appliedFont = FONT_DEFAULT;

    public EditorStyleControls(ComboBox<String> font, ColorPicker color,
                               MenuButton align, BiConsumer<String, String> applyFormat) {
        this.font = font;
        this.color = color;
        this.align = align;
        this.applyFormat = applyFormat;
        installFont();
        installColor();
        installAlign();
    }

    // ------------------------------------------------------------------ 字体

    private void installFont() {
        if (font == null) {
            return;
        }
        // 可编辑：既能从列表里挑，也能手输列表外的族名（换机器打开时的兜底）。
        font.setEditable(true);
        font.setPrefWidth(150.0);
        font.setVisibleRowCount(FONT_VISIBLE_ROWS);
        font.setFocusTraversable(false);
        applyTooltip(font, "font");
        showAllFonts();
        font.setValue(FONT_DEFAULT);
        appliedFont = FONT_DEFAULT;

        // 输入筛选：只在弹层展开时过滤。收起后恢复全量，否则「选完一项列表就只剩一项」。
        font.getEditor().textProperty().addListener((obs, old, now) -> {
            if (fontFiltering || !font.isShowing()) {
                return;
            }
            filterFonts(now);
        });
        // 从列表里选中一项 → 应用
        font.valueProperty().addListener((obs, old, now) -> {
            if (syncing || now == null) {
                return;
            }
            applyFont(now);
        });
        // 手输：回车提交（弹层随之收起）
        font.setOnAction(e -> commitTypedFont());
        // 手输：点走（失焦）也提交——不然「输了字但没回车」会被静默丢弃
        font.focusedProperty().addListener((obs, was, now) -> {
            if (was && !now) {
                commitTypedFont();
            }
        });
        // 展开 / 收起各做一件事：展开恢复全量列表，收起把半截筛选文本还回实际生效值
        font.setOnShowing(e -> showAllFonts());
        font.setOnHidden(e -> restoreEditorFromValue());
    }

    /**
     * 本机全部字体族；懒加载 + 进程内缓存，取不到时退回空表（下拉至少还有常用档）。
     *
     * <p>对外可见：接线测试要断言「字体下拉确实列全了本机字体族」。
     */
    public static synchronized List<String> systemFonts() {
        if (systemFonts == null) {
            List<String> families;
            try {
                families = List.copyOf(Font.getFamilies());
            } catch (RuntimeException unavailable) {
                families = List.of();
            }
            systemFonts = families;
        }
        return systemFonts;
    }

    /** 供接线测试断言「字体下拉确实列了本机全部的字体族」。 */
    public static int systemFontCount() {
        return systemFonts().size();
    }

    /**
     * 字体下拉的候选项：{@code [默认] + 常用档（置顶）+ 全量（跟随）}，按输入做大小写不敏感子串过滤。
     *
     * <p>常用档用<b>本机返回的原始拼写</b>（{@link #canonicalName}）——{@code COMMON_FONTS} 里写的
     * 只是查表键，大小写与系统口径不一致时不能把键直接塞进列表。
     *
     * <p>对外可见：接线测试要断言「输入筛选能命中常用档、且条数明显收窄」。
     */
    public static List<String> fontItems(String typed) {
        String needle = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        out.add(FONT_DEFAULT);
        seen.add(FONT_DEFAULT.toLowerCase(Locale.ROOT));

        for (String wanted : COMMON_FONTS) {
            String name = canonicalName(wanted);
            if (name == null || !matches(name, needle)) {
                continue;
            }
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
            }
        }
        for (String name : systemFonts()) {
            if (!matches(name, needle)) {
                continue;
            }
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
            }
        }
        return out;
    }

    /** 把 {@link #COMMON_FONTS} 里的查表键换成系统返回的原始拼写；本机没装返回 null。 */
    private static String canonicalName(String name) {
        for (String candidate : systemFonts()) {
            if (candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean matches(String name, String lowerNeedle) {
        return lowerNeedle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private void showAllFonts() {
        replaceFontItems(fontItems(""));
    }

    private void filterFonts(String typed) {
        replaceFontItems(fontItems(typed));
        String want = typed == null ? "" : typed;
        if (want.equals(font.getEditor().getText())) {
            return;
        }
        // setItems 可能把编辑器文本重置掉（探针实测一般会保留，但别把「一般」当契约），
        // 写回用户刚敲的筛选词并把光标留在末尾——不然输入框会突然变空。
        fontFiltering = true;
        try {
            font.getEditor().setText(want);
        } finally {
            fontFiltering = false;
        }
        font.getEditor().positionCaret(want.length());
    }

    private void replaceFontItems(List<String> items) {
        fontFiltering = true;
        try {
            font.getItems().setAll(items);
        } finally {
            fontFiltering = false;
        }
    }

    /** 手输提交：把编辑器文本落成下拉值，命令由 valueProperty 监听器统一下发（不重复）。 */
    private void commitTypedFont() {
        if (font == null) {
            return;
        }
        String raw = font.getEditor().getText();
        String typed = raw == null ? "" : raw.trim();
        if (typed.isEmpty()) {
            // 清空输入 = 回默认档；值已经就是默认档时 setValue 不触发变更事件，无需再下发
            if (!FONT_DEFAULT.equals(appliedFont)) {
                font.setValue(FONT_DEFAULT);
            }
            return;
        }
        if (typed.equals(appliedFont)) {
            return;
        }
        font.setValue(typed);
    }

    /** 关闭弹层时把编辑器文本还原成实际生效的字体：筛了一半就收起的半截文本不该留在框里。 */
    private void restoreEditorFromValue() {
        if (font == null) {
            return;
        }
        String want = appliedFont == null ? FONT_DEFAULT : appliedFont;
        if (want.equals(font.getEditor().getText())) {
            return;
        }
        fontFiltering = true;
        try {
            font.getEditor().setText(want);
        } finally {
            fontFiltering = false;
        }
    }

    private void applyFont(String display) {
        appliedFont = display;
        applyFormat.accept("font", cssFontValue(display));
    }

    /** 显示名 → CSS 值：默认档是空串；其余一律加双引号（口径 3）。 */
    static String cssFontValue(String display) {
        if (display == null || display.isBlank() || FONT_DEFAULT.equals(display)) {
            return "";
        }
        return '"' + display.trim() + '"';
    }

    // ------------------------------------------------------------------ 颜色

    private void installColor() {
        if (color == null) {
            return;
        }
        color.setFocusTraversable(false);
        applyTooltip(color, "color");
        installColorIcon();
        // 初始「没有颜色」：null 是 ColorPicker 的合法值（自带「自定义颜色…」入口，不需要色板）
        syncing = true;
        try {
            color.setValue(null);
        } finally {
            syncing = false;
        }
        paintColorBar(null);
        color.valueProperty().addListener((obs, old, now) -> {
            // 先上色再判断回环：回显（syncing）也要让色条跟着走，只是不下发命令
            paintColorBar(now);
            if (syncing) {
                return;
            }
            applyFormat.accept("color", toCssColor(now));
        });
    }

    /**
     * 在取色器上叠一枚「A + 色条」图标。
     *
     * <p><b>为什么是「叠」而不是 setGraphic</b>：{@link ColorPicker} 继承 {@code ComboBoxBase}，
     * 不是 {@code Labeled}，<b>根本没有 graphic 属性</b>。要既保留标准色板与不透明度（= 清除颜色
     * 的口径），又要图标外观，只能把图标当兄弟节点叠在取色器上面。FXML 里取色器已经包在一个
     * {@code StackPane} 里（{@code styleClass="toolbar-color"}），这里往那个槽再塞一层。
     *
     * <p>图标 {@code mouseTransparent}——不参与命中，点击继续落到下面的取色器（照旧打开色板）。
     * 取色器自带的色块与颜色名文字由 CSS 隐藏（{@code .toolbar-color} 规则），只留它当点击面。
     */
    private void installColorIcon() {
        ToolbarIcons.ColorIcon icon = ToolbarIcons.colorIcon();
        colorBar = icon.bar();
        Node node = icon.node();
        node.setMouseTransparent(true);
        // 取色器铺满叠放槽，整块都可点（图标只占中间一小块，别让边缘点不到）
        color.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        if (color.getParent() instanceof Pane slot) {
            slot.getChildren().add(node);
        }
    }

    /**
     * 把色条涂成当前颜色：null / 不透明度为 0 → 空心（描边仍在），表示「正文没有 color 声明」。
     *
     * <p>走 {@code setFill} 而不是内联 style——样式表里没写 {@code -fx-fill}，代码设的填充才不被压掉；
     * 那圈细描边由 {@code .toolbar-color-bar} 提供，白底色时才不至于在浅色工具条上「消失」。
     */
    private void paintColorBar(Color value) {
        if (colorBar == null) {
            return;
        }
        colorBar.setFill(value == null || value.getOpacity() <= 0.0 ? Color.TRANSPARENT : value);
    }

    /**
     * {@link Color} → CSS 颜色串。null、或完全不透明以外的「无颜色」态 → 空串（删掉 color 声明）。
     *
     * <p>取色器的不透明度滑到 0 = 用户想「清除颜色」；写成 {@code rgba(…,0)} 也能work，
     * 但会往正文塞一条肉眼无差别的声明，不如归一成空串干净。
     */
    static String toCssColor(Color value) {
        if (value == null || value.getOpacity() <= 0.0) {
            return "";
        }
        return String.format("#%02x%02x%02x",
                Math.round(value.getRed() * 255.0),
                Math.round(value.getGreen() * 255.0),
                Math.round(value.getBlue() * 255.0));
    }

    /** CSS 颜色串 → {@link Color}；空串 / 认不出的写法 → null（取色器留空，不改正文原值）。 */
    static Color toFxColor(String css) {
        if (css == null || css.isBlank()) {
            return null;
        }
        try {
            return Color.web(css);
        } catch (RuntimeException unrecognized) {
            // 源码区手写的写法（rgb() / hsl() / 拼错的色名）交给浏览器解释，取色器留空即可
            return null;
        }
    }

    // ------------------------------------------------------------------ 对齐

    private void installAlign() {
        if (align == null) {
            return;
        }
        align.getItems().addAll(alignItem("左对齐", "left"),
                alignItem("居中", "center"),
                alignItem("右对齐", "right"));
        ToolbarIcons.installMenuButton(align);
        align.setFocusTraversable(false);
        markFlat(align);
    }

    private MenuItem alignItem(String text, String value) {
        MenuItem item = new MenuItem(text);
        Node icon = ToolbarIcons.graphicFor(alignIconId(value));
        if (icon != null) {
            item.setGraphic(icon);
        }
        item.setOnAction(e -> applyFormat.accept("align", value));
        return item;
    }

    // ------------------------------------------------------------------ 回显

    /**
     * 按 {@code window.epubraQueryStyle()} 的返回值回显三类控件的当前值。
     *
     * @param styleState 形如 {@code "font-family=\"SimSun\";font-size=18px;color=#c00000"}；
     *                   空串 / null = 全部回默认档
     */
    public void update(String styleState) {
        Map<String, String> props = parse(styleState);
        syncing = true;
        try {
            if (font != null) {
                String family = firstFamily(props.get("font-family"));
                String display = family == null || family.isBlank() ? FONT_DEFAULT : family;
                font.setValue(display);
                appliedFont = display;
            }
            if (color != null) {
                color.setValue(toFxColor(props.get("color")));
            }
        } finally {
            syncing = false;
        }
        if (align != null) {
            align.setGraphic(ToolbarIcons.graphicFor(alignIconId(props.get("text-align"))));
        }
    }

    /** 离开编辑 tab / 换章节时把控件退回默认档（旧文档的样式不再成立）。 */
    public void clear() {
        update("");
    }

    /**
     * 取字体栈的首段（剥掉引号）：{@code "SimSun", serif} → {@code SimSun}。
     *
     * <p>按逗号切分——族名内部带逗号的情况 CSS 本就要求加引号，实际字体族名里不出现，
     * 不为此增加解析复杂度。
     */
    static String firstFamily(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String first = value.split(",")[0].trim();
        if (first.length() >= 2
                && ((first.startsWith("\"") && first.endsWith("\""))
                || (first.startsWith("'") && first.endsWith("'")))) {
            first = first.substring(1, first.length() - 1);
        }
        return first.trim();
    }

    /** {@code left} / null → 左对齐图标；其余按 {@code align-center} 这种 id 约定拼。 */
    private static String alignIconId(String value) {
        if (value == null || value.isBlank() || "left".equals(value)) {
            return "align";
        }
        return "align-" + value;
    }

    /** 解析 JS 上报的 {@code "属性=值;属性=值"} 串。值里不含分号（字体栈也只用逗号）。 */
    private static Map<String, String> parse(String styleState) {
        Map<String, String> out = new HashMap<>();
        if (styleState == null || styleState.isBlank()) {
            return out;
        }
        for (String part : styleState.split(";")) {
            int at = part.indexOf('=');
            if (at <= 0) {
                continue;
            }
            out.put(part.substring(0, at).trim(), part.substring(at + 1).trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ 装配杂项

    /**
     * 挂悬停提示与无障碍文案。两者取自同一个 {@link Tooltip}——文案只有一个来源，
     * 不会出现「提示写「字体」、读屏读成 font」这种分叉。
     */
    private static void applyTooltip(Control control, String id) {
        Tooltip tip = ToolbarIcons.tooltip(id);
        control.setTooltip(tip);
        control.setAccessibleText(tip.getText());
    }

    /**
     * 补 {@code flat-button} 样式类（FXML 里已声明，这里只兜底）。
     *
     * <p>必须查重再补：{@code ObservableList#add} 允许重复，重复挂同一个类会让
     * 样式表里出现两条同名规则，也会让「控件带哪些样式类」的断言失去意义。
     */
    private static void markFlat(Control control) {
        if (!control.getStyleClass().contains("flat-button")) {
            control.getStyleClass().add("flat-button");
        }
    }
}
