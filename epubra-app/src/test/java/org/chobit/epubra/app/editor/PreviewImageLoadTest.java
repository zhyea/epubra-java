package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览 / 可视化编辑器里的相对图片引用能不能<b>真的解码出来</b> —— 端到端回归。
 *
 * <p>为什么非要有这一层：P1-2 的形态是「门禁全绿但图片就是显示不出来」。
 * {@code PreviewMirror} 与 {@code PreviewHtml.withBaseHref} 的字符串级断言只能证明
 * 「我们注入了 base」，证明不了「WebView 真的据此把 {@code file:} 图片解出来了」。
 * 而且 {@code loadContent} 的页面源是 {@code about:blank}，没有 {@code <base>} 时
 * {@code <img>} 会<b>静默失败</b>（{@code naturalWidth} 停在 0，不报错、不抛异常），
 * 光看单元测试必然漏掉。
 *
 * <p>因此这里断言两件事：
 * <ol>
 *   <li>注入 base 后 {@code naturalWidth > 0}（图片真解出来了），且<b>对照组</b>
 *       不注入 base 时确实为 0 —— 防止这条断言变成永远成立的空断言；</li>
 *   <li>序列化回写的结果里<b>没有</b> {@code <base>}、原始 {@code src} 未被改写 ——
 *       {@code <base>} 只影响解析，不能污染正文。</li>
 * </ol>
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code StatusProgressUiTest} 的说明。
 */
class PreviewImageLoadTest {

    private static final String CHAPTER_HREF = "OEBPS/text/ch1.xhtml";
    private static final String IMAGE_HREF = "OEBPS/images/pixel.png";
    private static final String CHAPTER_XHTML =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>图</title></head>"
                    + "<body><p>看图</p><img src=\"../images/pixel.png\" alt=\"图\"/></body></html>";

    /**
     * 真实 EPUB 里常见的<b>畸形</b>结构：{@code <head>} 被写到整段正文之后，图片在 head 之前。
     *
     * <p>不是编出来的边角料——用户实际在编的书就是这个形态（镜像目录里抓到的原始章节）：
     * <pre>
     * &lt;html&gt;&lt;body&gt;&lt;h1&gt;贩罪&lt;/h1&gt;&lt;p&gt;作者：…&lt;img src="images/xxx.jpg"/&gt;&lt;/p&gt;
     * …大段正文… &lt;head&gt;&lt;title&gt;贩罪&lt;/title&gt;&lt;/head&gt;&lt;/body&gt;&lt;/html&gt;
     * </pre>
     */
    private static final String HEAD_AFTER_BODY_XHTML =
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>图</h1>"
                    + "<p>作者：某人<img src=\"../images/pixel.png\" alt=\"pixel.png\"/></p>"
                    + "<p>正文正文正文</p>"
                    + "<head><title>图</title></head></body></html>";

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

    @Test
    @Timeout(90)
    @DisplayName("注入 base 后图片真的解码出来；不注入则解不出来（对照组）")
    void relativeImageLoadsOnlyWithBaseHref(@TempDir Path dir) throws Exception {
        Book book = bookWithImage();
        PreviewMirror mirror = new PreviewMirror(dir);

        String base = mirror.baseHrefFor(book, CHAPTER_HREF);
        assertNotNull(base, "镜像可用时必须给出基准地址");

        // 实验组：带 base → 图片解码成功
        int width = loadAndAwaitImage(PreviewHtml.editableDocument(CHAPTER_XHTML, Theme.LIGHT, base));
        assertTrue(width > 0, "注入 base 后 ../images/pixel.png 必须能解码，naturalWidth=" + width);

        // 对照组：不带 base → 同一个 <img> 静默失败（这正是 P1-2 的原始症状）
        int withoutBase = loadAndAwaitImage(PreviewHtml.editableDocument(CHAPTER_XHTML, Theme.LIGHT));
        assertTrue(withoutBase == 0,
                "没有 base 时相对引用应当解析失败（naturalWidth=0）；"
                        + "若这里不再是 0，说明上面的断言已失去判别力，naturalWidth=" + withoutBase);
    }

