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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        loadEditable(FULL_DOC);
    }

    /** 把任意章节塞进 WebView 当可编辑文档（跨周期场景需要换不同的源文档）。 */
    private void loadEditable(String xhtml) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        runOnFx(() -> {
            WebEngine engine = webView.getEngine();
            engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            engine.loadContent(PreviewHtml.editableDocument(xhtml, Theme.LIGHT),
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
    @DisplayName("无选区点行内格式按钮不再插入空的 <strong>/<em>/<code> 占位标签")
    void collapsedCaretFormattingDoesNotInsertEmptyTags() throws Exception {
        caretIntoParagraph();
        // 光标收进块内后确保没有选区（collapse 成光标）
        runScript("(function () {"
                + " var s = window.getSelection();"
                + " if (s.rangeCount) { s.getRangeAt(0).collapse(true); }"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")),
                "光标态命令按「有意不作为」处理（返回 true，避免 Java 侧 fallback 去源码区插片段）");
        runScript("window.epubraFormat('italic')");
        runScript("window.epubraFormat('code')");

        String xhtml = serialized();
        assertFalse(bodyHasTag("strong"), "无选区不该出现空 <strong>：" + xhtml);
        assertFalse(bodyHasTag("em"), "无选区不该出现空 <em>：" + xhtml);
        assertFalse(bodyHasTag("code"), "无选区不该出现空 <code>：" + xhtml);
        assertTrue(xhtml.contains("正文"), "原有文字不能被动：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("光标落在斜体文字内点「斜体」仍取消整段斜体（#54 保留行为）")
    void caretInsideEmStillTogglesOff() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        // 光标收进 <em> 内部
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")),
                "光标在格式内点按钮 = 取消（返回 true）");

        assertFalse(bodyHasTag("em"), "光标在 <em> 内点「斜体」应拆掉包裹：" + serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("无选区插入链接不再产生空 <a href>")
    void collapsedCaretLinkDoesNotInsertEmptyAnchor() throws Exception {
        caretIntoParagraph();
        runScript("(function () {"
                + " var s = window.getSelection();"
                + " if (s.rangeCount) { s.getRangeAt(0).collapse(true); }"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(withMember("__testHref", "https://example.com",
                        "window.epubraFormat('link', window.__testHref)")),
                "光标态插链接同样按「有意不作为」处理");
        String xhtml = serialized();
        assertFalse(bodyHasTag("a"), "无选区不该出现空 <a href>：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("序列化剔除空强调标签（含空白壳与嵌套空壳），带图片等内容的保留")
    void serializePrunesEmptyInlineTags() throws Exception {
        // 直接在 DOM 里注入各类空壳（模拟历史文档 / 粘贴带进来的），验证回写兜底净化
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " p.appendChild(document.createElementNS('http://www.w3.org/1999/xhtml', 'strong'));"
                + " var emBlank = document.createElementNS('http://www.w3.org/1999/xhtml', 'em');"
                + " emBlank.appendChild(document.createTextNode('  '));"
                + " p.appendChild(emBlank);"
                + " var shell = document.createElementNS('http://www.w3.org/1999/xhtml', 'strong');"
                + " shell.appendChild(document.createElementNS('http://www.w3.org/1999/xhtml', 'em'));"
                + " p.appendChild(shell);"
                + " var alias = document.createElementNS('http://www.w3.org/1999/xhtml', 'b');"
                + " p.appendChild(alias);"
                + " var withImg = document.createElementNS('http://www.w3.org/1999/xhtml', 'em');"
                + " var img = document.createElementNS('http://www.w3.org/1999/xhtml', 'img');"
                + " img.setAttribute('src', 'x.png');"
                + " withImg.appendChild(img);"
                + " p.appendChild(withImg);"
                + " return true; })()");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertFalse(body.contains("<strong"), "空 <strong> 与嵌套空壳都应被剔除：" + body);
        assertFalse(body.contains("<b>"), "别名 <b> 的空壳也应被剔除：" + body);
        assertTrue(body.contains("<em><img"), "带 <img> 内容的 <em> 必须保留：" + body);
        assertTrue(body.contains("x.png"), "图片本身不能被动：" + body);
        assertWellFormedXhtml(xhtml);
    }

    /** 取序列化输出的 body 区间——head 里内嵌的编辑脚本源码会撞断言，不能整篇 contains。 */
    private String bodyOf(String xhtml) {
        int start = xhtml.indexOf("<body");
        int end = xhtml.indexOf("</body>");
        assertTrue(start >= 0 && end > start, "序列化输出缺 body：" + xhtml);
        return xhtml.substring(start, end);
    }

    @Test
    @Timeout(60)
    @DisplayName("列表/段落多次往返结构始终合法，内容不丢")
    void listRoundTripsKeepStructureLegal() throws Exception {
        caretIntoParagraph();
        for (int round = 0; round < 3; round++) {
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                    "第 " + (round + 1) + " 轮转列表应成功");
            String asList = serialized();
            assertWellFormedXhtml(asList);
            assertFalse(asList.contains("<p><ul") || asList.contains("<p><ol"),
                    "列表内不应出现段落嵌套列表的非法结构：" + bodyOf(asList));

            caretIntoParagraph();
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                    "第 " + (round + 1) + " 轮退回段落应成功");
            String asParagraph = serialized();
            assertWellFormedXhtml(asParagraph);
            assertTrue(asParagraph.contains("正文"), "往返后文字不能丢：" + bodyOf(asParagraph));
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("带子列表的列表项退回段落：子列表提出去，绝不产生 p>ul 非法嵌套")
    void indentedSublistExitsToParagraphWithoutIllegalNesting() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('list')");
        // 模拟 Tab 缩进结果：外层 li 里有一个子列表
        runScript("(function () {"
                + " var li = document.body.querySelector('li');"
                + " var sub = document.createElementNS('http://www.w3.org/1999/xhtml', 'ul');"
                + " var subLi = document.createElementNS('http://www.w3.org/1999/xhtml', 'li');"
                + " subLi.appendChild(document.createTextNode('子项'));"
                + " sub.appendChild(subLi);"
                + " li.appendChild(sub);"
                + " return true; })()");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "带子列表的列表项退回段落应成功");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<p><ul") && body.contains("</ul></p>"),
                "绝不能产生 p>ul 非法嵌套：" + body);
        assertTrue(body.contains("<ul><li>子项</li></ul>"),
                "子列表应作为独立列表保留：" + body);
        assertTrue(body.contains("正文"), "外层文字不能丢：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("引用块内含多个段落时退回段落：块级孩子提出去，绝不产生 p>p")
    void quoteWithBlocksExitsToParagraphWithoutIllegalNesting() throws Exception {
        // 直接构造引用块含两个段落的形态
        runScript("(function () {"
                + " var body = document.body;"
                + " var p0 = body.querySelector('p');"
                + " var bq = document.createElementNS('http://www.w3.org/1999/xhtml', 'blockquote');"
                + " var p1 = document.createElementNS('http://www.w3.org/1999/xhtml', 'p');"
                + " p1.appendChild(document.createTextNode('引文一'));"
                + " var p2 = document.createElementNS('http://www.w3.org/1999/xhtml', 'p');"
                + " p2.appendChild(document.createTextNode('引文二'));"
                + " bq.appendChild(p1); bq.appendChild(p2);"
                + " body.replaceChild(bq, p0);"
                + " return true; })()");
        // 光标落进引用块内
        runScript("(function () {"
                + " var p = document.body.querySelector('blockquote p');"
                + " var r = document.createRange(); r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")),
                "引用块再点「引用」应退回段落");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<p><p>"), "绝不能产生 p>p 非法嵌套：" + body);
        assertTrue(body.contains("引文一") && body.contains("引文二"),
                "两段引文都要保留：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整列表被选中再点「列表」：所有 li 退回成段（绝不 fallback 往源码区插骨架）")
    void wholeListSelectionTogglesToParagraphs() throws Exception {
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        // 整列表选中（selectNodeContents(ul)：起点就是列表本身）
        runScript("(function () {"
                + " var ul = document.body.querySelector('ul');"
                + " var r = document.createRange(); r.selectNodeContents(ul);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "整列表选中点「列表」应退回段落（返回 true，触发源码 fallback 会把内容搞乱）");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<ul"), "整列表退回后不应残留列表：" + body);
        assertTrue(body.contains("正文"), "文字不能丢：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("body 被清空后点格式按钮：直接在 body 内建块，绝不把标签打到 body 外或插去源码区")
    void emptyBodyFormattingCreatesBlockInsideBody() throws Exception {
        // 清空 body，光标落在 body 本身（topBlock 的越界形态）
        runScript("(function () {"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var r = document.createRange(); r.selectNodeContents(b); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "空 body 上点「列表」应建列表（返回 true，触发源码 fallback 会把骨架插到源码区）");
        // 落点看实时 DOM：#58 要守的是「块建在 body 内、不打到 body 外」。而空列表按 #60
        // 不写进书——所以序列化结果里反而**不该**出现 <ul>。
        assertTrue(bodyHasTag("ul"), "body 内应出现列表（实时 DOM）：" + bodyOf(serialized()));
        assertFalse(bodyOf(serialized()).contains("<ul"),
                "只有空壳的列表不该被回写进书（#60）：" + bodyOf(serialized()));
        assertWellFormedXhtml(serialized());

        // 再清空，试段落按钮
        runScript("(function () {"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var r = document.createRange(); r.selectNodeContents(b); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('paragraph')")),
                "空 body 上点「段落」应建段落");
        String paraBody = bodyOf(serialized());
        assertTrue(paraBody.contains("<p"), "body 内应出现段落：" + paraBody);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("历史损坏残留：body 之外的元素在回写时收编回 body 内")
    void strayNodesOutsideBodyRescuedOnSerialize() throws Exception {
        // 模拟历史损坏：把段落挂到 body 之外（html 层）
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " document.body.parentNode.appendChild(p);"
                + " return true; })()");
        assertFalse(bodyOf(serialized()).isEmpty(), "序列化输出应有 body");

        String body = bodyOf(serialized());
        assertTrue(body.contains("<p>正文</p>"), "body 外的段落应在回写时收编回 body：" + body);
        assertWellFormedXhtml(serialized());
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

    @Test
    @Timeout(60)
    @DisplayName("回写序列化不携带编辑脚本——正文里不应混入任何 <script>")
    void serializedOutputCarriesNoEditorScript() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")));
        String xhtml = serialized();

        assertTrue(xhtml.contains("<strong"), "命令本身要生效：" + xhtml);
        assertFalse(xhtml.contains("<script"),
                "编辑脚本必须从回写结果里剥掉，否则每次编辑都往书里灌几百行 JS：" + xhtml);
        assertFalse(xhtml.contains("epubraFormat"), xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("行内语义标签在画布上有视觉样式：em 斜体 / strong 加粗 / u 下划线")
    void inlineSemanticTagsCarryVisualStyles() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " p.innerHTML = '前<em>斜</em>中<strong>粗</strong>后<u>下</u>';"
                + " return true; })()");
        Object report = runScript("(function () {"
                + " function styleOf(sel, prop) {"
                + "   var el = document.body.querySelector(sel);"
                + "   return el ? document.defaultView.getComputedStyle(el)[prop] : 'MISSING';"
                + " }"
                + " return styleOf('em', 'fontStyle') + '|' + styleOf('strong', 'fontWeight')"
                + "      + '|' + styleOf('u', 'textDecorationLine');"
                + "})()");
        // computed style 只能证明 CSS 链路通（引擎不做合成斜体，中文斜体的「可见」
        // 由 Theme.previewStyleCss 的 em 字体栈负责：西文真斜体字面 + 楷体替代）
        assertEquals("italic|700|underline", String.valueOf(report),
                "行内语义标签的 computed style 被破坏，画布上强调格式会隐形");
    }

    @Test
    @Timeout(60)
    @DisplayName("斜体后光标落回 / 重新选中，epubraQuery 与选区上报流都仍报 italic（工具条点亮的前置契约）")
    void italicStillReportedAfterReactivation() throws Exception {
        // 装 stub 桥记录 onSelectionChanged 上报流
        runScript("window.__selReports = [];"
                + "window.epubraBridge = {"
                + " onSelectionChanged: function (q) { window.__selReports.push(q); },"
                + " onEdited: function () {}, onUndo: function () {}, onRedo: function () {} };");

        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        runScript("document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}))");

        // 用户操作模拟：点别处收起选区，再把光标落回斜体文字上
        runScript("(function () {"
                + " var s = window.getSelection(); s.removeAllRanges();"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        Object caretQuery = runScript("window.epubraQuery()");
        assertTrue(String.valueOf(caretQuery).contains("italic"),
                "光标落在斜体文字上时 epubraQuery 必须报 italic，实际：" + caretQuery);

        // 再模拟拖选斜体文字
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        Object selectQuery = runScript("window.epubraQuery()");
        Object reports = runScript("window.__selReports.join(' || ')");
        assertTrue(String.valueOf(selectQuery).contains("italic"),
                "重新选中斜体文字时 epubraQuery 必须报 italic，实际：" + selectQuery);
        assertTrue(String.valueOf(reports).contains("italic"),
                "mouseup / selectionchange 上报流必须包含 italic，实际：" + reports);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区内只要含有斜体文字（部分覆盖）也点亮 italic")
    void partialSelectionAcrossEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        // p 变成 <p><em>正文</em></p>；在包裹外补一段文字，构造部分覆盖的选区
        runScript("document.body.querySelector('p').appendChild(document.createTextNode('续写'))");

        // 选区：斜体文字的前半段 + 包裹外的「续」字——混有格式外文字也应点亮
        Object q = runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var tail = em.nextSibling;"
                + " var r = document.createRange();"
                + " r.setStart(em.firstChild, 0); r.setEnd(tail, 1);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "选区内含有斜体文字就应报 italic（亮 ⇔ 可移除），实际：" + q);
    }

    @Test
    @Timeout(60)
    @DisplayName("部分覆盖选区点「斜体」= 移除选区内相交的 <em>（整标签拆掉，文字保留）")
    void partialSelectionTogglesItalicOffRemovesTag() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        runScript("document.body.querySelector('p').appendChild(document.createTextNode('续写'))");

        // 部分覆盖选区（斜体前半段 + 包裹外的字），点「斜体」→ 移除 <em>
        assertTrue(Boolean.TRUE.equals(runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var tail = em.nextSibling;"
                + " var r = document.createRange();"
                + " r.setStart(em.firstChild, 0); r.setEnd(tail, 1);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraFormat('italic'); })()")),
                "点亮状态下点击应执行移除（返回 true）");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<em>"), "选区内相交的 <em> 应被移除：" + body);
        assertTrue(body.contains("正文") && body.contains("续写"),
                "文字一律保留：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整段选中完全覆盖斜体包裹（三击选段形态）也点亮 italic")
    void fullParagraphSelectionCoveringEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");

        // 三击选段形态：选区起点/终点都在 p 上（包裹之外），但语义上完整覆盖了 em。
        // #54 用户实测「把斜体字选中后按钮不亮」正是这种形态。
        Object q = runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange(); r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "完整覆盖斜体包裹的整段选区应报 italic，实际：" + q);
    }

    @Test
    @Timeout(60)
    @DisplayName("整段选中后点「斜体」应拆掉包裹（toggle off），而不是再嵌套一层")
    void fullParagraphSelectionTogglesItalicOff() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        assertTrue(Boolean.TRUE.equals(runScript("!!document.body.querySelector('em')")),
                "前置确认：斜体已设置");

        // 再次整段选中（起点在包裹外），点「斜体」= 取消
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")),
                "整段选中已斜体内容后点「斜体」应执行取消");
        String serialized = serialized();
        assertFalse(bodyHasTag("em"),
                "取消后不应再有 <em>，实际：" + serialized);
        assertFalse(serialized.contains("<em><em>"),
                "绝不能嵌套包裹（取消被实现成了再加一层），实际：" + serialized);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区两端都在斜体内才点亮 italic")
    void fullSelectionInsideEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");

        Object q = runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "两端都在 em 内的选区应报 italic，实际：" + q);
    }

    @Test
    @Timeout(120)
    @DisplayName("head 内的空行不随「加载→回写」周期累积（#59）")
    void headWhitespaceDoesNotAccumulateAcrossRoundTrips() throws Exception {
        String chapter = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head>\n  <title>第一章</title>\n  <meta charset=\"utf-8\"/>\n</head>"
                + "<body>\n  <p>正文</p>\n</body></html>";

        int baseline = -1;
        for (int round = 1; round <= 4; round++) {
            loadEditable(chapter);
            chapter = serialized();
            int blanks = headNewlines(chapter);
            if (round == 1) {
                baseline = blanks;
            } else {
                assertEquals(baseline, blanks,
                        "第 " + round + " 轮回写后 head 换行数应稳定在 " + baseline
                                + "，实际 " + blanks + "：\n" + chapter);
            }
            assertWellFormedXhtml(chapter);
        }
        assertTrue(chapter.contains("<p>正文</p>"), "正文内容应原样保留：" + chapter);
    }

    @Test
    @Timeout(60)
    @DisplayName("空列表（零 li / li 空 / 嵌套空子列表）绝不回写进书，有内容的列表不受影响")
    void emptyListsAreNeverWrittenBack() throws Exception {
        // ① 真实可达路径：建列表后把列表项内容删空
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript("(function () { var li = document.body.querySelector('li');"
                + " while (li.firstChild) { li.removeChild(li.firstChild); } return true; })()");
        assertTrue(bodyHasTag("ul"), "前置：实时 DOM 里列表还在（编辑中的落脚点）");
        assertFalse(bodyOf(serialized()).contains("<ul"),
                "列表项被清空后，空列表不该回写：" + bodyOf(serialized()));

        // ② 零 li 的空列表（历史损坏 / 引擎删除残留），同批正文不能受牵连
        runScript("(function () {"
                + " var ns = 'http://www.w3.org/1999/xhtml';"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " b.appendChild(document.createElementNS(ns, 'ul'));"
                + " b.appendChild(document.createElementNS(ns, 'ol'));"
                + " var p = document.createElementNS(ns, 'p');"
                + " p.appendChild(document.createTextNode('正文'));"
                + " b.appendChild(p);"
                + " return true; })()");
        String bare = bodyOf(serialized());
        assertFalse(bare.contains("<ul"), "零 li 的空 <ul> 不该回写：" + bare);
        assertFalse(bare.contains("<ol"), "零 li 的空 <ol> 不该回写：" + bare);
        assertTrue(bare.contains("<p>正文</p>"), "正文不能被净化牵连：" + bare);

        // ③ 嵌套：内层空子列表删掉，外层（有文字）必须保留
        runScript("(function () {"
                + " var ns = 'http://www.w3.org/1999/xhtml';"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var ul = document.createElementNS(ns, 'ul');"
                + " var li = document.createElementNS(ns, 'li');"
                + " li.appendChild(document.createTextNode('有条目'));"
                + " li.appendChild(document.createElementNS(ns, 'ul'));"
                + " ul.appendChild(li);"
                + " b.appendChild(ul);"
                + " return true; })()");
        String nested = bodyOf(serialized());
        assertTrue(nested.contains("有条目"), "有内容的列表不能误删：" + nested);
        assertEquals(1, nested.split("<ul", -1).length - 1,
                "只该剩外层列表（内层空子列表已净化）：" + nested);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整列表选中点「段落」：退回段落且返回 true（绝不触发源码 fallback）")
    void paragraphCommandOnWholeListSelectionDoesNotFallBackToSource() throws Exception {
        String selectWholeList = "(function () {"
                + " var L = document.body.querySelector('ul,ol');"
                + " var r = document.createRange(); r.selectNodeContents(L);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()";

        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript(selectWholeList);
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('paragraph')")),
                "整列表选中点「段落」必须返回 true——false 会让 Java 侧往源码区插 <p></p>，"
                        + "切 tab 时用源码覆盖章节，可视化改动全丢");
        String body = bodyOf(serialized());
        assertFalse(body.contains("<ul"), "整列表应退回段落：" + body);
        assertTrue(body.contains("正文"), "文字不能丢：" + body);

        // 标题对整列表没有明确语义：按「有意不作为」处理，但同样必须返回 true
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript(selectWholeList);
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('heading')")),
                "整列表选中点「标题」也必须返回 true——有意不作为，而不是触发源码 fallback");
        assertTrue(bodyOf(serialized()).contains("正文"),
                "「不作为」不等于丢内容：" + bodyOf(serialized()));
        assertWellFormedXhtml(serialized());
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

    /**
     * 数 {@code <head>} 区间里的换行字符。空行一旦跨周期累积，这个数就会随
     * 「加载→回写」轮次线性增长——是 #59 最直接的可观测指标。
     */
    private static int headNewlines(String xhtml) {
        int start = xhtml.indexOf("<head");
        int end = xhtml.indexOf("</head>");
        if (start < 0 || end < start) {
            return -1;
        }
        int count = 0;
        for (int i = start; i < end; i++) {
            if (xhtml.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
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
