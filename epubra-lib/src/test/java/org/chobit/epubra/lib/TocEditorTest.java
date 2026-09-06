package org.chobit.epubra.lib;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.domain.TocEditor;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录结构编辑与阅读顺序同步的验证。
 */
class TocEditorTest {

    @TempDir
    Path tempDir;

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();

    private static List<String> titles(List<TOCReference> nodes) {
        return nodes.stream().map(TOCReference::title).toList();
    }

    private static List<String> spineTitles(Book book) {
        return book.spineResources().stream()
                .map(r -> ChapterTemplates.extractTitle(r.asString()))
                .toList();
    }

    @Test
    void 移动节点应改变目录顺序并同步阅读顺序() {
        Book book = BookFactory.createEmpty("目录编辑");
        book.addChapter("第二章", null);
        book.addChapter("第三章", null);

        TOCReference first = book.toc().roots().get(0);
        TOCReference third = book.toc().roots().get(2);

        assertTrue(TocEditor.moveBefore(book, third, first));
        assertEquals(List.of("第三章", "第一章", "第二章"), titles(book.toc().roots()));
        assertEquals(List.of("第三章", "第一章", "第二章"), spineTitles(book));

        assertTrue(TocEditor.moveAfter(book, first, third));
        assertEquals(List.of("第三章", "第一章", "第二章"), titles(book.toc().roots()));
    }

    @Test
    void 降级与升级应正确改变层级() {
        Book book = BookFactory.createEmpty("层级调整");
        book.addChapter("第二章", null);
        book.addChapter("第三章", null);

        TOCReference second = book.toc().roots().get(1);

        assertTrue(TocEditor.indent(book, second), "有前一个兄弟时应可降级");
        assertEquals(List.of("第一章", "第三章"), titles(book.toc().roots()));
        assertEquals(1, book.toc().roots().get(0).children().size());
        assertEquals("第二章", book.toc().roots().get(0).children().get(0).title());

        assertTrue(TocEditor.outdent(book, second));
        assertEquals(List.of("第一章", "第二章", "第三章"), titles(book.toc().roots()));
        assertTrue(book.toc().roots().get(0).children().isEmpty());
    }

    @Test
    void 第一个节点没有前序兄弟时不可降级顶层节点不可升级() {
        Book book = BookFactory.createEmpty("边界");
        book.addChapter("第二章", null);

        assertFalse(TocEditor.indent(book, book.toc().roots().get(0)), "首节点无前序兄弟，不可降级");
        assertFalse(TocEditor.outdent(book, book.toc().roots().get(0)), "顶层节点无法再升级");
    }

    @Test
    void 不允许把节点移入自己的子孙() {
        Book book = BookFactory.createEmpty("防环");
        book.addChapter("第二章", null);
        TOCReference parent = book.toc().roots().get(0);
        TOCReference child = book.toc().roots().get(1);
        TocEditor.indent(book, child);

        assertTrue(TocEditor.isAncestorOrSelf(parent, child));
        assertFalse(TocEditor.moveTo(book, parent, child, 0), "父节点不能移入自己的子节点");
        assertFalse(TocEditor.moveTo(book, parent, parent, 0), "节点不能移入自身");
        assertEquals(List.of("第一章"), titles(book.toc().roots()));
    }

    @Test
    void 阅读顺序未覆盖的资源应保留在末尾() {
        Book book = BookFactory.createEmpty("保留未编目章节");
        Resource orphan = book.addChapter("附录", null);
        // 目录里只保留第一章，其余视为未编目
        book.toc().roots().subList(1, book.toc().roots().size()).clear();

        TocEditor.syncSpineFromToc(book);

        List<Resource> spine = book.spineResources();
        assertEquals(2, spine.size());
        assertEquals("第一章", ChapterTemplates.extractTitle(spine.get(0).asString()));
        assertSame(orphan, spine.get(1), "未在目录中出现的章节应保留在阅读顺序末尾");
    }

    @Test
    void 调整后的目录结构应在写回读回后保留() throws IOException {
        Book book = BookFactory.createEmpty("结构往返");
        book.addChapter("第二章", null);
        book.addChapter("第三章", null);

        TocEditor.indent(book, book.toc().roots().get(1));   // 第二章降为第一章的子节点
        TocEditor.indent(book, book.toc().roots().get(1));   // 第三章降为第一章的子节点
        TOCReference child = book.toc().roots().get(0).children().get(0);
        child.setTitle("第一章·第一节");

        Path target = tempDir.resolve("toc-structure.epub");
        writer.write(book, target);
        Book reloaded = reader.read(target);

        assertEquals(1, reloaded.toc().roots().size());
        TOCReference root = reloaded.toc().roots().get(0);
        assertEquals("第一章", root.title());
        assertEquals(2, root.children().size());
        assertEquals(List.of("第一章·第一节", "第三章"), titles(root.children()));
        assertEquals(3, reloaded.spineResources().size());
        // 目录标题与文档内标题是两份数据：重命名目录节点只改写 NCX/nav 的导航标签（上面已断言），
        // 不会同步改写章节 XHTML 里的 <title>/<h1>，因此阅读顺序仍按文档原标题读回。
        // 这与 Sigil 的默认行为一致：需要同步时由用户显式触发。
        assertEquals(List.of("第一章", "第二章", "第三章"), spineTitles(reloaded));

        assertNotNull(reader.read(target).navResource(), "读回后仍应存在导航文档");
    }
}
