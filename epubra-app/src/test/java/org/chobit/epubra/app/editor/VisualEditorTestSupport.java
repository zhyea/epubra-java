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
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化编辑器「真实 WebView」测试的共享脚手架。
 *
 * <p>这套测试不能靠字符串断言覆盖：{@code window.epubraFormat} 是对 XML DOM 做 Range 操作，
 * 只有把文档真的塞进 WebView（{@code application/xhtml+xml}）才验证得了两件事——
 * (1) 命令确实改了 DOM，(2) 序列化结果仍是<b>合法 XHTML</b>。
 *
 * <p>刻意不用 {@code document.execCommand}，因此也不依赖页面获得焦点：选区由脚本手动建立
 * （{@code getSelection().addRange()}）。<b>手动选区 + 合成事件</b>是这套测试能在无头环境跑的前提。
 *
 * <p>原先 39 条用例挤在一个 1090 行的类里，按主题拆成 6 个测试类后，WebView 启动、文档加载、
 * 选区清理与全部 DOM 断言助手集中到这里，子类只写用例。
 *
 * <h2>⚠ 跨 class 共享 JavaFX Platform</h2>
 * <p>{@code Platform.startup} 全 JVM 只允许一次，6 个子类各自继承 {@link #bootFx()}，
 * 后 5 次会抛 {@code IllegalStateException}——这里吞掉异常并 {@code countDown} 放行；
 * {@code setImplicitExit(false)} 保证最后一个 {@code Stage} 关掉也不会把 toolkit 关掉。
 * <b>子类别写 {@code @AfterAll} 调 {@code Platform.exit()}</b>，否则后面的类全起不来。
 *
 * <p>WebView / Stage 只建一次（静态字段持有）：{@code bootFx} 每个子类都会跑一遍，
 * 不判空就会为 6 个类各建一套窗口。
 */
abstract class VisualEditorTestSupport {

    private static final String FULL_DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<head><title>第一章</title></head>"
            + "<body><h1>标题</h1><p>正文</p></body></html>";

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static WebView webView;

    /** 共享 WebView 访问器：session 级测试（如 runWhenLoaded）要在它上面装 VisualEditorSession。 */
    protected static WebView webView() {
        return webView;
    }

    /** 整个测试套只跑一次的 toolkit 启动 + 窗口创建；{@code @BeforeAll} 每个子类触发一次，故两处都要幂等。 */
    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        if (webView != null) {
            return;
        }
        runOnFx(() -> {
            webView = new WebView();
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(webView), 640, 480));
            stage.show();
        });
    }

    @BeforeEach
    protected void loadEditableDocument() throws Exception {
        loadEditable(FULL_DOC);
    }

    /** 把任意章节塞进 WebView 当可编辑文档（跨周期场景需要换不同的源文档）。 */
    protected static void loadEditable(String xhtml) throws Exception {
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

    /** 取序列化输出的 body 区间——head 里内嵌的编辑脚本源码会撞断言，不能整篇 contains。 */
    protected String bodyOf(String xhtml) {
        int start = xhtml.indexOf("<body");
        int end = xhtml.indexOf("</body>");
        assertTrue(start >= 0 && end > start, "序列化输出缺 body：" + xhtml);
        return xhtml.substring(start, end);
    }

    /** 选中正文段落里的文字（模拟用户划选一段后点工具条）。 */
    protected void selectParagraphContents() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange();"
                + " r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /** 把光标收进正文块（模拟用户点进某一句话里）。块可能是段落、列表项、引用或标题。 */
    protected void caretIntoParagraph() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p, li, blockquote, h2');"
                + " var r = document.createRange();"
                + " r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /** 走 Java 侧同样的通道：片段经 window 上的临时成员传入，不拼脚本字符串。 */
    protected Object insertHtml(String html) throws Exception {
        return withMember("__testHtml", html, "window.epubraInsertHtml(window.__testHtml)");
    }

    /**
     * 挂<b>多个</b> window 临时成员后在 FX 线程执行脚本，最后摘除全部成员。
     * 查找条的关键词 / 替换词 / 大小写开关就是多个成员一起传的场景；
     * {@link #withMember} 只支持一个，成员的 setMember 必须在 FX 线程上做。
     */
    protected static Object runScriptWithMembers(java.util.LinkedHashMap<String, Object> members,
                                                 String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> {
            netscape.javascript.JSObject window =
                    (netscape.javascript.JSObject) webView.getEngine().executeScript("window");
            for (java.util.Map.Entry<String, Object> entry : members.entrySet()) {
                window.setMember(entry.getKey(), entry.getValue());
            }
            try {
                out.set(webView.getEngine().executeScript(script));
            } finally {
                for (java.util.Map.Entry<String, Object> entry : members.entrySet()) {
                    window.setMember(entry.getKey(), null);
                }
            }
        });
        return out.get();
    }

    /** 同上，调粘贴净化器（净化入口同样不把 HTML 拼进脚本文本）。 */
    protected Object sanitize(String html) throws Exception {
        return withMember("__testHtml", html, "window.epubraSanitize(window.__testHtml)");
    }

    protected Object withMember(String member, String value, String script) throws Exception {
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

    protected String serialized() throws Exception {
        Object result = runScript("window.epubraSerialize()");
        assertTrue(result instanceof String, "serialize 应返回字符串，实际：" + result);
        return (String) result;
    }

    /**
     * 只查 body 里有没有某标签。负向断言必须走这里而不是对整篇序列化文本做
     * 子串匹配——head 里内嵌的编辑器脚本源码本身含有 &lt;strong&gt;、&lt;a&gt; 等字样，
     * 会把「已取消」误判成「仍残留」。
     */
    protected boolean bodyHasTag(String tag) throws Exception {
        return Boolean.TRUE.equals(runScript("!!document.body.querySelector('" + tag + "')"));
    }

    /**
     * 数 {@code <head>} 区间里的换行字符。空行一旦跨周期累积，这个数就会随
     * 「加载→回写」轮次线性增长——是 #59 最直接的可观测指标。
     */
    protected static int headNewlines(String xhtml) {
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

    protected static void assertWellFormedXhtml(String xhtml) {
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

    protected static Object runScript(String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> out.set(webView.getEngine().executeScript(script)));
        return out.get();
    }

    protected static void runOnFx(FxTask r) throws Exception {
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

    /** 包级可见：子类要在 FX 线程上跑自己的任务（如 session 级测试），需能向 runOnFx 传 lambda。 */
    @FunctionalInterface
    interface FxTask {
        void runWithException() throws Exception;
    }
}
