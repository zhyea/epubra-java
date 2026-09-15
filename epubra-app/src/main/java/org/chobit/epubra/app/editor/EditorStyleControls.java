package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
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
 *   <li><b>字体是「按钮下拉」</b>（{@link MenuButton}，与对齐同形）：工具条上只留一枚图标按钮，
 *       点开才是清单。清单列本机全部字体族（{@code Font.getFamilies()}，实测约 258 个），
 *       常用档置顶；当前生效的那一款在清单里以<b>单选态</b>标出——按钮上不挂族名，
 *       否则「Microsoft JhengHei」这类长名字会把工具条撑变形。
 *       弹层顶部一条输入框：边打边筛，回车可把列表外的族名直接当族名应用。
 *       换台机器打开同一本书时，装不到的族名仍要原样显示，而不是被强行归到「默认」档
 *       （那会静默改掉作者的排版意图）。</li>
 *   <li><b>字体值必须加引号</b>：{@code Font.getFamilies()} 返回的族名里过半数带空格
 *       （实测 167/258），CSS 里不加引号会被当成字体栈拆开。写进正文的一律是
 *       {@code "Microsoft YaHei"} 这种带双引号形态；回显时再剥掉引号取首段。</li>
 *   <li><b>颜色用取色器</b>（{@link ColorPicker}），自带标准色板与「自定义颜色…」，
 *       外观经 {@code ToolbarIcons.colorIcon()} 换成「A + 色条」图标（色条随当前颜色上色）。
 *       {@code value == null} = 正文没有 color 声明；把不透明度滑到 0 同样归一成「清除」——
 *       {@code rgba(…,0)} 与「没设颜色」视觉上无异，塞进正文只是一条无意义声明。</li>
 *   <li><b>回显必须防回环</b>：{@code update()} 是程序化设值，会改动清单里的选中项 ——
 *       {@link RadioMenuItem#setSelected(boolean)} 在某些 JavaFX 版本上会连带触发
 *       {@code onAction}，那就又是一次 {@code applyFormat} → 又回显……用 {@code syncing} /
 *       {@code fontFiltering} 两个标志挡住（程序化重建清单与写输入框文本时也会走同一批监听器）。</li>
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

    /** 字体弹层顶部输入框的提示语：两种用法（边打边筛 / 回车把输入当族名应用）都写进去。 */
    private static final String FONT_FILTER_PROMPT = "筛选字体，回车应用";

    /** 本机字体族缓存（{@code Font.getFamilies()} 廉价但没必要反复枚举）。 */
    private static List<String> systemFonts;

    private final MenuButton font;
    private final ColorPicker color;
    private final MenuButton align;
    private final BiConsumer<String, String> applyFormat;

    /** 字体清单的单选组：清单里「当前生效的是哪一款」靠它标出。 */
    private final ToggleGroup fontGroup = new ToggleGroup();

    /** 字体弹层顶部的筛选输入框。 */
    private final TextField fontFilter = new TextField();

    /**
     * 承载筛选输入框的菜单项。
     *
     * <p>{@code hideOnClick=false} 是关键：输入框在弹层里，点它不能把弹层收掉，否则
     * 「想打字筛选，一点弹层就没了」。
     */
    private final CustomMenuItem fontFilterItem = new CustomMenuItem(fontFilter, false);

    /** 文字颜色图标的色条：随当前颜色改写填充（空 = 正文没有 color 声明）。 */
    private Rectangle colorBar;

    /** 回显期间为 true：挡住「程序化设值 → 监听器 → 又下发一次命令」的回环（口径 5）。 */
    private boolean syncing;

    /** 过滤期间为 true：重建清单 / 写回输入框文本会触发文本监听器，同样要挡。 */
    private boolean fontFiltering;

    /** 当前生效的字体显示名：用来判断「手输的值是否真的变了」，避免重复下发。 */
    private String appliedFont = FONT_DEFAULT;

    public EditorStyleControls(MenuButton font, ColorPicker color,
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
        font.setFocusTraversable(false);
        // 图标 + 悬停提示：id 就是「font」，与工具条上其它控件同一套来源（ToolbarIcons）
        ToolbarIcons.installMenuButton(font);
        markFlat(font);

        fontFilter.setPromptText(FONT_FILTER_PROMPT);
        fontFilter.setPrefColumnCount(14);
        // 边打边筛：清单跟着输入收窄。重建清单本身会写输入框、也会换 items，
        // 两者都会回到这个监听器上，所以要有 fontFiltering 这道闸。
        fontFilter.textProperty().addListener((obs, old, now) -> {
            if (fontFiltering) {
                return;
            }
            rebuildFontItems(now);
        });
        // 回车：把输入原样当族名应用（列表外族名的手输通道），随即收起弹层
        fontFilter.setOnAction(e -> applyTypedFont());

        // 每次展开都复位：清单回全量、输入框清空并聚焦——展开即可直接打字筛选。
        // 焦点必须等弹层真正显示出来再要（runLater），否则弹层窗口还没就绪，焦点落不下去。
        font.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) {
                return;
            }
            rebuildFontItems("");
            setFilterText("");
            Platform.runLater(fontFilter::requestFocus);
        });

        // 输入框那一项只在装配时插入一次，之后原地保留：重建清单只换它后面的字体选项。
        // 若连它一起 setAll，承载输入框的菜单项会脱离菜单再挂回去，焦点随之丢掉——
        // 现象是「打出第一个字母之后就打不进第二个字了」。
        font.getItems().add(fontFilterItem);
        rebuildFontItems("");
        appliedFont = FONT_DEFAULT;
    }

    /**
     * 重建字体清单：{@code [筛选输入框] + [默认档 + 字体族…]}。
     *
     * <p>整份重建而不是增删差集——每一项都是新对象，就不必维护「哪一项对应哪个族名」的映射，
     * 也不会残留上一轮的选中态。旧项先退出单选组再丢弃，免得 {@link ToggleGroup} 攥着
     * 已经不在清单里的项不放。
     *
     * <p><b>筛选输入框那一项原地保留</b>（见 {@link #installFont()}）：它一动，焦点就没了。
     */
    private void rebuildFontItems(String typed) {
        if (font == null) {
            return;
        }
        List<MenuItem> options = new ArrayList<>();
        for (String name : fontItems(typed)) {
            options.add(fontOption(name));
        }
        fontFiltering = true;
        try {
            for (int i = font.getItems().size() - 1; i >= 0; i--) {
                MenuItem existing = font.getItems().get(i);
                if (existing == fontFilterItem) {
                    continue;
                }
                if (existing instanceof RadioMenuItem radio) {
                    radio.setToggleGroup(null);
                }
                font.getItems().remove(i);
            }
            font.getItems().addAll(options);
        } finally {
            fontFiltering = false;
        }
        syncSelection();
    }

    /** 一个字体选项：单选态 + 选中即下发命令（与对齐的 {@code alignItem} 同一形态）。 */
    private RadioMenuItem fontOption(String display) {
        RadioMenuItem item = new RadioMenuItem(display);
        item.setToggleGroup(fontGroup);
        item.setOnAction(e -> applyFont(display));
        return item;
    }

    /**
     * 让清单里的单选态跟住 {@link #appliedFont}。
     *
     * <p>族名不在清单里时（本机没装——换台机器打开同一本书）<b>临时补一项</b>并把选中给它：
     * 归到「默认」档等于静默改掉作者的排版意图（口径 2）。
     *
     * <p>整个过程置 {@code syncing}：{@code setSelected} 在部分 JavaFX 版本上会连带触发
     * {@code onAction}，不挡的话「回显」会变成「又下发一次命令」——每动一下光标就多一条历史。
     */
    private void syncSelection() {
        if (font == null) {
            return;
        }
        String want = appliedFont == null || appliedFont.isBlank() ? FONT_DEFAULT : appliedFont;
        RadioMenuItem match = null;
        for (MenuItem item : font.getItems()) {
            if (item instanceof RadioMenuItem radio && want.equals(radio.getText())) {
                match = radio;
                break;
            }
        }
        boolean wasSyncing = syncing;
        syncing = true;
        try {
            if (match == null && !FONT_DEFAULT.equals(want)) {
                match = fontOption(want);
                // 紧跟筛选输入框之后、常用档之前
                font.getItems().add(1, match);
            }
            if (match != null) {
                match.setSelected(true);
            }
        } finally {
            syncing = wasSyncing;
        }
    }

    /** 程序化写输入框文本：置 {@code fontFiltering}，免得又触发一次清单重建。 */
    private void setFilterText(String text) {
        if (text.equals(fontFilter.getText())) {
            return;
        }
        fontFiltering = true;
        try {
            fontFilter.setText(text);
        } finally {
            fontFiltering = false;
        }
    }

    /** 手输提交：把输入框里的字原样当族名应用；空输入 = 回默认档。 */
    private void applyTypedFont() {
        String raw = fontFilter.getText();
        String typed = raw == null ? "" : raw.trim();
        font.hide();
        applyFont(typed.isEmpty() ? FONT_DEFAULT : typed);
        // 应用完把清单复位成全量：下次展开仍是完整可挑的状态
        rebuildFontItems("");
    }

    /**
     * 本机全部字体族；懒加载 + 进程内缓存，取不到时退回空表（清单至少还有常用档）。
     *
     * <p>对外可见：接线测试要断言「字体清单确实列全了本机字体族」。
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

    /** 供接线测试断言「字体清单确实列了本机全部的字体族」。 */
    public static int systemFontCount() {
        return systemFonts().size();
    }

    /**
     * 字体清单的候选项：{@code [默认] + 常用档（置顶）+ 全量（跟随）}，按输入做大小写不敏感子串过滤。
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

    /**
     * 应用一款字体：记下当前档（回显要用），并把命令经 {@code applyFormat} 发出去。
     *
     * <p>{@code syncing} 期间只记账、不下发——那是回显路径，命令的源头已在别处
     * （见 {@link #syncSelection()}）。
     */
    private void applyFont(String display) {
        appliedFont = display;
        if (syncing) {
            return;
        }
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
                // 先把「当前档」落定，再让清单的单选态跟上来——syncSelection 读的就是它
                appliedFont = family == null || family.isBlank() ? FONT_DEFAULT : family;
                syncSelection();
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
