package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具条的富文本操作在真实 WebView 里的行为契约。
 *
 * <p>这一层不能靠字符串断言覆盖：{@code window.epubraFormat} 是对 XML DOM 做
 * Range 操作，只有把文档真的塞进 WebView（{@code application/xhtml+xml}）才验证得了
 * 两件事——(1) 命令确实改了 DOM，(2) 序列化结果仍是<b>合法 XHTML</b>。
 *
 * <p>刻意不用 {@code document.execCommand}，因此这里也不依赖页面获得焦点：
 * 选区由脚本手动建立。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code StatusProgressUiTest} 的说明。
 */
class VisualEditorFormatTest {

    private static final String FULL_DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<head><title>第一章</title></head>"
            + "<body><h1>标题</h1><p>正文</p></body></html>";

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static WebView webView;

    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        runOnFx(() -> {
            webView = new WebView();
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(webView), 640, 480));
            stage.show();
        });
    }

    @BeforeEach
    void loadEditableDocument() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        runOnFx(() -> {
            WebEngine engine = webView.getEngine();
            engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            engine.loadContent(PreviewHtml.editableDocument(FULL_DOC, Theme.LIGHT),
                    "application/xhtml+xml");
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS), "可编辑文档加载超时");
    }

    @AfterEach
    void dropSelection() throws Exception {
        // 选区是全局状态，测试之间互不残留
        runOnFx(() -> webView.getEngine().executeScript(
                "(function () { var s = window.getSelection(); if (s) { s.removeAllRanges(); } return true; })()"));
    }

    @Test
    @Timeout(60)
    @DisplayName("加粗把选中内容包成 <strong>，且序列化后仍是合法 XHTML")
    void boldWrapsSelectionIntoStrong() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")),
                "在正文选区上执行加粗应成功");

        String xhtml = serialized();
        assertTrue(xhtml.contains("<strong"), "应产出 <strong>，而不是 <b> 或行内 style：" + xhtml);
        assertTrue(xhtml.contains("正文"), "原文本不能丢：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("斜体包成 <em>")
    void italicWrapsSelectionIntoEm() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<em"), "应产出 <em>，而不是 <i>：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("标题把光标所在块转成 <h2>（不新增空块）")
    void headingConvertsCurrentBlock() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('heading')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<h2"), "段落应变成 h2：" + xhtml);
        assertTrue(xhtml.contains("正文"), "块的文字内容要跟着搬过去：" + xhtml);
        // 原来的 <p> 应当被换掉而不是并列多出一个
        assertFalse(xhtml.contains("<p>正文</p>"), "原段落不应残留：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("列表把光标所在块转成 <ul><li>")
    void listConvertsCurrentBlock() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<ul"), "应包出 ul：" + xhtml);
        assertTrue(xhtml.contains("<li"), "应包出 li：" + xhtml);
        assertTrue(xhtml.contains("正文"), "文字内容要保留：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("列表状态再点一次退回段落（可逆）")
    void listTogglesBackToParagraph() throws Exception {
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "已在列表里再点列表应退回段落");

        String xhtml = serialized();
        assertFalse(xhtml.contains("<ul"), "退回后不应残留列表：" + xhtml);
        assertTrue(xhtml.contains("<p"), "应恢复成段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("插入图片：img 元素落在光标处，序列化为自闭合标签")
    void insertImageAtCaret() throws Exception {
        caretIntoParagraph();
        String tag = "<img src=\"images/cover.png\" alt=\"封面\"/>";
        assertTrue(Boolean.TRUE.equals(insertHtml(tag)), "在光标处插入 img 应成功");

        String xhtml = serialized();
        assertTrue(xhtml.contains("images/cover.png"), "图片地址应在正文里：" + xhtml);
        assertTrue(xhtml.matches("(?s).*<img[^>]*\\s*/>.*"),
                "img 必须自闭合，否则回写正文会校验失败：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("未就绪/未知命令不改变文档，也不抛异常")
    void unknownCommandIsNoop() throws Exception {
        selectParagraphContents();
        String before = serialized();
        assertFalse(Boolean.TRUE.equals(runScript("window.epubraFormat('nonsense')")),
                "未知命令应返回 false，让 Java 侧走降级路径");
        assertTrue(before.equals(serialized()), "失败的命令不应改动文档");
    }

    @Test
    @Timeout(60)
    @DisplayName("下划线 / 删除线 / 行内代码分别产出 <u> / <del> / <code>")
    void inlineCommandsProduceSemanticTags() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('underline')")));
        assertTrue(serialized().matches("(?s).*<u[\\s>].*"),
                "下划线应产出 <u>（不是 <ul>）：" + serialized());
        assertWellFormedXhtml(serialized());

        loadEditableDocument();
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('strike')")));
        assertTrue(serialized().contains("<del"), "删除线应产出 <del> 而非 <s>/<strike>：" + serialized());
        assertWellFormedXhtml(serialized());

        loadEditableDocument();
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('code')")));
        assertTrue(serialized().contains("<code"), "行内代码应产出 <code>：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("引用在段落与 <blockquote> 之间可逆切换")
    void quoteTogglesWithParagraph() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")));
        assertTrue(serialized().contains("<blockquote"), "应变成引用：" + serialized());
        assertWellFormedXhtml(serialized());

        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")));
        String xhtml = serialized();
        assertFalse(xhtml.contains("<blockquote"), "再点一次应退回段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("分隔线插在段落之后并补一个空段落，序列化为 <hr/>")
    void ruleInsertsHrAndTrailingParagraph() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('rule')")));

        String xhtml = serialized();
        // XMLSerializer 会写成 <hr />（带空格），两种写法都合法，别只认一种
        assertTrue(xhtml.matches("(?s).*<hr\\s*/>.*"), "分隔线必须自闭合：" + xhtml);
        // 分隔线之后要有落点，否则光标无处可去
        assertTrue(xhtml.indexOf("<hr") < xhtml.lastIndexOf("<p"), "hr 之后应有空段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("链接包成 <a href>；javascript: 这类地址被拒绝且不改文档")
    void linkWrapsSelectionAndRejectsUnsafeUrl() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(
                runScript("window.epubraFormat('link', 'https://example.com/a?b=1')")));
        String xhtml = serialized();
        assertTrue(xhtml.contains("<a href=\"https://example.com/a?b=1\""),
                "选区应被包成带 href 的链接：" + xhtml);
        assertWellFormedXhtml(xhtml);

        loadEditableDocument();
        selectParagraphContents();
        String before = serialized();
        assertFalse(Boolean.TRUE.equals(
                        runScript("window.epubraFormat('link', 'javascript:alert(1)')")),
                "javascript: 地址必须被拒绝");
        assertTrue(before.equals(serialized()), "被拒绝的链接不应改动文档");
    }

    @Test
    @Timeout(60)
    @DisplayName("光标状态查询：报告当前生效的格式名，供工具条点亮")
    void queryReportsActiveFormats() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraQuery().indexOf('paragraph') >= 0")),
                "光标在段落里应报告 paragraph");

        runScript("window.epubraFormat('bold')");
        Object active = runScript("window.epubraQuery()");
        assertTrue(active instanceof String && ((String) active).contains("bold"),
                "刚加粗过，应报告 bold，实际：" + active);
        assertTrue(((String) active).contains("paragraph"),
                "块级状态也应一起报告，实际：" + active);
    }

    @Test
    @Timeout(60)
    @DisplayName("粘贴净化：剥掉危险标签/事件属性/内联样式，b 归并成 strong")
    void pasteSanitizerKeepsOnlyWhitelistedMarkup() throws Exception {
        String dirty = "<div style=\"color:red\"><b>粗体</b>"
                + "<script>evil()</script>"
                + "<span onclick=\"steal()\">普通</span></div>"
                + "<p class=\"x\" style=\"margin:0\">段落</p>"
                + "<img src=\"a.png\" onerror=\"boom()\"/>"
                + "<a href=\"javascript:alert(1)\">坏链</a>";
        Object cleaned = sanitize(dirty);
        assertTrue(cleaned instanceof String, "净化器应返回序列化后的片段");
        String out = (String) cleaned;

        assertFalse(out.contains("<script"), "脚本必须被丢掉：" + out);
        assertFalse(out.contains("onclick"), "事件属性必须被丢掉：" + out);
        assertFalse(out.contains("onerror"), "事件属性必须被丢掉：" + out);
        assertFalse(out.contains("style="), "内联样式必须被丢掉：" + out);
        assertFalse(out.contains("class="), "外来 class 必须被丢掉：" + out);
        assertTrue(out.contains("<strong>粗体</strong>"), "b 应归并成 strong：" + out);
        assertTrue(out.contains("普通"), "未知标签要拆掉但保留文字：" + out);
        assertTrue(out.contains("src=\"a.png\""), "img 的 src 应保留：" + out);
        assertFalse(out.contains("javascript:"), "危险 href 必须被丢掉：" + out);
        assertTrue(out.contains("段落"), "段落文字不能丢：" + out);
    }

    @Test
    @Timeout(60)
    @DisplayName("行内格式（加粗/斜体/下划线/删除线）再点一次取消包裹，不再嵌套")
    void inlineFormatsToggleOff() throws Exception {
        String[][] cases = {{"bold", "strong"}, {"italic", "em"},
                {"underline", "u"}, {"strike", "del"}};
        for (String[] c : cases) {
            loadEditableDocument();
            selectParagraphContents();
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('" + c[0] + "')")),
                    c[0] + " 第一次应用应成功");
            Object active = runScript("window.epubraQuery()");
            assertTrue(active instanceof String && ((String) active).contains(c[0]),
                    "刚应用 " + c[0] + " 应报告激活态（工具条靠它点亮），实际：" + active);

            // wrapInline 之后选区落在包裹层内容上，第二次应用走的是「拆层」路径
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('" + c[0] + "')")),
                    c[0] + " 第二次应用应取消包裹");

            String xhtml = serialized();
            assertFalse(bodyHasTag(c[1]),
                    c[0] + " 取消后不应残留 <" + c[1] + ">：" + xhtml);
            assertTrue(xhtml.contains("正文"), "取消包裹不能丢文字：" + xhtml);
            assertWellFormedXhtml(xhtml);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("编号列表：ol 产出 <ol> 并点亮 ol 态；再点退回段落；ul 可直接换型")
    void orderedListConvertsAndToggles() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")));
        assertTrue(bodyHasTag("ol"), "应包出 ol：" + serialized());
        assertFalse(bodyHasTag("ul"), "点编号列表不应误产出 ul：" + serialized());
        assertWellFormedXhtml(serialized());
        Object active = runScript("window.epubraQuery()");
        assertTrue(active instanceof String && ((String) active).contains("ol"),
                "有序列表应报告 ol，实际：" + active);

        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")),
                "已在 ol 里再点一次应退回段落");
        assertFalse(bodyHasTag("ol"), "退回后不应残留 ol：" + serialized());
        assertTrue(serialized().contains("<p"), "应恢复成段落：" + serialized());
        assertWellFormedXhtml(serialized());

        // ul → ol 直接换型，不必先退回段落
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")),
                "在 ul 上点编号列表应换型为 ol");
        assertTrue(bodyHasTag("ol"), "应换成 ol：" + serialized());
        assertFalse(bodyHasTag("ul"), "换型后不应残留 ul：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("列表内 Tab 缩进成子列表，Shift+Tab 降级还原")
    void listTabIndentsAndShiftTabOutdents() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")));
        // 造第二个列表项；光标收进它，Tab 才有「前一项」可挂子列表（第一项缩不了）
        runScript("(function () {"
                + " var ul = document.body.querySelector('ul');"
                + " var li = document.createElementNS('http://www.w3.org/1999/xhtml', 'li');"
                + " li.textContent = '第二条';"
                + " ul.appendChild(li);"
                + " var r = document.createRange(); r.selectNodeContents(li); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");

        // 合成 Tab 键盘事件，走 document 上的捕获监听器
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', bubbles: true, cancelable: true}))");
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                + " var sub = document.querySelector('ul > li > ul > li');"
                + " return !!sub && sub.textContent === '第二条'; })()")),
                "Tab 应把当前项缩进成前一项内的子列表：" + serialized());
        assertWellFormedXhtml(serialized());

        // 缩进后光标仍留在被挪动的 li 里，Shift+Tab 直接降级
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', shiftKey: true, bubbles: true, cancelable: true}))");
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                + " var lis = document.querySelectorAll('ul > li');"
                + " return lis.length === 2 && !document.querySelector('ul li ul'); })()")),
                "Shift+Tab 应把子列表降级回顶层并删掉空子列表：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("取消链接：拆掉 <a> 保留文字；queryLink 可回填当前链接地址")
    void unlinkRemovesAnchorKeepsText() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(
                runScript("window.epubraFormat('link', 'https://example.com/x')")));
        Object href = runScript("window.epubraQueryLink()");
        assertTrue(String.valueOf(href).equals("https://example.com/x"),
                "包裹后选区在链接内，queryLink 应回填地址，实际：" + href);

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('unlink')")),
                "取消链接应成功");
        String xhtml = serialized();
        assertFalse(bodyHasTag("a"), "取消后不应残留 <a>：" + xhtml);
        assertTrue(xhtml.contains("正文"), "链接里的文字要保留：" + xhtml);
        assertTrue(String.valueOf(runScript("window.epubraQueryLink()")).isEmpty(),
                "取消后 queryLink 应为空串");
        assertWellFormedXhtml(xhtml);

        // 光标不在链接里时取消链接是无操作
        loadEditableDocument();
        selectParagraphContents();
        assertFalse(Boolean.TRUE.equals(runScript("window.epubraFormat('unlink')")),
                "不在链接内时 unlink 应返回 false");
    }

    // ------------------------------------------------------------------ 脚本与断言助手

    /** 选中正文段落里的文字（模拟用户划选一段后点工具条）。 */
    private void selectParagraphContents() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange();"
                + " r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /** 把光标收进正文块（模拟用户点进某一句话里）。块可能是段落、列表项、引用或标题。 */
    private void caretIntoParagraph() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p, li, blockquote, h2');"
                + " var r = document.createRange();"
                + " r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /** 走 Java 侧同样的通道：片段经 window 上的临时成员传入，不拼脚本字符串。 */
    private Object insertHtml(String html) throws Exception {
        return withMember("__testHtml", html, "window.epubraInsertHtml(window.__testHtml)");
    }

    /** 同上，调粘贴净化器（净化入口同样不把 HTML 拼进脚本文本）。 */
    private Object sanitize(String html) throws Exception {
        return withMember("__testHtml", html, "window.epubraSanitize(window.__testHtml)");
    }

    private Object withMember(String member, String value, String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> {
            WebEngine engine = webView.getEngine();
            netscape.javascript.JSObject window =
                    (netscape.javascript.JSObject) engine.executeScript("window");
            window.setMember(member, value);
            out.set(engine.executeScript(script));
            window.setMember(member, null);
        });
        return out.get();
    }

    private String serialized() throws Exception {
        Object result = runScript("window.epubraSerialize()");
        assertTrue(result instanceof String, "serialize 应返回字符串，实际：" + result);
        return (String) result;
    }

    /**
     * 只查 body 里有没有某标签。负向断言必须走这里而不是对整篇序列化文本做
     * 子串匹配——head 里内嵌的编辑器脚本源码本身含有 &lt;strong&gt;、&lt;a&gt; 等字样，
     * 会把「已取消」误判成「仍残留」。
     */
    private boolean bodyHasTag(String tag) throws Exception {
        return Boolean.TRUE.equals(runScript("!!document.body.querySelector('" + tag + "')"));
    }

    private static void assertWellFormedXhtml(String xhtml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xhtml)));
            assertTrue(doc.getDocumentElement() != null, "解析结果应有根元素");
        } catch (Exception failed) {
            throw new AssertionError("序列化结果不是合法 XML/XHTML：" + failed.getMessage()
                    + "\n---\n" + xhtml);
        }
    }

    private static Object runScript(String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> out.set(webView.getEngine().executeScript(script)));
        return out.get();
    }

    private static void runOnFx(FxTask r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.runWithException();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
