package org.chobit.epubra.lib;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.EpubVersion;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EPUB 内核的写出 / 读回往返验证。
 */
class EpubRoundTripTest {

    @TempDir
    Path tempDir;

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();

    @Test
    void 写出后读回应保留元数据与章节内容() throws IOException {
        Book book = BookFactory.createEmpty("往返测试书籍");
        book.metadata().addCreator("游承峰");
        book.metadata().setPublisher("Epubra");
        book.metadata().setDescription("用于验证读写一致性的样例书籍。");
        book.addChapter("第二章", ChapterTemplates.empty("第二章"));

        Path target = tempDir.resolve("round-trip.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        assertEquals("往返测试书籍", reloaded.metadata().firstTitle());
        assertEquals("游承峰", reloaded.metadata().creatorsInline());
        assertEquals("Epubra", reloaded.metadata().publisher());
        assertEquals("zh-CN", reloaded.metadata().language());
        assertEquals(EpubVersion.EPUB_3, reloaded.version());
        assertEquals(2, reloaded.spineResources().size());
        assertEquals(2, reloaded.toc().size());
        assertTrue(reloaded.spineResources().get(0).asString().contains("<h1>第一章</h1>"));
        assertNotNull(reloaded.metadata().primaryIdentifier());
    }

    @Test
    void mimetype必须是首个条目且不压缩() throws IOException {
        Book book = BookFactory.createEmpty("容器结构");
        Path target = tempDir.resolve("container.epub");
        writer.write(book, target);

        try (ZipFile zip = new ZipFile(target.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry first = zip.entries().nextElement();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
            try (InputStream in = zip.getInputStream(first)) {
                assertEquals("application/epub+zip", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            }
            assertNotNull(zip.getEntry("META-INF/container.xml"), "缺少 container.xml");
            assertNotNull(zip.getEntry("OEBPS/content.opf"), "缺少 OPF");
            assertNotNull(zip.getEntry("OEBPS/nav.xhtml"), "缺少 nav 文档");
            assertNotNull(zip.getEntry("OEBPS/toc.ncx"), "缺少 NCX");
        }
    }

    @Test
    void 多级目录应完整保留() throws IOException {
        Book book = BookFactory.createEmpty("目录层级");
        book.addChapter("第二章", ChapterTemplates.empty("第二章"));
        TOCReference parent = book.toc().roots().get(0);
        parent.setTitle("第一部分");
        parent.addChild(new TOCReference("小节 1-1", "chapter-1.xhtml#s1"));

        Path target = tempDir.resolve("toc.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        // 两个顶层章节，其中第一个带一个子条目
        assertEquals(2, reloaded.toc().roots().size());
        TOCReference root = reloaded.toc().roots().get(0);
        assertEquals("第一部分", root.title());
        assertEquals(1, root.children().size());
        assertEquals("小节 1-1", root.children().get(0).title());
        assertEquals("chapter-1.xhtml#s1", root.children().get(0).href());
        assertEquals("chapter-1.xhtml", root.children().get(0).resourceHref());
        assertEquals("s1", root.children().get(0).fragmentId());
    }

    @Test
    void 目录缺失时应按spine生成() throws IOException {
        Book book = BookFactory.createEmpty("无目录书籍");
        book.addChapter("第二章", ChapterTemplates.empty("第二章"));
        // addChapter 会登记目录条目，需在章节就绪后再清空，以验证写出时的兜底生成
        book.toc().clear();

        Path target = tempDir.resolve("no-toc.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        assertEquals(2, reloaded.toc().size());
        assertEquals("第一章", reloaded.toc().roots().get(0).title());
        assertEquals("第二章", reloaded.toc().roots().get(1).title());
    }

    @Test
    void 能读取EPUB2风格的NCX目录() throws IOException {
        Path target = tempDir.resolve("epub2.epub");
        writeMinimalEpub2(target);

        Book book = reader.read(target);
        assertEquals(EpubVersion.EPUB_2, book.version());
        assertEquals("EPUB2 样例", book.metadata().firstTitle());
        assertEquals(1, book.spineResources().size());
        assertEquals("第一章", book.toc().roots().get(0).title());
        assertEquals("chapter1.xhtml", book.toc().roots().get(0).resourceHref());
        assertTrue(book.spineResources().get(0).asString().contains("正文内容"));
    }

    /** 手工构造一个最小 EPUB 2 容器（仅 NCX 目录，无 nav 文档）。 */
    private void writeMinimalEpub2(Path target) throws IOException {
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="BookId">uuid:epub2-sample</dc:identifier>
                    <dc:title>EPUB2 样例</dc:title>
                    <dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                  </spine>
                </package>
                """;
        String ncx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head><meta name="dtb:uid" content="uuid:epub2-sample"/></head>
                  <docTitle><text>EPUB2 样例</text></docTitle>
                  <navMap>
                    <navPoint id="np-1" playOrder="1">
                      <navLabel><text>第一章</text></navLabel>
                      <content src="chapter1.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """;
        String chapter = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>第一章</title></head>
                <body><h1>第一章</h1><p>正文内容</p></body>
                </html>
                """;
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;

        try (OutputStream fileOut = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write(container.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            putString(zip, "OEBPS/content.opf", opf);
            putString(zip, "OEBPS/toc.ncx", ncx);
            putString(zip, "OEBPS/chapter1.xhtml", chapter);
        }
    }

    private static void putString(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void 章节增删改应反映到写出结果() throws IOException {
        Book book = BookFactory.createEmpty("章节维护");
        Resource second = book.addChapter("第二章", ChapterTemplates.empty("第二章"));
        Path target = tempDir.resolve("edit.epub");
        writer.write(book, target);

        Book reloaded = reader.read(target);
        assertEquals(2, reloaded.spineResources().size());

        Resource removed = reloaded.resources().getByHref(second.href());
        assertNotNull(removed);
        String removedId = removed.id();
        reloaded.resources().removeByHref(second.href());
        reloaded.spine().removeResourceId(removedId);
        reloaded.toc().clear();

        Path rewritten = tempDir.resolve("edit-removed.epub");
        writer.write(reloaded, rewritten);
        Book again = reader.read(rewritten);

        assertEquals(1, again.spineResources().size());
        assertNull(again.resources().getByHref(second.href()));
        assertFalse(again.spineResources().get(0).asString().contains("第二章"));
    }

    @Test
    void 写出到普通输出流应得到完整归档() throws IOException {
        // ZIP 的中央目录在 close 时才写入：write(Book, OutputStream) 必须自行收尾，
        // 否则下游（如撤销快照）拿到的是不完整的字节流，读回时会失败
        Book book = BookFactory.createEmpty("内存写出");
        book.addChapter("第二章", ChapterTemplates.empty("第二章"));

        byte[] data;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writer.write(book, out);
            data = out.toByteArray();
        }

        Book reloaded = reader.read(new ByteArrayInputStream(data));
        assertEquals("内存写出", reloaded.metadata().firstTitle());
        assertEquals(2, reloaded.spineResources().size());
    }

    @Test
    void 写出失败时不应覆盖已有目标文件() throws IOException {
        Path target = tempDir.resolve("existing.epub");
        byte[] original = "existing content".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);

        Book broken = BookFactory.createEmpty("坏书");
        broken.setOpfPath("mimetype"); // 与 EPUB 规范要求的首个 mimetype 条目冲突，稳定触发写出失败

        assertThrows(IOException.class, () -> writer.write(broken, target));

        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".epubra-")));
        }
    }
}
