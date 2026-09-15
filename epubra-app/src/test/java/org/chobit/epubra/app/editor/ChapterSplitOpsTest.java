package org.chobit.epubra.app.editor;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节拆分纯逻辑（{@link ChapterSplitOps}）：三种拆分方式、外壳保留与应用到书。
 */
class ChapterSplitOpsTest {

    private static final String HEAD_LINK =
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"../styles/main.css\"/>";

    /** 造一章：每个参数是一个顶层段落。 */
    private static String doc(String... blocks) {
        StringBuilder body = new StringBuilder();
        for (String block : blocks) {
            body.append("<p>").append(block).append("</p>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章 风起</title>"
                + HEAD_LINK + "</head><body>" + body + "</body></html>";
    }

    private static ChapterSplitOps.Params marker(String prefix, String suffix, String baseTitle) {
        return new ChapterSplitOps.Params(ChapterSplitOps.Mode.MARKER,
                prefix, suffix, null, 0, baseTitle);
    }

    // ---- 三种拆分方式 ----

    @Test
    @DisplayName("前后缀标记：汉字数字命中，每段独立成章且外壳（head/样式表）保留")
    void markerSplitSplitsAtNumberedMarkers() {
        String xhtml = doc("第一章 风起", "正文甲", "第二章 云涌", "正文乙", "第三章 雷动");
        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(xhtml,
                marker("第", "章", "第一章 风起"));

        assertEquals(3, segments.size(), "三个标记应拆出三章");
        assertEquals("第一章 风起", segments.get(0).title());
        assertEquals("第二章 云涌", segments.get(1).title());
        assertEquals("第三章 雷动", segments.get(2).title());

        assertTrue(segments.get(0).xhtml().contains("正文甲"), "第一段应含首章正文");
        assertFalse(segments.get(0).xhtml().contains("正文乙"), "第一段不得混入第二章正文");
        assertTrue(segments.get(1).xhtml().contains("正文乙"));

        for (ChapterSplitOps.Segment segment : segments) {
            assertWellFormed(segment.xhtml());
            assertTrue(segment.xhtml().contains(HEAD_LINK),
                    "样式表引用应保留：" + segment.xhtml());
            assertTrue(segment.xhtml().contains("<title>" + segment.title() + "</title>"),
                    "段标题应写进 <title>：" + segment.xhtml());
        }
    }

    @Test
    @DisplayName("前后缀标记：自定义前缀/后缀（Section N）与阿拉伯数字同样成立")
    void markerSplitSupportsCustomPrefixSuffix() {
        String xhtml = doc("Section 1 开篇", "正文", "Section 2 展开");
        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(xhtml,
                marker("Section ", "", "Section 1 开篇"));

        assertEquals(2, segments.size());
        assertEquals("Section 1 开篇", segments.get(0).title());
        assertEquals("Section 2 展开", segments.get(1).title());
    }

    @Test
    @DisplayName("正则方式：自定义正则命中块即新章起点，标题取段首块文本")
    void regexSplitSplitsAtMatches() {
        String xhtml = doc("序章", "引文", "第一回 出场", "正文");
        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(xhtml,
                new ChapterSplitOps.Params(ChapterSplitOps.Mode.REGEX,
                        null, null, "第[一二三四五六七八九十]+回", 0, "序章"));

        assertEquals(2, segments.size());
        assertEquals("序章", segments.get(0).title(), "首个标记之前的段用原章节标题");
        assertEquals("第一回 出场", segments.get(1).title());
    }

    @Test
    @DisplayName("每段字数：攒满即在下个块边界收段，标题用 原标题+（汉字序号）")
    void lengthSplitCutsAtBlockBoundaries() {
        String xhtml = doc("aaa", "bbb", "ccc");
        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(xhtml,
                new ChapterSplitOps.Params(ChapterSplitOps.Mode.LENGTH,
                        null, null, null, 5, "长章"));

        assertEquals(2, segments.size(), "3+3≥5 收一段，剩 ccc 一段");
        assertEquals("长章", segments.get(0).title());
        assertEquals("长章（二）", segments.get(1).title());
        assertTrue(segments.get(0).xhtml().contains("aaa"));
        assertFalse(segments.get(0).xhtml().contains("ccc"), "ccc 应落在第二段");
        assertTrue(segments.get(1).xhtml().contains("ccc"));
    }

    // ---- 边界与错误 ----

    @Test
    @DisplayName("找不到可拆分处：返回单元素列表（调用方据此提示，不动书）")
    void noSplitPointReturnsSingleSegment() {
        String xhtml = doc("正文一", "正文二");
        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(xhtml,
                marker("第", "章", "第一章"));

        assertEquals(1, segments.size());
        assertEquals("第一章", segments.get(0).title());
        assertTrue(segments.get(0).xhtml().contains("正文二"), "单段应是全章内容");
    }

    @Test
    @DisplayName("非法入参：正则无效 / 前后缀全空 / 字数小于 1 / XHTML 不合法，各自抛参数错误")
    void invalidInputsThrow() {
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitOps.split(
                doc("a"), new ChapterSplitOps.Params(ChapterSplitOps.Mode.REGEX,
                        null, null, "第[章", 0, "t")));
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitOps.split(
                doc("a"), marker("", "", "t")));
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitOps.split(
                doc("a"), new ChapterSplitOps.Params(ChapterSplitOps.Mode.LENGTH,
                        null, null, null, 0, "t")));
        assertThrows(IllegalArgumentException.class, () -> ChapterSplitOps.split(
                "<?xml version=\"1.0\"?><html><body><p>未闭合</body></html>",
                marker("第", "章", "t")));
    }

    // ---- 应用到书 ----

    @Test
    @DisplayName("apply：新章插在原章节同级之后，目录与阅读顺序一致，首段写回原资源")
    void applyInsertsSiblingsAndSyncsSpine() {
        Book book = new Book();
        Resource original = book.addChapter("第一章 风起",
                doc("第一章 风起", "正文甲", "第二章 云涌", "正文乙"));
        TOCReference reference = book.toc().roots().get(0);

        List<ChapterSplitOps.Segment> segments = ChapterSplitOps.split(
                original.asString(), marker("第", "章", "第一章 风起"));
        ChapterSplitOps.apply(book, reference, segments);

        assertEquals(2, book.toc().size(), "目录应有两项");
        assertEquals(2, book.spine().size(), "阅读顺序应有两项");
        assertEquals("第一章 风起", reference.title(), "首段标题未变时目录标题不动");
        assertTrue(original.asString().contains("正文甲"), "原资源应写入第一段");
        assertFalse(original.asString().contains("正文乙"), "原资源不得残留第二段");

        List<TOCReference> roots = book.toc().roots();
        assertEquals("第二章 云涌", roots.get(1).title(), "新章应紧跟原章节");
        Resource second = book.resources().getByHref(
                org.chobit.epubra.lib.util.Hrefs.resolve(
                        book.contentDirectory(), roots.get(1).resourceHref()));
        assertTrue(second != null && second.asString().contains("正文乙"),
                "新章资源应存在且承载第二段内容");
        assertEquals(original, book.spineResources().get(0), "阅读顺序首项应是原章节");
        assertEquals(second, book.spineResources().get(1), "阅读顺序次项应是新章");
    }

    /** 拆分产物必须是合法 XML（apply 后写进书的就是这个字符串）。 */
    private static void assertWellFormed(String xhtml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.newDocumentBuilder().parse(
                    new org.xml.sax.InputSource(new java.io.StringReader(xhtml)));
        } catch (Exception failed) {
            throw new AssertionError("拆分产物不是合法 XML：" + failed.getMessage()
                    + "\n---\n" + xhtml);
        }
    }
}
