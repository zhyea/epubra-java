package org.chobit.epubra.app.editor;

import org.chobit.epubra.app.editor.PreviewHtml;
import org.chobit.epubra.app.editor.Theme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("编辑脚本带 id 且登记进剥离名单——否则回写时整段脚本会混进正文")
    void injectedScriptCarriesIdAndIsRegisteredForStripping() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        assertTrue(doc.contains("<script id=\"" + PreviewHtml.INJECTED_SCRIPT_ID + "\""),
                "脚本元素必须有稳定 id，serialize 才能把它从回写结果里剥掉");
        // JS 侧的 INJECTED_IDS 必须登记全部三个注入物：样式 / 基准 / 脚本
        assertTrue(doc.contains("'" + PreviewHtml.INJECTED_STYLE_ID + "'"));
        assertTrue(doc.contains("'" + PreviewHtml.INJECTED_BASE_ID + "'"));
        assertTrue(doc.contains("'" + PreviewHtml.INJECTED_SCRIPT_ID + "'"));
    }

    @Test
    @DisplayName("stripInjectedScript 只剥我们的脚本，作者自己的脚本不能误伤")
    void stripInjectedScriptRemovesOnlyOurScripts() {
        // 旧版历史形态：无 id，靠内容特征（window.epubra）识别
        String legacy = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
                + "<script type=\"text/javascript\">//<![CDATA[\n"
                + "(function () { window.epubraFormat = function () {}; })();\n"
                + "//]]></script>\n</head><body><p>正文</p></body></html>";
        String cleaned = PreviewHtml.stripInjectedScript(legacy);
        assertFalse(cleaned.contains("epubraFormat"), "历史脚本要被剥掉：" + cleaned);
        assertTrue(cleaned.contains("<p>正文</p>"), "正文不能受伤：" + cleaned);

        // 现行带 id 的整份编辑文档同样按特征剥掉（回写前的兜底防线）
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);
        String stripped = PreviewHtml.stripInjectedScript(doc);
        assertFalse(stripped.contains("epubraFormat"), "现行脚本也要剥得掉：" + stripped);
        assertTrue(stripped.contains("<p>正文</p>"), "正文不能受伤：" + stripped);

        // 作者自己的脚本（CDATA 风格相同但没有 epubra 入口）不能误删
        String own = "<html><head><script type=\"text/javascript\">//<![CDATA[\n"
                + "var highlight = function (el) { el.style.color = 'red'; };\n"
                + "//]]></script></head><body><p>正文</p></body></html>";
        assertEquals(own, PreviewHtml.stripInjectedScript(own), "作者脚本不能被误删");
        assertEquals("", PreviewHtml.stripInjectedScript(""));
        assertNull(PreviewHtml.stripInjectedScript(null));
    }

    @Test
    @DisplayName("加载时清洗历史污染：曾混进正文的编辑脚本不会再次注入")
    void editableDocumentCleansLegacyInjectedScript() {
        String legacyScript = "<script type=\"text/javascript\">//<![CDATA[\n"
                + "window.epubraSerialize = function () { return 'LEGACY-JS'; };\n//]]></script>";
        String polluted = FULL_DOC.replace("</head>", legacyScript + "</head>");

        String doc = PreviewHtml.editableDocument(polluted, Theme.LIGHT);

        int occurrences = doc.split("epubraSerialize", -1).length - 1;
        assertEquals(1, occurrences,
                "剥离历史脚本后应只剩现行注入的一份，否则污染会滚雪球");
        assertFalse(doc.contains("LEGACY-JS"), "历史脚本内容不能残留：" + doc);
        assertTrue(doc.contains("<p>正文</p>"), "原始正文不能被丢掉");
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
        assertTrue(doc.contains("window.epubraQuery"), "光标状态查询入口（驱动工具条点亮）");
        assertTrue(doc.contains("window.epubraSanitize"), "粘贴净化入口");
        // 刻意不用 execCommand：它产出的标签随引擎而异（<b>/<span style>），
        // 而回写正文必须是确定的 XHTML。（注释里提到它没关系，不能有调用）
        assertTrue(!doc.contains("document.execCommand("), "不应调用 execCommand");
        assertTrue(doc.indexOf("window.epubraFormat") < doc.indexOf("</head>"),
                "入口必须在 head 内定义，且先于 body 解析完成");
    }

    @Test
    @DisplayName("脚本带了富文本编辑必备的挂钩：快捷键、选区上报、粘贴拦截")
    void editableDocumentWiresRichTextHooks() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        assertTrue(doc.contains("'keydown'"), "Ctrl+B/I/U 与 Ctrl+Z 需要 keydown 挂钩");
        assertTrue(doc.contains("'selectionchange'"), "工具条点亮需要选区变化上报");
        assertTrue(doc.contains("'paste'"), "粘贴必须被拦截，否则外部 HTML 会污染正文");
        assertTrue(doc.contains("onSelectionChanged"), "选区状态要经桥回传");
        assertTrue(doc.contains("onUndo") && doc.contains("onRedo"),
                "Ctrl+Z/Ctrl+Y 要交给应用的快照撤销，不能留两套栈");
    }

    // ------------------------------------------------------------------ 解析基准（<base>）

    @Test
    @DisplayName("<base> 插在 head 开标签之后，早于其它引用 URL 的元素")
    void baseHrefGoesRightAfterHeadOpenTag() {
        String doc = PreviewHtml.withBaseHref(FULL_DOC, "file:///tmp/preview/OEBPS/text/");

        int headOpen = doc.indexOf("<head>");
        int baseAt = doc.indexOf("<base");
        assertTrue(headOpen >= 0 && baseAt >= 0, doc);
        assertTrue(baseAt > headOpen, "base 必须在 head 内：" + doc);
        assertTrue(baseAt < doc.indexOf("</head>"), "base 必须在 head 内：" + doc);
        assertTrue(doc.contains("href=\"file:///tmp/preview/OEBPS/text/\""), doc);
        assertTrue(doc.contains("<p>正文</p>"), "原有正文不能被改动");
    }

    @Test
    @DisplayName("文档里出现 <header> 不会把 base 插错位置")
    void headDetectionIsNotConfusedByHeaderElement() {
        String withHeader = "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<header>不应被当成 head</header>"
                + "<head><title>t</title></head><body><p>正文</p></body></html>";

        String doc = PreviewHtml.withBaseHref(withHeader, "file:///tmp/x/");

        int baseAt = doc.indexOf("<base");
        assertTrue(baseAt > doc.indexOf("<head>"), doc);
        assertTrue(baseAt < doc.indexOf("</head>"), doc);
        assertFalse(doc.contains("</header><base"), "插到 <header> 后面就白注入了：" + doc);
    }

    @Test
    @DisplayName("可视化编辑文档带 base，且序列化脚本按 id 把它剥掉")
    void editableDocumentCarriesBaseAndStripsItOnSerialize() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT, "file:///tmp/x/OEBPS/");

        assertTrue(doc.contains("id=\"" + PreviewHtml.INJECTED_BASE_ID + "\""), doc);
        assertTrue(doc.contains("href=\"file:///tmp/x/OEBPS/\""), doc);
        // 回写正文时必须剥掉：镜像目录是临时产物，写进书里就成了一条指向本机 /.Epubra 的绝对路径
        assertTrue(doc.contains("'" + PreviewHtml.INJECTED_BASE_ID + "'"),
                "脚本要按 id 定位注入的 base：" + doc);
        assertTrue(doc.contains("<![CDATA["), "脚本必须仍在 CDATA 内");
        // 两参重载不注入 base——没有镜像时行为必须保持原样
        assertFalse(PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT).contains("<base"));
    }

    @Test
    @DisplayName("base 缺失或文档无处可插时原样返回")
    void baseHrefIsSkippedWhenMissing() {
        assertEquals(FULL_DOC, PreviewHtml.withBaseHref(FULL_DOC, null));
        assertEquals(FULL_DOC, PreviewHtml.withBaseHref(FULL_DOC, "   "));
        assertEquals("<p>x</p>", PreviewHtml.withBaseHref("<p>x</p>", "file:///tmp/x/"));
        assertNull(PreviewHtml.withBaseHref(null, "file:///tmp/x/"));
    }

    @Test
    @DisplayName("base 里的 & 会被转义（镜像路径可能落在含 & 的目录下）")
    void baseHrefEscapesXmlSpecialChars() {
        String doc = PreviewHtml.withBaseHref(FULL_DOC, "file:///tmp/a&b/OEBPS/");

        assertTrue(doc.contains("href=\"file:///tmp/a&amp;b/OEBPS/\""), doc);
        assertFalse(doc.contains("a&b"), "裸 & 会破坏 XML 解析：" + doc);
    }

    /**
     * D1 守卫：编辑脚本与纸面配色已外置为类路径资源
     * （{@code editor-script.js} / {@code editor-paper.css}），
     * 占位符仍由 {@code INJECTED_*_ID} 代入。
     *
     * <p>这条守的是「资源真的被读进来了」——资源路径写错、文件漏提交、被截断，
     * 都会让可视化编辑器整条链路失灵，而只断言 {@code <script} 存在是看不出来的。
     */
    @Test
    @DisplayName("编辑脚本与纸面配色来自外置资源，id 占位符由常量代入")
    void editorAssetsComeFromClasspathResources() {
        String doc = PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT);

        // 脚本本体（外置文件）确实在文档里
        assertTrue(doc.contains("var INJECTED_IDS = ['" + PreviewHtml.INJECTED_STYLE_ID + "', '"
                        + PreviewHtml.INJECTED_BASE_ID + "', '" + PreviewHtml.INJECTED_SCRIPT_ID + "'];"),
                "三个 id 占位符必须由 INJECTED_*_ID 常量代入：" + doc.substring(0, 200));
        for (String marker : new String[]{"tidyHeadWhitespace", "rescueStrayNodes",
                "pruneEmptyInline", "pruneEmptyLists", "epubraSerialize", "safeUrl"}) {
            assertTrue(doc.contains(marker), "外置脚本应包含 " + marker + "，疑似资源读空或截断");
        }
        // 纸面配色（外置 css）确实生效
        assertTrue(doc.contains("html, body { background: #faf9f5 !important;"),
                "editor-paper.css 未被读入");
        assertTrue(doc.contains("</style>"), doc.substring(Math.max(0, doc.length() - 120)));
    }
}