    @Test
    @Timeout(90)
    @DisplayName("注入的 base 不会被序列化写回正文，原始 src 原样保留")
    void injectedBaseIsNotSerializedBack(@TempDir Path dir) throws Exception {
        Book book = bookWithImage();
        PreviewMirror mirror = new PreviewMirror(dir);
        String base = mirror.baseHrefFor(book, CHAPTER_HREF);

        loadAndAwaitImage(PreviewHtml.editableDocument(CHAPTER_XHTML, Theme.LIGHT, base));

        Object serialized = runScript("window.epubraSerialize()");
        assertTrue(serialized instanceof String, "serialize 应返回字符串，实际：" + serialized);
        String xhtml = (String) serialized;

        assertFalse(xhtml.contains("<base"), "注入的 base 是运行期产物，绝不能写回正文：" + xhtml);
        assertTrue(xhtml.contains("src=\"../images/pixel.png\""),
                "base 只影响解析，不改 src 属性值；回写必须是原始相对路径：" + xhtml);
        assertFalse(xhtml.contains("file:/"), "正文里不能残留镜像目录的绝对路径：" + xhtml);
    }

    @Test
    @Timeout(90)
    @DisplayName("head 掉在正文之后的畸形章节，图片同样必须解码出来（裂图回归）")
    void imageLoadsWhenHeadFollowsContent(@TempDir Path dir) throws Exception {
        Book book = bookWithImage();
        PreviewMirror mirror = new PreviewMirror(dir);
        String base = mirror.baseHrefFor(book, CHAPTER_HREF);
        assertNotNull(base, "镜像可用时必须给出基准地址");

        int width = loadAndAwaitImage(
                PreviewHtml.editableDocument(HEAD_AFTER_BODY_XHTML, Theme.LIGHT, base));
        assertTrue(width > 0,
                "head 排在正文之后时，base 曾被插到文件末尾——<img> 在它前面，相对引用按 "
                        + "about:blank 解析后静默失败，页面上只剩 alt 文字（图片名）。"
                        + "naturalWidth=" + width);
    }

    @Test
    @Timeout(90)
    @DisplayName("畸形章节里注入的 base 同样不会被序列化写回正文")
    void malformedChapterStillDropsInjectedBase(@TempDir Path dir) throws Exception {
        Book book = bookWithImage();
        PreviewMirror mirror = new PreviewMirror(dir);
        String base = mirror.baseHrefFor(book, CHAPTER_HREF);

        loadAndAwaitImage(
                PreviewHtml.editableDocument(HEAD_AFTER_BODY_XHTML, Theme.LIGHT, base));
        Object serialized = runScript("window.epubraSerialize()");

        assertTrue(serialized instanceof String, "serialize 应返回字符串，实际：" + serialized);
        String xhtml = (String) serialized;
        assertFalse(xhtml.contains("<base"), "注入的 base 绝不能写回畸形章节：" + xhtml);
        assertTrue(xhtml.contains("src=\"../images/pixel.png\""),
                "base 只影响解析，不改 src 属性值：" + xhtml);
    }

    // ---------------------------------------------------------------- 辅助

    private static Book bookWithImage() throws IOException {
        Book book = BookFactory.createEmpty("图片解析");
        Resource chapter = new Resource("chapter-sub", CHAPTER_HREF, MediaTypes.XHTML);
        chapter.setString(CHAPTER_XHTML);
        book.resources().add(chapter);
        Resource image = new Resource("image-pixel", IMAGE_HREF, MediaTypes.PNG);
        image.setData(pixelPng());
        book.resources().add(image);
        return book;
    }

    /** 4×4 的合法 PNG；用 ImageIO 现场生成，避免手抄 base64 出错。 */
    private static byte[] pixelPng() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF3366CC);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out), "生成测试用 PNG 失败");
        return out.toByteArray();
    }

    /** 载入文档并等待 {@code <img>.naturalWidth} 出炉（图片解码可能略晚于文档 SUCCEEDED）。 */
    private static int loadAndAwaitImage(String document) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        runOnFx(() -> {
            WebEngine engine = webView.getEngine();
            engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            engine.loadContent(document, "application/xhtml+xml");
        });
        assertTrue(loaded.await(30, TimeUnit.SECONDS), "文档加载超时");

        String script = "(function () { var i = document.querySelector('img');"
                + " return i ? i.naturalWidth : -1; })()";
        int last = -1;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Object value = runScript(script);
            last = value instanceof Number number ? number.intValue() : -1;
            if (last > 0) {
                return last;
            }
            Thread.sleep(50);
        }
        return last;
    }

    private static Object runScript(String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> out.set(webView.getEngine().executeScript(script)));
        return out.get();
    }

    private static void runOnFx(FxTask task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.runWithException();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX 任务超时");
        if (error.get() != null) {
            throw new RuntimeException("FX task failed: " + error.get().getMessage(), error.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
