package org.chobit.epubra.app.support.editor;

/**
 * 给预览用的章节 XHTML 注入主题样式。
 *
 * <p>WebView 里的内容是作者自己写的 XHTML，配色由文档自身的内联样式决定，JavaFX 的
 * {@code -epubra-*} 变量传不进去；深色主题下若不处理，预览区会仍是白底黑字，与周围
 * 深灰界面反差极大。这里往 {@code <head>} 里追加一段带 {@code !important} 的内联样式，
 * 压过文档自带的配色。
 *
 * <p>纯字符串处理，不依赖 JavaFX 运行时，可单测。
 */
public final class PreviewHtml {

    private static final String XHTML_NS = "http://www.w3.org/1999/xhtml";

    private PreviewHtml() {
    }

    /**
     * 注入主题样式。
     *
     * <p>按文档结构的完整程度依次降级：有 {@code </head>} 就插在它前面；只有
     * {@code <body>} 就补一个 {@code <head>}；只有 {@code <html>} 就插在 html 开标签之后；
     * 连根标签都没有的裸片段则整体包一层最小文档。
     *
     * @param xhtml 章节正文；为 null 或全空白时返回 {@link #emptyDocument(Theme)}
     * @param theme 当前主题
     */
    public static String withTheme(String xhtml, Theme theme) {
        if (xhtml == null || xhtml.isBlank()) {
            return emptyDocument(theme);
        }
        int headClose = indexOfIgnoringCase(xhtml, "</head>");
        if (headClose >= 0) {
            return xhtml.substring(0, headClose) + styleTag(theme) + "\n" + xhtml.substring(headClose);
        }
        int bodyStart = indexOfIgnoringCase(xhtml, "<body");
        if (bodyStart >= 0) {
            return xhtml.substring(0, bodyStart) + "<head>" + styleTag(theme) + "</head>"
                    + xhtml.substring(bodyStart);
        }
        int htmlTagEnd = endOfOpenTag(xhtml, "<html");
        if (htmlTagEnd >= 0) {
            return xhtml.substring(0, htmlTagEnd) + "<head>" + styleTag(theme) + "</head>"
                    + xhtml.substring(htmlTagEnd);
        }
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + styleTag(theme) + "</head><body>"
                + xhtml + "</body></html>";
    }

    /** 无章节时的空预览文档；背景跟随主题，避免出现一张刺眼的空白页。 */
    public static String emptyDocument(Theme theme) {
        return "<html xmlns=\"" + XHTML_NS + "\"><head>" + styleTag(theme) + "</head><body></body></html>";
    }

    private static String styleTag(Theme theme) {
        return "<style type=\"text/css\">\n" + theme.previewStyleCss() + "\n</style>";
    }

    private static int indexOfIgnoringCase(String text, String token) {
        return text.toLowerCase().indexOf(token.toLowerCase());
    }

    /** 返回 {@code <html ...>} 这类开标签结束位置（即 {@code >} 之后的下标）；未找到返回 -1。 */
    private static int endOfOpenTag(String text, String openTagPrefix) {
        int start = indexOfIgnoringCase(text, openTagPrefix);
        if (start < 0) {
            return -1;
        }
        int end = text.indexOf('>', start);
        return end < 0 ? -1 : end + 1;
    }
}
