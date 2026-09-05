package com.epubra.app.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览区主题样式的注入逻辑。
 */
class PreviewHtmlTest {

    private static final String FULL_DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<head><title>第一章</title></head>"
            + "<body><h1>标题</h1><p>正文</p></body></html>";

    @Test
    @DisplayName("有 head 时样式插在 </head> 之前，正文保持不变")
    void injectBeforeHeadClose() {
        String themed = PreviewHtml.withTheme(FULL_DOC, Theme.DARK);
        int styleAt = themed.indexOf("<style");
        assertTrue(styleAt >= 0);
        assertTrue(styleAt < themed.indexOf("</head>"));
        assertTrue(themed.contains("<p>正文</p>"));
        assertTrue(themed.endsWith("</html>"));
    }

    @Test
    @DisplayName("只有 body 时自动补一个 head")
    void injectWithBodyOnly() {
        String themed = PreviewHtml.withTheme("<body><p>片段</p></body>", Theme.SEPIA);
        assertTrue(themed.contains("<head><style"));
        assertTrue(themed.indexOf("<head>") < themed.indexOf("<body>"));
        assertTrue(themed.contains("<p>片段</p>"));
    }

    @Test
    @DisplayName("裸片段被包成最小文档而不是把样式拼在末尾")
    void wrapBareFragment() {
        String themed = PreviewHtml.withTheme("<p>只有一段</p>", Theme.LIGHT);
        assertTrue(themed.startsWith("<html"));
        assertTrue(themed.contains("</body></html>"));
        assertTrue(themed.indexOf("<style") < themed.indexOf("<p>只有一段</p>"));
    }

    @Test
    @DisplayName("空内容返回带主题背景的空文档")
    void emptyDocumentCarriesTheme() {
        String empty = PreviewHtml.emptyDocument(Theme.DARK);
        assertTrue(empty.contains("<style"));
        assertTrue(empty.contains(Theme.DARK.previewBackground()));
        assertTrue(empty.contains("</body></html>"));
        assertTrue(PreviewHtml.withTheme(null, Theme.DARK).contains("<style"));
        assertTrue(PreviewHtml.withTheme("   ", Theme.SEPIA).contains("<style"));
    }

    @Test
    @DisplayName("切换主题后注入的配色确实不同")
    void themeChangesInjectedColors() {
        String dark = PreviewHtml.withTheme(FULL_DOC, Theme.DARK);
        String light = PreviewHtml.withTheme(FULL_DOC, Theme.LIGHT);
        assertTrue(dark.contains(Theme.DARK.previewBackground()));
        assertTrue(light.contains(Theme.LIGHT.previewBackground()));
        assertTrue(!dark.replace(Theme.DARK.previewBackground(), Theme.LIGHT.previewBackground()).equals(dark));
    }
}
