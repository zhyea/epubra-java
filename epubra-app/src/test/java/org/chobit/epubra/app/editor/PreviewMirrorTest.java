package org.chobit.epubra.app.editor;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PreviewMirror} 的行为验证：可达资源镜像、基准地址形态、增量写盘、换书清空、越界防护。
 *
 * <p>背景：预览与可视化编辑器走 {@code loadContent}，页面源是 {@code about:blank}，正文里的
 * 相对引用没有解析基准 —— 不镜像就一律显示不出图片。本类不碰 JavaFX，可无头跑。
 */
class PreviewMirrorTest {

    private static final byte[] PNG_BYTES = {1, 2, 3, 4, 5};

    @Test
    @DisplayName("章节在子目录时基准指向该目录，可达资源按容器结构落盘")
    void mirrorsReachableResourcesAndReturnsChapterDirectoryBase(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");
        addChapter(book, "OEBPS/text/ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                        + "<img src=\"../images/a.png\"/></body></html>");
        addImage(book, "OEBPS/images/a.png");

        String base = mirror.baseHrefFor(book, "OEBPS/text/ch1.xhtml");

        assertNotNull(base);
        assertTrue(base.startsWith("file:"), base);
        assertTrue(base.endsWith("/OEBPS/text/"), base);
        // 镜像里的相对位置必须与容器内一致，否则 ../images/a.png 解析不到
        Path mirrored = dir.resolve("OEBPS/images/a.png");
        assertTrue(Files.exists(mirrored), "被引用的图片必须落盘：" + mirrored);
        assertArrayEquals(PNG_BYTES, Files.readAllBytes(mirrored));
    }

    @Test
    @DisplayName("未被引用的资源不落盘——镜像只覆盖可达集合")
    void unreachableResourcesAreNotMirrored(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");
        String href = firstChapterHref(book,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>无图</p></body></html>");
        addImage(book, "OEBPS/images/orphan.png");

        mirror.baseHrefFor(book, href);

        assertFalse(Files.exists(dir.resolve("OEBPS/images/orphan.png")),
                "没有任何章节引用它，写盘纯属浪费");
    }

    @Test
    @DisplayName("CSS 里 url() 引用的资源会沿引用链一并镜像")
    void cssReferencedResourcesAreMirroredTransitively(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");
        addChapter(book, "OEBPS/text/ch1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
                        + "<link rel=\"stylesheet\" href=\"../styles/main.css\"/></head>"
                        + "<body><p>x</p></body></html>");
        Resource css = new Resource("css-1", "OEBPS/styles/main.css", MediaTypes.CSS);
        css.setString("body { background: url(../images/bg.png); }");
        book.resources().add(css);
        addImage(book, "OEBPS/images/bg.png");

        String base = mirror.baseHrefFor(book, "OEBPS/text/ch1.xhtml");

        assertNotNull(base);
        assertTrue(Files.exists(dir.resolve("OEBPS/styles/main.css")), "CSS 自身要落盘");
        assertTrue(Files.exists(dir.resolve("OEBPS/images/bg.png")),
                "CSS 里 url() 指向的图片也要落盘，否则背景图照样是空的");
    }

    @Test
    @DisplayName("内容未变则不重写文件（增量：换章节 / 切主题不该反复落盘）")
    void unchangedResourcesAreNotRewritten(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");
        String href = firstChapterHref(book,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                        + "<img src=\"images/a.png\"/></body></html>");
        addImage(book, "OEBPS/images/a.png");

        mirror.baseHrefFor(book, href);
        Path mirrored = dir.resolve("OEBPS/images/a.png");
        var firstWrite = Files.getLastModifiedTime(mirrored);

        Thread.sleep(50);
        mirror.baseHrefFor(book, href);

        assertEquals(firstWrite, Files.getLastModifiedTime(mirrored),
                "指纹一致就应跳过写盘");
    }

    @Test
    @DisplayName("换书清空镜像：上一本的残留不会串到新书")
    void switchingBookWipesPreviousMirror(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book first = BookFactory.createEmpty("第一本");
        String firstHref = firstChapterHref(first,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                        + "<img src=\"images/old.png\"/></body></html>");
        addImage(first, "OEBPS/images/old.png");
        mirror.baseHrefFor(first, firstHref);
        assertTrue(Files.exists(dir.resolve("OEBPS/images/old.png")));

        Book second = BookFactory.createEmpty("第二本");
        String secondHref = firstChapterHref(second,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>新书</p></body></html>");
        mirror.baseHrefFor(second, secondHref);

        assertFalse(Files.exists(dir.resolve("OEBPS/images/old.png")),
                "换书必须清空镜像目录");
    }

    @Test
    @DisplayName("逃出镜像根目录的章节路径被拒绝，返回 null 而不是写到外面去")
    void escapingChapterPathIsRejected(@TempDir Path dir) {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");

        assertNull(mirror.baseHrefFor(book, "../escape.xhtml"),
                "越界路径必须被拦下，宁可退化也不能往镜像目录外面写");
        assertNull(mirror.baseHrefFor(book, ""), "空章节路径直接跳过");
        assertNull(mirror.baseHrefFor(null, "OEBPS/a.xhtml"), "没有书就没有镜像");
    }

    @Test
    @DisplayName("discard 清空镜像内容，目录本身保留供下次复用")
    void discardClearsContentsButKeepsRoot(@TempDir Path dir) throws Exception {
        PreviewMirror mirror = new PreviewMirror(dir);
        Book book = BookFactory.createEmpty("镜像");
        String href = firstChapterHref(book,
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                        + "<img src=\"images/a.png\"/></body></html>");
        addImage(book, "OEBPS/images/a.png");
        mirror.baseHrefFor(book, href);

        mirror.discard();

        assertFalse(Files.exists(dir.resolve("OEBPS/images/a.png")));
        assertTrue(Files.isDirectory(dir));
    }

    // ---------------------------------------------------------------- 辅助

    /** 复用 book 自带的首章，换上指定正文；返回它的 href。 */
    private static String firstChapterHref(Book book, String xhtml) {
        Resource chapter = book.spineResources().get(0);
        chapter.setString(xhtml);
        return chapter.href();
    }

    private static void addChapter(Book book, String href, String xhtml) {
        Resource chapter = new Resource("chapter-" + href.hashCode(), href, MediaTypes.XHTML);
        chapter.setString(xhtml);
        book.resources().add(chapter);
    }

    private static void addImage(Book book, String href) {
        Resource image = new Resource("image-" + href.hashCode(), href, MediaTypes.PNG);
        image.setData(PNG_BYTES);
        book.resources().add(image);
    }
}
