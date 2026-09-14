package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑窗口 / 预览区 WebView 滚动条<b>实际渲染</b>的端到端回归（宽度 + 可见性 + 轨道底色）。
 *
 * <p>为什么要真起 WebView：滚动条是渲染引擎自己画的，JavaFX 样式表够不到，只能靠 WebKit 的
 * {@code ::-webkit-scrollbar} 伪元素覆盖。字符串级断言只能证明「我们把这段 CSS 拼进了文档」，
 * 证明不了引擎真的照它渲染——伪元素在 XML 文档下不生效、属性名拼错、被后续规则盖掉，
 * 这些都会让断言全绿而画面上仍是引擎默认的宽滚动条。因此这里量的是真实数值与真实像素。
 *
 * <h2>量到的三件事</h2>
 * <ol>
 *   <li><b>占用宽度</b>：{@code window.innerWidth - document.documentElement.clientWidth}；
 *       配对照组——不注入样式的裸文档必须是另一个数（实测 0：引擎默认是覆盖式滚动条，
 *       不占布局宽度），否则「量到 7px」就不是我们注入样式的结果；</li>
 *   <li><b>滑块是否真被画出来</b>：光占位 7px 不等于「看得见」（#47 的教训：格式加了但看不见）；</li>
 *   <li><b>轨道底色</b>：滚动条位于页面视口之外，{@code html}/{@code body} 的背景铺不到，
 *       写 {@code transparent} 会露出 WebView 自带的白色底——实测快照里出现过整条白边。</li>
 * </ol>
 *
 * <h2>⚠ 快照要取「稳定帧」</h2>
 * <p>WebView 的 {@code snapshot} 可能落后一帧：文档 {@code SUCCEEDED} 之后立刻快照，拿到的
 * 有时还是上一个文档的画面。所以这里连续两次快照的条带指纹一致才认（见
 * {@link #settledSnapshot(int)}），否则像素断言会随机地量到上一页的颜色。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code StatusProgressUiTest} 的说明。
 */
class EditorScrollbarWidthTest {

    /** 目录侧栏与「源码」标签页的滚动条口径，见 app.css 的 toc-tree / content-editor 规则组。 */
    private static final int SIDEBAR_SCROLLBAR_WIDTH = 7;

    /** 条带取 8px 而不是 7px：多留 1px，好让「轨道有没有外溢」也能被看出来。 */
    private static final int STRIP_CSS_PX = 8;

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static WebView webView;

    /** 够长才会撑出垂直滚动条——没有溢出时量到的差值恒为 0，断言会变成空断言。 */
    private static final String TALL_XHTML = tallDocument();

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
    @DisplayName("编辑器画布的滚动条收窄到与目录侧栏同宽（7px）")
    void editorCanvasUsesThinScrollbar() throws Exception {
        int width = scrollbarWidthOf(PreviewHtml.editableDocument(TALL_XHTML, Theme.LIGHT));
        assertTrue(Math.abs(width - SIDEBAR_SCROLLBAR_WIDTH) <= 1,
                "编辑器画布的滚动条应与目录侧栏同宽（" + SIDEBAR_SCROLLBAR_WIDTH
                        + "px），实际=" + width);
    }

    @Test
    @Timeout(90)
    @DisplayName("预览区的滚动条同样收窄（同一套注入样式）")
    void previewUsesThinScrollbar() throws Exception {
        int width = scrollbarWidthOf(PreviewHtml.withTheme(TALL_XHTML, Theme.LIGHT));
        assertTrue(Math.abs(width - SIDEBAR_SCROLLBAR_WIDTH) <= 1,
                "预览区滚动条应与目录侧栏同宽，实际=" + width);
    }

    @Test
    @Timeout(90)
    @DisplayName("不改宽度就是普通细条，滑块真的画出来了")
    void scrollbarThumbIsActuallyPainted() throws Exception {
        load(PreviewHtml.editableDocument(TALL_XHTML, Theme.LIGHT));
        long painted = paintedPixels(settledSnapshot(STRIP_CSS_PX));
        assertTrue(painted > 50,
                "占位 7px 不等于「看得见」：若引擎没把滑块画出来，这一条会挂。"
                        + "右侧滚动条区域内的滑块色像素数=" + painted);
    }

    @Test
    @Timeout(90)
    @DisplayName("编辑器画布的滚动条轨道与纸面同色，不露引擎默认白底")
    void editorTrackMatchesPaper() throws Exception {
        load(PreviewHtml.editableDocument(TALL_XHTML, Theme.LIGHT));
        WritableImage shot = settledSnapshot(STRIP_CSS_PX);
        Color expected = pageBackground();
        Color actual = dominantStripColor(shot);
        assertTrue(closeTo(actual, expected),
                "轨道必须刷成纸面色：滚动条在页面视口之外，html/body 背景铺不过去，"
                        + "transparent 会露出 WebView 自带白底，画布右缘多一条白边。"
                        + "期望=" + rgb(expected) + " 实际=" + rgb(actual));
    }

    @Test
    @Timeout(90)
    @DisplayName("深色预览的滚动条轨道跟着主题走，不是固定浅色")
    void darkPreviewTrackFollowsTheme() throws Exception {
        load(PreviewHtml.withTheme(TALL_XHTML, Theme.DARK));
        WritableImage shot = settledSnapshot(STRIP_CSS_PX);
        Color expected = pageBackground();
        Color actual = dominantStripColor(shot);
        assertTrue(closeTo(actual, expected),
                "深色主题下轨道应是深底，否则预览区右缘会有一条刺眼亮边。"
                        + "期望=" + rgb(expected) + " 实际=" + rgb(actual));
    }

    @Test
    @Timeout(90)
    @DisplayName("对照组：不注入样式的裸文档走的不是我们这套细条口径")
    void plainDocumentKeepsEngineDefaultWidth() throws Exception {
        int width = scrollbarWidthOf(TALL_XHTML);
        // 诊断要重新载一次页面，故只在失败分支里取，不拖慢正常路径
        if (width == SIDEBAR_SCROLLBAR_WIDTH) {
            throw new AssertionError("裸文档不应量到 " + SIDEBAR_SCROLLBAR_WIDTH + "px，"
                    + "否则上面两条断言量到的不是我们注入的样式，判别力已失效。"
                    + diagnose(TALL_XHTML));
        }
    }

    // ---------------------------------------------------------------- 测量

    /**
     * 量垂直滚动条占用的布局宽度；量不到（没有溢出 / 脚本异常）时返回 -1。
     *
     * <p>{@code innerWidth} 含滚动条、{@code documentElement.clientWidth} 不含，差值即滚动条。
     * 同时要求没有横向溢出，否则量到的是横向滚动条的高度。
     */
    private static int scrollbarWidthOf(String document) throws Exception {
        load(document);
        String script = "(function () {"
                + " var d = document.documentElement;"
                + " if (d.scrollWidth > d.clientWidth + 1) { return -1; }"
                + " if (d.scrollHeight <= d.clientHeight) { return -1; }"
                + " return window.innerWidth - d.clientWidth; })()";
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

    /** 页面 {@code documentElement} 的实算背景色——轨道的期望值。 */
    private static Color pageBackground() throws Exception {
        Object value = runScript("getComputedStyle(document.documentElement).backgroundColor");
        assertTrue(value instanceof String, "取页面背景色失败，实际：" + value);
        String text = ((String) value).trim();
        int open = text.indexOf('(');
        int close = text.indexOf(')');
        assertTrue(open > 0 && close > open, "无法解析背景色：" + text);
        String[] parts = text.substring(open + 1, close).split(",");
        assertTrue(parts.length >= 3, "无法解析背景色：" + text);
        return Color.rgb(Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }

    /**
     * 取一张「画面已稳定」的快照：连续两次快照的条带指纹一致才认。
     *
     * <p>{@code snapshot} 可能落后一帧（文档 SUCCEEDED 之后立刻取，拿到的有时还是上一页），
     * 因此用「两次一致」当收敛判据——这样像素断言不会随机地量到上一个文档的颜色。
     */
    private static WritableImage settledSnapshot(int stripCssPx) throws Exception {
        long previous = Long.MIN_VALUE;
        WritableImage shot = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            shot = snapshot();
            long fingerprint = stripFingerprint(shot, stripCssPx);
            if (fingerprint == previous) {
                return shot;
            }
            previous = fingerprint;
            Thread.sleep(50);
        }
        return shot;
    }

    private static WritableImage snapshot() throws Exception {
        AtomicReference<WritableImage> shot = new AtomicReference<>();
        runOnFx(() -> shot.set(webView.snapshot(null, null)));
        assertNotNull(shot.get(), "WebView 快照失败");
        return shot.get();
    }

    private static long stripFingerprint(WritableImage image, int stripCssPx) {
        return eachStripPixel(image, new StripVisitor() {
            long hash = 1125899906842597L;

            @Override
            public void visit(Color color) {
                hash = hash * 31 + (long) (color.getRed() * 255)
                        + (long) (color.getGreen() * 255) * 7
                        + (long) (color.getBlue() * 255) * 13;
            }

            @Override
            public long result() {
                return hash;
            }
        }, stripCssPx);
    }

    /**
     * 数条带里有多少像素接近滑块灰 {@code #c8c8c8}。
     *
     * <p>必须按<b>颜色</b>判定，不能用「非纸面」代替：条带里除了滑块还有轨道底色与纸面，
     * 「非纸面」会把轨道、白底一并算成「已绘制」，这条断言就失效了。
     */
    private static long paintedPixels(WritableImage image) {
        return eachStripPixel(image, new StripVisitor() {
            long count;

            @Override
            public void visit(Color color) {
                if (closeTo(color, Color.rgb(200, 200, 200), 20)) {
                    count++;
                }
            }

            @Override
            public long result() {
                return count;
            }
        }, STRIP_CSS_PX);
    }

    /** 条带里出现次数最多的颜色——滑块之外就该是轨道底色，所以众数即轨道。 */
    private static Color dominantStripColor(WritableImage image) {
        Map<Integer, int[]> histogram = new HashMap<>();
        eachStripPixel(image, new StripVisitor() {
            @Override
            public void visit(Color color) {
                int key = ((int) (color.getRed() * 255) << 16)
                        | ((int) (color.getGreen() * 255) << 8)
                        | (int) (color.getBlue() * 255);
                histogram.computeIfAbsent(key, ignored -> new int[1])[0]++;
            }

            @Override
            public long result() {
                return 0;
            }
        }, STRIP_CSS_PX);
        int bestKey = -1;
        int bestCount = -1;
        for (Map.Entry<Integer, int[]> entry : histogram.entrySet()) {
            if (entry.getValue()[0] > bestCount) {
                bestCount = entry.getValue()[0];
                bestKey = entry.getKey();
            }
        }
        assertTrue(bestKey >= 0, "条带里没有任何像素");
        return Color.rgb((bestKey >> 16) & 0xFF, (bestKey >> 8) & 0xFF, bestKey & 0xFF);
    }

    /**
     * 遍历右侧条带的每个像素。
     *
     * <p>快照的像素尺寸不一定等于 CSS 像素（HiDPI 缩放下会更宽），按 快照宽 / 控件宽 的比值
     * 换算条带宽度。
     */
    private static long eachStripPixel(WritableImage image, StripVisitor visitor, int stripCssPx) {
        PixelReader reader = image.getPixelReader();
        double scale = image.getWidth() / webView.getWidth();
        int stripPx = (int) Math.ceil(stripCssPx * scale);
        int x0 = Math.max(0, (int) image.getWidth() - stripPx);
        for (int x = x0; x < (int) image.getWidth(); x++) {
            for (int y = 0; y < (int) image.getHeight(); y++) {
                visitor.visit(reader.getColor(x, y));
            }
        }
        return visitor.result();
    }

    /** 像素比较：允许渲染取整带来的少量偏差。 */
    private static boolean closeTo(Color actual, Color expected) {
        return closeTo(actual, expected, 3);
    }

    private static boolean closeTo(Color actual, Color expected, int tolerance) {
        double epsilon = tolerance / 255.0;
        return actual.getOpacity() > 0.5
                && Math.abs(actual.getRed() - expected.getRed()) <= epsilon
                && Math.abs(actual.getGreen() - expected.getGreen()) <= epsilon
                && Math.abs(actual.getBlue() - expected.getBlue()) <= epsilon;
    }

    private static String rgb(Color color) {
        return String.format("rgb(%d,%d,%d)", (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255), (int) (color.getBlue() * 255));
    }

    private interface StripVisitor {
        void visit(Color color);

        long result();
    }

    /**
     * 一条失败诊断串：把这几个值一起带出来，省得下次断言挂掉时再靠猜。
     *
     * <p>实测（JavaFX 24 / Windows，640×480 视口）：
     * <pre>
     *   裸文档     innerWidth=640 clientWidth=640 scrollHeight=1440 → 差值 0
     *   注入样式后 innerWidth=640 clientWidth=633 scrollHeight=1853 → 差值 7
     * </pre>
     * 说明引擎默认走的是<b>覆盖式</b>滚动条（撑得住内容却不占布局宽度，宽度约 15px 的半透明
     * 浮条），一旦用 {@code ::-webkit-scrollbar} 指定了宽度才切换成占位的经典滚动条。
     * 所以对照组不能按「默认更宽」来判，只能断言「不等于 7px」。
     */
    private static String diagnose(String document) throws Exception {
        load(document);
        Object detail = runScript("(function () {"
                + " var d = document.documentElement;"
                + " return 'innerWidth=' + window.innerWidth + ' clientWidth=' + d.clientWidth"
                + " + ' scrollWidth=' + d.scrollWidth + ' scrollHeight=' + d.scrollHeight"
                + " + ' clientHeight=' + d.clientHeight; })()");
        return " [实况 " + detail + "]";
    }

    // ---------------------------------------------------------------- 基础设施

    /** 40 个段落，稳定撑出垂直滚动条。 */
    private static String tallDocument() {
        StringBuilder body = new StringBuilder("<h1>滚动条</h1>");
        for (int i = 0; i < 40; i++) {
            body.append("<p>第 ").append(i).append(" 段正文，用来把内容撑到视口之外。</p>");
        }
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>滚动条</title></head>"
                + "<body>" + body + "</body></html>";
    }

    private static void load(String document) throws Exception {
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
