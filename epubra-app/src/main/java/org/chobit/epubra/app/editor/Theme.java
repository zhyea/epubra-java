package org.chobit.epubra.app.editor;

/**
 * 应用主题。
 *
 * <p>{@code styleClass} 与 {@link ThemeManager} 打到 Scene 根节点上的样式类一一对应，
 * 具体配色见 {@code app.css} 的 {@code .root.theme-*} 规则组：只需换一个样式类，
 * 整棵控件树的 {@code -epubra-*} 变量随之重算。
 *
 * <p>预览区是 WebView，吃不到 JavaFX 样式表，因此另备一份 {@code previewStyleCss()}：
 * 加载章节 XHTML 时注入到 {@code <head>} 中，让正文背景与文字跟随主题。
 */
public enum Theme {

    /** 浅色：现有的 WPS / Office 蓝风格，也是默认值。 */
    LIGHT("theme-light", "浅色", "#ffffff", "#1f1f1f", "#1a5fb4", "#dcdcdc", "#f2f2f2", "#c8c8c8"),

    /** 深色：低亮度中性灰底 + 浅色文字，弱化边框，夜间阅读不刺眼。 */
    DARK("theme-dark", "深色", "#1e1e1e", "#dcdcdc", "#7fb2f0", "#3a3a3a", "#2a2a2a", "#4a4a4a"),

    /** 护眼米黄：低饱和暖色，背景米黄、文字深棕灰，避免高饱和刺激。 */
    SEPIA("theme-sepia", "护眼米黄", "#f5ecd9", "#3f3524", "#9a6b34", "#d6c8a8", "#e8dcc0", "#cfc09c");

    private final String styleClass;
    private final String displayName;
    private final String previewBackground;
    private final String previewForeground;
    private final String previewLink;
    private final String previewBorder;
    private final String previewCodeBackground;
    private final String previewScrollThumb;

    Theme(String styleClass, String displayName, String previewBackground, String previewForeground,
          String previewLink, String previewBorder, String previewCodeBackground,
          String previewScrollThumb) {
        this.styleClass = styleClass;
        this.displayName = displayName;
        this.previewBackground = previewBackground;
        this.previewForeground = previewForeground;
        this.previewLink = previewLink;
        this.previewBorder = previewBorder;
        this.previewCodeBackground = previewCodeBackground;
        this.previewScrollThumb = previewScrollThumb;
    }

    /** 打在 Scene 根节点上的样式类名，与 app.css 中的主题规则对应。 */
    public String styleClass() {
        return styleClass;
    }

    /** 中文显示名，用于菜单项与状态栏提示。 */
    public String displayName() {
        return displayName;
    }

    /** 写进 Preferences 的取值。 */
    public String storageKey() {
        return name();
    }

    public String previewBackground() {
        return previewBackground;
    }

    public String previewForeground() {
        return previewForeground;
    }

    public String previewLink() {
        return previewLink;
    }

    public String previewBorder() {
        return previewBorder;
    }

    public String previewCodeBackground() {
        return previewCodeBackground;
    }

    /**
     * 预览滚动条滑块色。
     *
     * <p>与 {@code theme.css} 里 JavaFX 侧的 {@code -epubra-scrollbar-thumb} 取值一一对应：
     * 预览区是 WebView，吃不到那张样式表，但滚动条要和界面里的控件滚动条看起来是同一条，
     * 因此两处必须成对维护——改了这里别忘了 {@code theme.css}。
     */
    public String previewScrollThumb() {
        return previewScrollThumb;
    }

