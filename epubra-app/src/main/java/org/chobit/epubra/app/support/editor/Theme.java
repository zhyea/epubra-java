package org.chobit.epubra.app.support.editor;

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
    LIGHT("theme-light", "浅色", "#ffffff", "#1f1f1f", "#1a5fb4", "#dcdcdc", "#f2f2f2"),

    /** 深色：低亮度中性灰底 + 浅色文字，弱化边框，夜间阅读不刺眼。 */
    DARK("theme-dark", "深色", "#1e1e1e", "#dcdcdc", "#7fb2f0", "#3a3a3a", "#2a2a2a"),

    /** 护眼米黄：低饱和暖色，背景米黄、文字深棕灰，避免高饱和刺激。 */
    SEPIA("theme-sepia", "护眼米黄", "#f5ecd9", "#3f3524", "#9a6b34", "#d6c8a8", "#e8dcc0");

    private final String styleClass;
    private final String displayName;
    private final String previewBackground;
    private final String previewForeground;
    private final String previewLink;
    private final String previewBorder;
    private final String previewCodeBackground;

    Theme(String styleClass, String displayName, String previewBackground, String previewForeground,
          String previewLink, String previewBorder, String previewCodeBackground) {
        this.styleClass = styleClass;
        this.displayName = displayName;
        this.previewBackground = previewBackground;
        this.previewForeground = previewForeground;
        this.previewLink = previewLink;
        this.previewBorder = previewBorder;
        this.previewCodeBackground = previewCodeBackground;
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
     * 注入到预览 HTML 的内联样式。
     *
     * <p>作者自己的 XHTML 常常带内联配色，这里统一用 {@code !important} 压过文档自带样式，
     * 否则深色主题下会出现白底黑字的刺眼预览页。
     *
     * <p>注意：这段 CSS 会被当作 XML 文本解析，不能出现 {@code <}、{@code >}、{@code &}。
     */
    public String previewStyleCss() {
        return """
                html, body { background: %s !important; color: %s !important; }
                body { margin: 0; padding: 18px 22px; font-family: "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", sans-serif; font-size: 16px; line-height: 1.7; }
                p, li, h1, h2, h3, h4, h5, h6, div, section, article, blockquote, td, th, span, figcaption { color: %s !important; border-color: %s !important; }
                a { color: %s !important; }
                hr, table, th, td, pre, img { border-color: %s !important; }
                pre, code { background: %s !important; color: %s !important; padding: 2px 4px; }
                img { max-width: 100%%; height: auto; }
                """.formatted(previewBackground, previewForeground, previewForeground, previewBorder,
                previewLink, previewBorder, previewCodeBackground, previewForeground);
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
