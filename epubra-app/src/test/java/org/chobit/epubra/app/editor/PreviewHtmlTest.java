package org.chobit.epubra.app.editor;

import org.chobit.epubra.app.editor.PreviewHtml;
import org.chobit.epubra.app.editor.Theme;
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

    // ------------------------------------------------------------------ 可视化编辑文档

    @Test
    @DisplayName("编辑文档把 body 置为 contenteditable 且保留原正文")
    void editableDocumentMakesBodyEditable() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        assertTrue(doc.contains("contenteditable=\"true\""), "body 必须可编辑");
        assertTrue(doc.contains("<p>正文</p>"), "原始正文不能被丢掉");
        assertTrue(doc.matches("(?s).*<body[^>]*contenteditable=\"true\"[^>]*>.*"),
                "contenteditable 应加在 body 开标签上");
    }

    @Test
    @DisplayName("编辑文档注入桥接脚本，且脚本包在 CDATA 里")
    void editableDocumentInjectsBridgeScript() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        assertTrue(doc.contains("epubraBridge"), "脚本必须把结果推给 Java 桥");
        assertTrue(doc.contains("epubraSerialize"), "需要暴露主动拉取入口");
        assertTrue(doc.contains("<![CDATA["), "文档按 XML 解析，脚本必须用 CDATA 包住");
        // 桥接脚本必须在 head 内，否则 XML 解析会把 <script> 当正文
        assertTrue(doc.indexOf("epubraBridge") < doc.indexOf("</head>"));
    }

    @Test
    @DisplayName("注入样式带 id，回写时才能按 id 剥掉预览配色")
    void injectedStyleCarriesId() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.DARK);

        assertTrue(doc.contains("id=\"" + PreviewHtml.INJECTED_STYLE_ID + "\""));
        assertTrue(doc.contains("getElementById") || doc.contains("querySelector"),
                "脚本要能按 id 定位并移除注入样式");
    }

    @Test
    @DisplayName("缺 body 的裸片段也能得到可编辑文档")
    void editableDocumentWrapsBareFragment() {
        String doc = PreviewHtml.editableDocument("<p>裸片段</p>", Theme.LIGHT);

        assertTrue(doc.contains("contenteditable=\"true\""));
        assertTrue(doc.contains("<p>裸片段</p>"));
    }

    @Test
    @DisplayName("工具条入口暴露在脚本里，且不改动 execCommand 之外的标签选择")
    void editableDocumentExposesToolbarEntryPoints() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        assertTrue(doc.contains("window.epubraFormat"), "工具条的格式化入口");
        assertTrue(doc.contains("window.epubraInsertHtml"), "图片等片段插入入口");
        // 刻意不用 execCommand：它产出的标签随引擎而异（<b>/<span style>），
        // 而回写正文必须是确定的 XHTML。（注释里提到它没关系，不能有调用）
        assertTrue(!doc.contains("document.execCommand("), "不应调用 execCommand");
        assertTrue(doc.indexOf("window.epubraFormat") < doc.indexOf("</head>"),
                "入口必须在 head 内定义，且先于 body 解析完成");
    }
}
