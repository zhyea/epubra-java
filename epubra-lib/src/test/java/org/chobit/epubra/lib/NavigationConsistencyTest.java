package org.chobit.epubra.lib;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.chobit.epubra.lib.validation.EpubValidator;
import org.chobit.epubra.lib.validation.IssueKind;
import org.chobit.epubra.lib.validation.ValidationIssue;
import org.chobit.epubra.lib.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导航文档（nav.xhtml / toc.ncx）与内存书籍模型的一致性。
 *
 * <p>这两个文档由目录派生，写出时才生成。若生成的内容不回写到资源，内存里的导航文档就会
 * 停在「空资源」状态——而撤销快照、保存都会触发一次写出，于是用户一编辑就会在校验里
 * 看到「导航文档无法解析」这类凭空出现的错误。
 */
class NavigationConsistencyTest {

    @TempDir
    Path tempDir;

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();
    private final EpubValidator validator = new EpubValidator();

    @Test
    void 写出的导航文档内容应同步回内存资源() throws IOException {
        Book book = BookFactory.createEmpty("导航同步");
        book.addChapter("第二章", null);

        writer.write(book, tempDir.resolve("nav.epub"));

        Resource nav = book.navResource();
        assertNotNull(nav, "写出后内存中应存在 nav 资源");
        assertTrue(nav.asString().contains("第二章"), "nav 内容必须反映当前目录，不能是空资源");

        Resource ncx = book.resources().getById(book.spine().tocResourceId());
        assertNotNull(ncx, "写出后内存中应存在 NCX 资源");
        assertTrue(ncx.asString().contains("第二章"), "NCX 内容必须反映当前目录");
    }

    @Test
    void 序列化到内存后校验不应因导航文档为空而误报() throws IOException {
        Book book = BookFactory.createEmpty("快照校验");
        book.addChapter("第二章", null);

        // 撤销快照走的正是这条路径：把整本书序列化一次
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writer.write(book, out);
        }

        ValidationReport report = validator.validate(book);
        assertEquals(0, report.errorCount(), "序列化不该在书里留下空资源：" + report);
    }

    @Test
    void 内存校验不应因导航文档滞后于目录而报不一致() throws IOException {
        Book book = BookFactory.createEmpty("导航滞后");
        writer.write(book, tempDir.resolve("stale.epub"));
        // 保存后又改了目录：nav 还没重新生成，但纯内存校验阶段不应把它算作问题
        book.addChapter("第二章", null);

        List<IssueKind> kinds = validator.validate(book).issues().stream()
                .map(ValidationIssue::kind)
                .toList();

        assertFalse(kinds.contains(IssueKind.NAV_VS_TOC_INCONSISTENT), "内存校验不该比较派生的 nav 与目录");
        assertFalse(kinds.contains(IssueKind.NCX_VS_TOC_INCONSISTENT), "内存校验不该比较派生的 NCX 与目录");
    }

    @Test
    void 容器校验应报出目录改动后未保存的导航滞后() throws IOException {
        Book book = BookFactory.createEmpty("容器校验");
        Path target = tempDir.resolve("container.epub");
        writer.write(book, target);

        assertTrue(codes(validator.validate(book, target)).isEmpty(), "刚保存完不应有导航类问题");

        book.addChapter("第二章", null);
        List<String> codes = codes(validator.validate(book, target));

        assertTrue(codes.contains("D12"), "磁盘上的 nav 仍是保存时的内容，目录改了应提示不同步：" + codes);
        assertTrue(codes.contains("D17"), "NCX 同理：" + codes);
    }

    @Test
    void 保存后再读回应保有完整导航文档() throws IOException {
        Book book = BookFactory.createEmpty("往返导航");
        book.addChapter("第二章", null);
        Path target = tempDir.resolve("round-trip.epub");

        writer.write(book, target);
        Book reloaded = reader.read(target);

        ValidationReport report = validator.validate(reloaded);
        assertEquals(0, report.errorCount(), "读回的书籍不应有错误级问题：" + report);
        assertEquals(2, reloaded.toc().size());
    }

    private static List<String> codes(ValidationReport report) {
        return report.issues().stream().map(issue -> issue.kind().code()).toList();
    }
}
