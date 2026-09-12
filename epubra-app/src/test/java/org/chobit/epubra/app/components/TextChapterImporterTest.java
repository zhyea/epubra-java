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

    // ------------------------------------------------------------------ 边界

    @Test
    void emptyTextStillProducesOneChapter() {
        List<TextChapterImporter.ImportedChapter> chapters = TextChapterImporter.split("书名", "");

        assertEquals(1, chapters.size(), "空文本也要产出一章，否则导入后得到一本没有正文的书");
        assertEquals("书名", chapters.get(0).title());
        assertEquals("", chapters.get(0).body());
    }

    @Test
    void nullTextIsTreatedAsEmpty() {
        List<TextChapterImporter.ImportedChapter> chapters = TextChapterImporter.split("书名", null);

        assertEquals(1, chapters.size());
        assertEquals("", chapters.get(0).body());
    }

    @Test
    void blankTitleFallsBackToDefault() {
        List<TextChapterImporter.ImportedChapter> chapters = TextChapterImporter.split("   ", "正文");

        assertEquals("导入文本", chapters.get(0).title());
    }

    @Test
    void crlfAndFullWidthSpaceAreNormalized() {
        List<TextChapterImporter.ImportedChapter> chapters =
                TextChapterImporter.split("书", "第　一章 全角\r\n正文A\r\n\r\n正文B");

        assertEquals("第 一章 全角", chapters.get(0).title(), "全角空格先转成半角，再按标题识别");
        assertEquals("正文A\n正文B", chapters.get(0).body(), "CRLF 与连续空行都要归一化");
    }

    @Test
    void specialCharactersAreEscapedInXhtml() {
        Book book = TextChapterImporter.importBook("书", "第一章 <b>粗体</b> & \"引号\"\n正文 A<B & C");

        String xhtml = book.spineResources().get(0).asString();
        assertTrue(xhtml.contains("&lt;b&gt;"), "< 与 > 必须转义，否则 EPUB 不是合法 XML");
        assertTrue(xhtml.contains("&amp;"), "& 必须转义");
        assertFalse(xhtml.contains("<b>粗体</b>"), "原文里的尖括号不能直接落盘");
    }

    @Test
    void ordinaryProseLineIsNotMistakenForHeading() {
        assertFalse(TextChapterImporter.isChapterTitle("他今年三十岁了"));
        assertFalse(TextChapterImporter.isChapterTitle("一共有一百二十三个人"));
        assertFalse(TextChapterImporter.isChapterTitle(""));
        assertFalse(TextChapterImporter.isChapterTitle(null));
    }

    /**
     * 纯数字行会被识别为章节标题——这是 TXT 导入的<b>有意设计</b>：
     * 网文常见「1」「100」独占一行作章节序号。固化以免被误当成 bug 改掉。
     */
    @Test
    void numericLineIsTreatedAsHeadingByDesign() {
        assertTrue(TextChapterImporter.isChapterTitle("1"));
        assertTrue(TextChapterImporter.isChapterTitle("100"));
        assertTrue(TextChapterImporter.isChapterTitle("三"));
    }

    @Test
    void knownHeadingsAndShortTitlesAreRecognized() {
        assertTrue(TextChapterImporter.isChapterTitle("楔子"));
        assertTrue(TextChapterImporter.isChapterTitle("后记"));
        assertTrue(TextChapterImporter.isChapterTitle("第十二回 火烧连营"));
        assertTrue(TextChapterImporter.isChapterTitle("第一节 起因"));
    }

    /**
     * 「卷一」「章三」这类<b>章节字在前、数字在后</b>的倒装写法不在识别范围内——
     * 正则只覆盖「数字 + 章节关键字」的正序写法，这是有意划定的范围，不做扩展。
     * 固化当前行为：将来若有人放宽正则，本断言会提醒他确认这是有意的。
     */
    @Test
    void invertedOrderHeadingIsNotRecognizedYet() {
        assertFalse(TextChapterImporter.isChapterTitle("卷一"), "倒装写法当前不支持（已知缺口）");
        assertTrue(TextChapterImporter.isChapterTitle("第一卷"), "正序写法正常识别");
    }

    /**
     * 已知边界：两个标题连续出现且后者没有正文时，后者不会成为独立章节
     * （{@code body} 为空且 {@code chapters} 非空时末尾不补章）。
     * 这里固化当前行为——若后续改为「空章也保留」，需同步更新本断言。
     */
    @Test
    void headingWithoutAnyBodyIsDroppedWhenOthersExist() {
        List<TextChapterImporter.ImportedChapter> chapters =
                TextChapterImporter.split("书", "第一章 有内容\n正文\n第二章 空章");

        assertEquals(1, chapters.size());
        assertEquals("第一章 有内容", chapters.get(0).title());
    }

    @Test
    void bodyLinesAreJoinedAndBlankLinesDropped() {
        List<TextChapterImporter.ImportedChapter> chapters =
                TextChapterImporter.split("书", "第一行\n\n\n第二行");

        assertEquals("第一行\n第二行", chapters.get(0).body(), "空行应被压掉，段间只留一个换行");
    }

    @Test
    void importBookProducesReadableChapterCount() {
        Book book = TextChapterImporter.importBook("长书",
                "第一章 一\n内容一\n第二章 二\n内容二\n第三章 三\n内容三");

        assertEquals(3, book.spineResources().size());
        assertEquals(3, book.toc().roots().size(), "目录条目数应与章节数一致");
    }
}
