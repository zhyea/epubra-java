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
        assertTrue(xhtml.contains("/>"), "img 必须自闭合，否则回写正文会校验失败：" + xhtml);
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

    /** 把光标收进正文段落（模拟用户点进某一句话里）。 */
    private void caretIntoParagraph() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p, li');"
                + " var r = document.createRange();"
                + " r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /** 走 Java 侧同样的通道：片段经 window 上的临时成员传入，不拼脚本字符串。 */
    private Object insertHtml(String html) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> {
            WebEngine engine = webView.getEngine();
            netscape.javascript.JSObject window =
                    (netscape.javascript.JSObject) engine.executeScript("window");
            window.setMember("__testHtml", html);
            out.set(engine.executeScript("window.epubraInsertHtml(window.__testHtml)"));
            window.setMember("__testHtml", null);
        });
        return out.get();
    }

    private String serialized() throws Exception {
        Object result = runScript("window.epubraSerialize()");
        assertTrue(result instanceof String, "serialize 应返回字符串，实际：" + result);
        return (String) result;
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
