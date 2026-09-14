package org.chobit.epubra.app.controller.view;

import javafx.application.Platform;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.resource.ResourceOps;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.ResourceReferences;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑栏「图片」按钮的流水线回归：从本机选图 → 导入为书内资源 → 插入正文。
 *
 * <p>覆盖 {@link ResourceController#insertImagesFromPaths}（去掉「弹 FileChooser」之后的部分，
 * 选择器在测试环境里弹不出来）。
 *
 * <p>必须守住的两条：
 * <ul>
 *   <li><b>图片一定要真的进书</b>——EPUB 规定正文引用的图片必须位于包内，
 *       只往正文里塞一个指向本机磁盘的路径会留下悬空引用，结构校验必然报错。</li>
 *   <li><b>导入 + 插入共用一次 {@code beginChange}</b>——否则用户要按两次撤销才能回退一次插图。</li>
 * </ul>
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@link org.chobit.epubra.app.controller.StatusProgressUiTest} 的说明。
 */
class ResourceControllerImageInsertTest {

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFxToolkit() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);
    }

    @Test
    @Timeout(60)
    @DisplayName("选中的本机图片会导入为书内资源，并生成相对路径的 img 标签")
    void localImageIsImportedAndInserted(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("cover.png");
        Files.write(png, new byte[]{1, 2, 3, 4});

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(png), h.chapterHref);
        awaitInsert(h);

        assertEquals(1, h.beginChangeCalls.get(),
                "导入 + 插入必须共用一次 beginChange，否则一次插图要撤销两回");
        assertEquals(1, h.markDirtyCalls.get(), "插图后应当标脏");
        assertEquals(1, h.refreshResourcesCalls.get(), "新资源要出现在资源列表中");
        assertEquals(1, h.inserted.size(), "只应触发一次插入");

        String tag = h.inserted.get(0);
        assertTrue(tag.startsWith("<img "), tag);
        assertTrue(tag.endsWith("/>"), tag);
        assertTrue(tag.contains("alt=\"cover.png\""), "alt 保留原名供无障碍：" + tag);
        // 图片本体按内容寻址命名（md5 + 原扩展名），src 不再出现用户原名
        assertTrue(tag.matches("(?s).*src=\"[^\"]*/[0-9a-f]{32}\\.png\".*"),
                "src 应为 images/<md5>.png：" + tag);
        assertFalse(tag.contains(dir.toString()),
                "正文里不能出现本机绝对路径——必须写包内相对路径：" + tag);
        assertParsableXml(tag);

        Resource imported = findByContent(h.book, new byte[]{1, 2, 3, 4});
        assertTrue(imported != null,
                "选中的本机图片必须真的导入为书内资源，否则是悬空引用");
        assertTrue(imported.href().endsWith(
                        "/images/" + ResourceOps.md5Hex(new byte[]{1, 2, 3, 4}) + ".png"),
                "新资源应按内容寻址命名并归入 images/ 子目录：" + imported.href());
        assertEquals("已插入图片：cover.png", h.statuses.get(0));
    }

    @Test
    @Timeout(60)
    @DisplayName("文件名里的 XML 特殊字符会被转义，插入结果仍是合法 XHTML")
    void fileNameWithXmlSpecialCharsIsEscaped(@TempDir Path dir) throws Exception {
        Path weird = dir.resolve("Tom & Jerry.png");
        Files.write(weird, new byte[]{1});

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(weird), h.chapterHref);
        awaitInsert(h);

        String tag = h.inserted.get(0);
        assertTrue(tag.contains("Tom &amp; Jerry.png"), "alt 保留原名且做了转义：" + tag);
        assertFalse(tag.contains("Tom & Jerry.png"), "裸 & 必须被转义：" + tag);
        assertTrue(ResourceOps.extractImageSrcs(tag).get(0).matches(".*/[0-9a-f]{32}\\.png"),
                "src 用 md5 内容寻址名后，原名里的特殊字符根本进不了包内路径：" + tag);
        assertParsableXml(tag);
    }

    @Test
    @Timeout(60)
    @DisplayName("多选时全部导入，标签拼成一次插入")
    void multipleImagesAreInsertedTogether(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.png");
        Path b = dir.resolve("b.jpg");
        Files.write(a, new byte[]{1});
        Files.write(b, new byte[]{2});

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(a, b), h.chapterHref);
        awaitInsert(h);

        assertEquals(1, h.inserted.size(), "多张图片也应只调一次插入（拼成一串）");
        assertEquals(2, countOf(h.inserted.get(0), "<img "), h.inserted.get(0));
        assertEquals(1, countOf(h.inserted.get(0), "<br/>"),
                "多张图之间必须有分隔，否则两个行内元素会挤在同一行：" + h.inserted.get(0));
        assertParsableXml(h.inserted.get(0));
        assertTrue(findByContent(h.book, new byte[]{1}) != null);
        assertTrue(findByContent(h.book, new byte[]{2}) != null);
        assertEquals("已插入 2 张图片", h.statuses.get(0));
    }

    @Test
    @Timeout(60)
    @DisplayName("重复插入同一张图不会产生重复资源")
    void reinsertingSameImageReusesResource(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("dup.png");
        Files.write(png, new byte[]{7, 7, 7, 7});

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(png), h.chapterHref);
        awaitInsert(h);

        h.expectNextRun();
        h.controller.insertImagesFromPaths(List.of(png), h.chapterHref);
        awaitInsert(h);

        assertEquals(2, h.inserted.size(), "两次插入都应发生");
        assertEquals(1, countOf(h.book, new byte[]{7, 7, 7, 7}),
                "第二次必须复用已导入的资源，不能再挂一份");
        assertEquals(h.inserted.get(0), h.inserted.get(1),
                "同图同章两次插入应产出相同标签（指向同一 href）");
        assertEquals("已插入图片：dup.png", h.statuses.get(1));
    }

    @Test
    @Timeout(60)
    @DisplayName("同内容不同文件名的图片按内容排重：只挂一份资源，两个标签指向同一 href")
    void duplicateContentUnderDifferentNamesSharesResource(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("first.png");
        Path b = dir.resolve("second.png");
        Files.write(a, new byte[]{9, 9, 9});
        Files.write(b, new byte[]{9, 9, 9});

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(a, b), h.chapterHref);
        awaitInsert(h);

        assertEquals(1, countOf(h.book, new byte[]{9, 9, 9}),
                "内容排重不认文件名：同字节只挂一份资源");
        List<String> srcs = ResourceOps.extractImageSrcs(h.inserted.get(0));
        assertEquals(2, srcs.size(), "两个 <img> 都要插进去");
        assertEquals(srcs.get(0), srcs.get(1), "两个标签应指向同一资源");
        assertEquals("已插入 2 张图片", h.statuses.get(0));
    }

    @Test
    @Timeout(60)
    @DisplayName("插入落空时不谎报「已插入」——资源仍进书，但状态如实说明")
    void failedInsertIsNotReportedAsSuccess(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("cover.png");
        Files.write(png, new byte[]{1, 2});

        Harness h = new Harness();
        h.insertSucceeds = false;
        h.controller.insertImagesFromPaths(List.of(png), h.chapterHref);
        awaitInsert(h);

        assertEquals(1, h.inserted.size(), "仍应尝试插入一次");
        assertFalse(h.statuses.get(0).contains("已插入"),
                "插入失败不能报成功：" + h.statuses.get(0));
        assertTrue(h.statuses.get(0).contains("未能插入正文"), h.statuses.get(0));
        assertTrue(findByContent(h.book, new byte[]{1, 2}) != null,
                "插入失败不应回滚导入——用户选过的图仍然留在书里");
    }

    @Test
    @DisplayName("非图片文件被跳过并告知用户，不产生变更步")
    void nonImageFilesAreSkipped(@TempDir Path dir) throws Exception {
        Path text = dir.resolve("notes.txt");
        Files.writeString(text, "not an image");

        Harness h = new Harness();
        h.controller.insertImagesFromPaths(List.of(text), h.chapterHref);

        assertEquals(0, h.beginChangeCalls.get(), "没有可插入的图片时不应开启变更步");
        assertTrue(h.inserted.isEmpty(), "不应触发插入");
        assertEquals(1, h.warnings.size(), "应当告知用户跳过了什么：" + h.warnings);
        assertTrue(h.warnings.get(0).contains("notes.txt"), h.warnings.get(0));
        assertFalse(findByContent(h.book, "not an image".getBytes()) != null,
                "非图片不应进书");
    }

    @Test
    @Timeout(60)
    @DisplayName("章节位于子目录时相对路径回溯，且能被引用校验解析回图片路径")
    void relativePathBacktracksForChapterInSubdirectory(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("pic.png");
        Files.write(png, new byte[]{1, 2, 3, 4});

        Harness h = new Harness();
        // 外部 EPUB 的常见布局：章节在 OEBPS/text/part1/，图片被导入到 OEBPS/images/
        String chapterHref = "OEBPS/text/part1/chapter-1.xhtml";
        h.controller.insertImagesFromPaths(List.of(png), chapterHref);
        awaitInsert(h);

        String tag = h.inserted.get(0);
        // 前缀剥离会写出 "OEBPS/images/<md5>.png"（包内绝对路径）→ 预览里静默消失
        String src = ResourceOps.extractImageSrcs(tag).get(0);
        assertEquals("../../images/" + ResourceOps.md5Hex(new byte[]{1, 2, 3, 4}) + ".png", src);

        // 真正的不变量：引用经校验侧解析必须还原成图片的容器内路径，否则会被判为断链
        Resource image = findByContent(h.book, new byte[]{1, 2, 3, 4});
        assertEquals(image.href(),
                ResourceReferences.resolveTarget(Hrefs.parentDirectory(chapterHref), src));
    }

    // ---------------------------------------------------------------- 辅助

    /** 把父控制器注入的回调换成可观测的替身，从而在无头环境里驱动整条流水线。 */
    private static final class Harness {
        final BookContext ctx = new BookContext();
        final Book book = BookFactory.createEmpty("插图测试");
        final String chapterHref;
        final List<String> inserted = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final AtomicInteger beginChangeCalls = new AtomicInteger();
        final AtomicInteger markDirtyCalls = new AtomicInteger();
        final AtomicInteger refreshResourcesCalls = new AtomicInteger();
        final ResourceController controller = new ResourceController();
        /** 模拟「编辑器当前不可插入」：可视化编辑器未加载完成 / 源码区被禁用。 */
        boolean insertSucceeds = true;
        private volatile CountDownLatch finished = new CountDownLatch(1);

        Harness() {
            ctx.setBook(book);
            chapterHref = book.spineResources().get(0).href();
            controller.bind(
                    ctx,
                    () -> beginChangeCalls.incrementAndGet(),
                    () -> markDirtyCalls.incrementAndGet(),
                    () -> { },
                    () -> refreshResourcesCalls.incrementAndGet(),
                    () -> { },
                    () -> { },
                    message -> {
                        statuses.add(message);
                        finished.countDown();
                    },
                    warnings::add,
                    // 确认通道现带文案（Function<String, Boolean>）：harness 一律确认
                    message -> true,
                    (title, message, e) -> warnings.add(title + ": " + message),
                    AsyncTasks.NOOP_PROGRESS,
                    xhtml -> {
                        inserted.add(xhtml);
                        return insertSucceeds;
                    },
                    // 用例直接驱动 insertImagesFromPaths（跳过入口章节检查），provider 给空实现即可
                    () -> null);
        }

        /** 同一 harness 上再跑一次流水线前，重新准备完成信号。 */
        void expectNextRun() {
            finished = new CountDownLatch(1);
        }

        /** 流水线最后一步（setStatus）在 FX 线程完成，看到它才说明整条链路走完。 */
        void awaitFinished() throws InterruptedException {
            CountDownLatch latch = finished;
            assertTrue(latch.await(30, TimeUnit.SECONDS), "插图流水线超时未完成");
        }
    }

    private static void awaitInsert(Harness h) throws InterruptedException {
        h.awaitFinished();
    }

    /** 书里内容与给定字节完全一致的资源；找不到返回 null。 */
    private static Resource findByContent(Book book, byte[] data) {
        return book.resources().all().stream()
                .filter(r -> java.util.Arrays.equals(data, r.data()))
                .findFirst()
                .orElse(null);
    }

    /** 内容与给定字节一致的资源数量——用来断言「排重后只挂了一份」。 */
    private static long countOf(Book book, byte[] data) {
        return book.resources().all().stream()
                .filter(r -> java.util.Arrays.equals(data, r.data()))
                .count();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** 片段放进带 XHTML 命名空间的 div 里解析——模拟它被插进章节后的真实上下文。 */
    private static void assertParsableXml(String fragment) throws Exception {
        String wrapped = "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + fragment + "</div>";
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(new InputSource(new StringReader(wrapped)));
    }
}