    /**
     * 注入到预览 HTML 的内联样式。
     *
     * <p>作者自己的 XHTML 常常带内联配色，这里统一用 {@code !important} 压过文档自带样式，
     * 否则深色主题下会出现白底黑字的刺眼预览页。
     *
     * <p><b>但文字颜色的那几条必须放过「自己带内联 {@code color} 的元素」</b>
     * （{@code p:not([style*="color"])} 这种写法）：样式表里的 {@code !important} 声明
     * 优先于内联 {@code style}，而工具条的「文字颜色 / 清档」正是往元素上写<b>内联</b> color。
     * 不加这道闸，作者选的颜色会写进正文（源码区可见）却在画布上没有任何变化。
     * 换言之：<b>压的是文档自带的样式表配色，不是作者在编辑器里显式指定的那一条。</b>
     *
     * <p>{@code em/i} 的字体栈是给「斜体可见性」专门定制的（实测依据见
     * {@code VisualEditorInlineFormatTest} 与 {@code ThemeTest} 的诊断记录）：
     * 渲染引擎<b>不做合成斜体</b>（同字体下
     * italic 与 normal 的渲染像素完全一致），雅黑又没有斜体字面——不做任何处理的话
     * {@code <em>} 在画布上完全隐形。西文交给带真斜体字面的 Segoe UI / Times New Roman，
     * 中文按排版惯例用楷体承担「强调」视觉（楷体无斜体字面、引擎也不合成，中文斜体
     * 在本引擎里只能以替代字形呈现）。
     *
     * <p>注意：这段 CSS 会被当作 XML 文本解析，不能出现 {@code <}、{@code >}、{@code &}。
     */
    public String previewStyleCss() {
        return """
                html, body { background: %s !important; color: %s !important; }
                body { margin: 0; padding: 18px 22px; font-family: "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", sans-serif; font-size: 16px; line-height: 1.7; }
                p, li, h1, h2, h3, h4, h5, h6, div, section, article, blockquote, td, th, span, figcaption { border-color: %s !important; }
                p:not([style*="color"]), li:not([style*="color"]), h1:not([style*="color"]), h2:not([style*="color"]), h3:not([style*="color"]), h4:not([style*="color"]), h5:not([style*="color"]), h6:not([style*="color"]), div:not([style*="color"]), section:not([style*="color"]), article:not([style*="color"]), blockquote:not([style*="color"]), td:not([style*="color"]), th:not([style*="color"]), span:not([style*="color"]), figcaption:not([style*="color"]) { color: %s !important; }
                a { color: %s !important; }
                hr, table, th, td, pre, img { border-color: %s !important; }
                pre, code { background: %s !important; padding: 2px 4px; }
                pre:not([style*="color"]), code:not([style*="color"]) { color: %s !important; }
                img { max-width: 100%%; height: auto; }
                em, i, dfn, cite, var { font-style: italic !important; font-family: "Segoe UI", "Times New Roman", "KaiTi", "楷体", serif !important; }
                %s
                """.formatted(previewBackground, previewForeground, previewBorder, previewForeground,
                previewLink, previewBorder, previewCodeBackground, previewForeground, scrollbarCss());
    }

    /**
     * WebView 内滚动条的样式：对齐 JavaFX 侧的目录侧栏口径（7px 细条、无箭头按钮）。
     *
     * <p>WebView 里的滚动条由渲染引擎自己画，JavaFX 样式表够不到，只能用 WebKit 的
     * {@code ::-webkit-scrollbar} 伪元素；不写这段的话，编辑器画布会带着引擎默认的
     * 宽滚动条，与旁边的目录侧栏、「源码」标签页形成明显落差。
     *
     * <p><b>轨道必须显式上色</b>：滚动条占的是页面视口<b>之外</b>的布局空间，
     * {@code html} / {@code body} 的背景铺不到那里，写 {@code transparent} 会露出 WebView
     * 自带的白色底 —— 画布右缘出现一条 7px 白边（实测快照确认）。所以轨道取页面背景色。
     *
     * <p>滑块用 {@code border: 1px solid transparent} + {@code background-clip: content-box}
     * 做出 1px 内缩——和 JavaFX 侧 {@code -fx-background-insets: 0 1px 0 1px} 是同一个
     * 「细条居中」效果（7px 轨道里画 5px 滑块）。
     *
     * <p>注意：这段 CSS 会被当作 XML 文本解析，不能出现 {@code <}、{@code >}、{@code &}。
     */
    private String scrollbarCss() {
        return """
                ::-webkit-scrollbar { width: 7px; height: 7px; }
                ::-webkit-scrollbar-track { background: %s; }
                ::-webkit-scrollbar-thumb { background: %s; border: 1px solid transparent; background-clip: content-box; border-radius: 4px; }
                ::-webkit-scrollbar-button { display: none; width: 0; height: 0; }
                ::-webkit-scrollbar-corner { background: %s; }
                """.formatted(previewBackground, previewScrollThumb, previewBackground).stripTrailing();
    }

    /**
     * 从持久化取值还原主题。
     *
     * @param value Preferences 中存放的 {@link #storageKey()}；空值或未知取值一律回退到浅色，
     *              这样手工改坏配置也不会导致界面没有配色
     */
    public static Theme of(String value) {
        if (value == null || value.isBlank()) {
            return LIGHT;
        }
        String normalized = value.trim();
        for (Theme theme : values()) {
            if (theme.storageKey().equalsIgnoreCase(normalized)) {
                return theme;
            }
        }
        return LIGHT;
    }
}
