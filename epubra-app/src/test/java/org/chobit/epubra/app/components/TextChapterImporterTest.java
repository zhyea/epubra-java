package org.chobit.epubra.app.components;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChapterImporterTest {

    @Test
    void splitsVolumeAndChapterHeadingsIntoIndependentChapters() {
        String text = """
                第一卷 风起
                第一章 初见
                这是第一章正文。
                仍然是第一章。
                第二章 重逢
                这是第二章正文。
                楔子
                这是楔子正文。
                """;

        Book book = TextChapterImporter.importBook("测试书", text);
        List<Resource> chapters = book.spineResources();

        assertEquals(3, chapters.size());
        assertEquals("第一章 初见", book.toc().roots().get(0).title());
        assertEquals("第二章 重逢", book.toc().roots().get(1).title());
        assertEquals("楔子", book.toc().roots().get(2).title());
        assertTrue(chapters.get(0).asString().contains("这是第一章正文。"));
        assertTrue(chapters.get(1).asString().contains("这是第二章正文。"));
        assertTrue(chapters.get(2).asString().contains("这是楔子正文。"));
        assertFalse(chapters.get(0).asString().contains("第二章重逢"));
    }

    @Test
    void supportsArabicNumeralsAndTextWithoutHeadings() {
        Book book = TextChapterImporter.importBook(
                "无标题文本",
                "开头内容\n\n第 2 章 第二节\n章节内容");

        assertEquals(2, book.spineResources().size());
        assertEquals("无标题文本", book.toc().roots().get(0).title());
        assertEquals("第 2 章 第二节", book.toc().roots().get(1).title());
        assertTrue(book.spineResources().get(0).asString().contains("开头内容"));
        assertTrue(book.spineResources().get(1).asString().contains("章节内容"));
    }
}
