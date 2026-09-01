package com.epubra.epublib;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.BookFactory;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.io.EpubReader;
import com.epubra.epublib.io.EpubWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源导入、删除、引用检测与封面设置的验证。
 */
class ResourceManagementTest {

    @TempDir
    Path tempDir;

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();

    @Test
    void 导入的资源应按媒体类型归类并在写回后保持字节一致() throws IOException {
        Book book = BookFactory.createEmpty("资源管理");
        byte[] cssBytes = "body { color: #333; }".getBytes(StandardCharsets.UTF_8);
        Path cssFile = tempDir.resolve("main.css");
        Files.write(cssFile, cssBytes);

        Resource css = book.addResource(cssFile);
        assertEquals("OEBPS/styles/main.css", css.href());
        assertEquals("main", css.id());
        assertEquals("text/css", css.mediaType());

        Path target = tempDir.resolve("with-css.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        Resource reloadedCss = reloaded.resources().getByHref("OEBPS/styles/main.css");
        assertNotNull(reloadedCss);
        assertArrayEquals(cssBytes, reloadedCss.data());
    }

    @Test
    void 图片资源应归入images目录且id合法() throws IOException {
        Book book = BookFactory.createEmpty("图片资源");
        Path image = tempDir.resolve("cover image.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

        Resource added = book.addResource(image);
        assertEquals("OEBPS/images/cover image.png", added.href());
        assertEquals("cover_image", added.id());
        assertEquals("image/png", added.mediaType());
    }

    @Test
    void 删除资源应同步清理spine与目录() {
        Book book = BookFactory.createEmpty("删除资源");
        book.addChapter("第二章", null);
        Resource second = book.spineResources().get(1);
        String removedHref = second.href();

        book.removeResource(second);

        assertEquals(1, book.spineResources().size());
        assertNull(book.resources().getByHref(removedHref));
        assertEquals(1, book.toc().size());
        assertEquals("第一章", book.toc().roots().get(0).title());
    }

    @Test
    void 未被引用的资源应被检出且被引用的不应误报() throws IOException {
        Book book = BookFactory.createEmpty("引用检测");
        Path cssFile = tempDir.resolve("style.css");
        Files.write(cssFile, "p { margin: 0; }".getBytes(StandardCharsets.UTF_8));
        Resource css = book.addResource(cssFile);

        List<Resource> before = book.unreferencedResources();
        assertTrue(before.stream().anyMatch(r -> r == css), "尚未被正文引用的样式应被检出");

        Resource chapter = book.spineResources().get(0);
        chapter.setString(chapter.asString().replace("</head>",
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"styles/style.css\"/></head>"));

        assertTrue(book.unreferencedResources().isEmpty(), "正文引用后不应再算作未引用资源");

        // 清理不应误删被引用的资源
        Path target = tempDir.resolve("referenced.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);
        assertTrue(reloaded.unreferencedResources().isEmpty());
        assertNotNull(reloaded.resources().getByHref("OEBPS/styles/style.css"));
    }

    @Test
    void 设置封面应在写回后保留cover属性() throws IOException {
        Book book = BookFactory.createEmpty("封面设置");
        Path image = tempDir.resolve("cover.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        Resource cover = book.addResource(image);
        book.setCover(cover);
        assertTrue(cover.properties().contains("cover-image"));
        assertEquals(cover.id(), book.metadata().property("cover"));

        Path target = tempDir.resolve("with-cover.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        assertTrue(reloaded.coverResource().isPresent(), "读回后应能定位封面");
        assertEquals("cover.png", reloaded.coverResource().orElseThrow().fileName());
        assertTrue(reloaded.coverResource().orElseThrow().properties().contains("cover-image"));
    }

    @Test
    void 清除封面应移除属性且被清理逻辑保护() throws IOException {
        Book book = BookFactory.createEmpty("清除封面");
        Path image = tempDir.resolve("cover.png");
        Files.write(image, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Resource cover = book.addResource(image);
        book.setCover(cover);

        // 封面本身不计入未引用资源
        assertTrue(book.unreferencedResources().isEmpty());

        book.setCover(null);
        assertFalse(cover.properties() != null && cover.properties().contains("cover-image"));
        assertNull(book.coverResourceId());
        assertTrue(book.unreferencedResources().stream().anyMatch(r -> r == cover),
                "清除封面后该图片不再被保护，应可被清理");
    }
}
