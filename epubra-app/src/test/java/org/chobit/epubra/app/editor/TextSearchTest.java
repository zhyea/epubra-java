package org.chobit.epubra.app.editor;

import org.chobit.epubra.app.editor.TextSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查找替换与章节标题同步的文本逻辑。
 */
class TextSearchTest {

    @Test
    @DisplayName("区分大小写时只替换完全匹配的片段")
    void replaceAllCaseSensitive() {
        TextSearch.ReplaceResult result = TextSearch.replaceAll("Epub epub EPUB", "epub", "书", true);
        assertEquals(1, result.count());
        assertEquals("Epub 书 EPUB", result.text());
    }

    @Test
    @DisplayName("忽略大小写时替换所有形态，并保留原文大小写形态之外的内容")
    void replaceAllIgnoreCase() {
        TextSearch.ReplaceResult result = TextSearch.replaceAll("Epub epub EPUB", "epub", "书", false);
        assertEquals(3, result.count());
        assertEquals("书 书 书", result.text());
    }

    @Test
    @DisplayName("无匹配时原样返回且计数为 0")
    void replaceAllWithoutMatch() {
        String source = "第一章 正文";
        TextSearch.ReplaceResult result = TextSearch.replaceAll(source, "缺失", "X", false);
        assertEquals(0, result.count());
        assertSame(source, result.text());
    }

    @Test
    @DisplayName("空关键词视为无匹配，不破坏原文")
    void replaceAllWithEmptyKeyword() {
        TextSearch.ReplaceResult result = TextSearch.replaceAll("正文", "", "X", false);
        assertEquals(0, result.count());
        assertEquals("正文", result.text());
    }

    @Test
    @DisplayName("替换为空串等价于删除")
    void replaceAllWithEmptyReplacement() {
        TextSearch.ReplaceResult result = TextSearch.replaceAll("a-b-c", "-", "", true);
        assertEquals(2, result.count());
        assertEquals("abc", result.text());
    }

    @Test
    @DisplayName("向后查找受大小写开关影响")
    void indexOfRespectsCaseSensitivity() {
        assertEquals(5, TextSearch.indexOf("Epub epub", "epub", 0, true));
        assertEquals(0, TextSearch.indexOf("Epub epub", "epub", 0, false));
    }

    @Test
    @DisplayName("向前查找从给定下标往回找")
    void lastIndexOfSearchesBackwards() {
        String text = "第一章 中间 第一章";
        int last = text.lastIndexOf("第一章");
        assertEquals(last, TextSearch.lastIndexOf(text, "第一章", text.length() - 1, true));
        assertEquals(0, TextSearch.lastIndexOf(text, "第一章", last - 1, true));
    }

    @Test
    @DisplayName("负下标与空关键词下向前查找返回 -1")
    void lastIndexOfEdgeCases() {
        assertEquals(-1, TextSearch.lastIndexOf("正文", "正", -1, true));
        assertEquals(-1, TextSearch.lastIndexOf("正文", "", 2, true));
    }

    @Test
    @DisplayName("选中文本与关键词的比较遵循大小写开关")
    void matchesComparesSelection() {
        assertTrue(TextSearch.matches("Epub", "Epub", true));
        assertFalse(TextSearch.matches("Epub", "epub", true));
        assertTrue(TextSearch.matches("Epub", "epub", false));
        assertFalse(TextSearch.matches(null, "epub", false));
        assertFalse(TextSearch.matches("", "epub", false));
    }

    @Test
    @DisplayName("重命名时同步 title 与 h1，正文其余部分不受影响")
    void replaceFirstTagTextUpdatesTitleAndHeading() {
        String xhtml = """
                <html><head><title>旧标题</title></head>
                <body><h1>旧标题</h1><p>提到旧标题的正文</p></body></html>
                """;
        String updated = TextSearch.replaceFirstTagText(xhtml, "title", "新标题");
        updated = TextSearch.replaceFirstTagText(updated, "h1", "新标题");

        assertTrue(updated.contains("<title>新标题</title>"));
        assertTrue(updated.contains("<h1>新标题</h1>"));
        // 正文里同样的文字不应被误改
        assertTrue(updated.contains("<p>提到旧标题的正文</p>"));
    }

    @Test
    @DisplayName("标签缺失时原样返回，不破坏手写正文")
    void replaceFirstTagTextKeepsSourceWhenTagMissing() {
        String xhtml = "<html><body><p>没有标题标签</p></body></html>";
        String updated = TextSearch.replaceFirstTagText(xhtml, "h1", "新标题");
        assertSame(xhtml, updated);
    }

    @Test
    @DisplayName("写入标签的标题会做 XML 转义")
    void replaceFirstTagTextEscapesXml() {
        String updated = TextSearch.replaceFirstTagText("<h1>旧</h1>", "h1", "A & B <C>");
        assertEquals("<h1>A &amp; B &lt;C&gt;</h1>", updated);
    }

    @Test
    @DisplayName("字数只数正文，去掉标签、注释、脚本与样式")
    void plainTextLengthIgnoresMarkup() {
        String xhtml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <!-- 注释里的字不算 -->
                  <head><title>标题</title><style>p { color: red; }</style></head>
                  <body><h1>第一章</h1><p>正文&amp;内容</p>
                    <script>var a = 1; x(a);</script>
                  </body>
                </html>
                """;
        // 标题 2 + 第一章 3 + 正文 2 + 内容 2 = 9；注释、样式、脚本与实体都不计入
        assertEquals(9, TextSearch.plainTextLength(xhtml));
    }

    @Test
    @DisplayName("空白与空串的字数为 0")
    void plainTextLengthOfBlankText() {
        assertEquals(0, TextSearch.plainTextLength(null));
        assertEquals(0, TextSearch.plainTextLength(""));
        assertEquals(0, TextSearch.plainTextLength("   \n\t "));
    }

    @Test
    @DisplayName("定位 id 属性时支持双引号与单引号")
    void indexOfIdAttributeHandlesBothQuotes() {
        String xhtml = "<p id=\"p1\">一</p><p id='p2'>二</p>";

        int first = TextSearch.indexOfIdAttribute(xhtml, "p1");
        assertEquals(xhtml.indexOf("p1\""), first);

        int second = TextSearch.indexOfIdAttribute(xhtml, "p2");
        assertEquals(xhtml.indexOf("p2'"), second);
    }

    @Test
    @DisplayName("找不到 id 或入参为空时返回 -1")
    void indexOfIdAttributeNotFound() {
        String xhtml = "<p id=\"p1\">一</p>";
        assertEquals(-1, TextSearch.indexOfIdAttribute(xhtml, "缺失"));
        assertEquals(-1, TextSearch.indexOfIdAttribute(xhtml, ""));
        assertEquals(-1, TextSearch.indexOfIdAttribute(xhtml, null));
        assertEquals(-1, TextSearch.indexOfIdAttribute(null, "p1"));
        assertEquals(-1, TextSearch.indexOfIdAttribute("", "p1"));
    }

    /**
     * 问题面板定位的选中长度必须等于「实际匹配到什么」。
     *
     * <p>正文里不会出现 {@code text/ch1.xhtml} 这种带路径的整串，于是会退化成末段
     * {@code ch1.xhtml} 匹配；旧实现无论走哪条降级路径都按整串锚点长度算选区，
     * 末段匹配时会越过匹配文本多高亮一截。
     */
    @Test
    @DisplayName("末段降级匹配时返回的长度是末段长度，不是整串锚点长度")
    void locateAnchorTailFallbackKeepsMatchedLength() {
        String xhtml = "<p><a href=\"ch1.xhtml\">下一章</a></p>";
        String anchor = "text/ch1.xhtml";

        TextSearch.AnchorMatch match = TextSearch.locateAnchor(xhtml, anchor, false);

        assertTrue(match != null, "整串搜不到时末段应能匹配");
        assertEquals("ch1.xhtml", xhtml.substring(match.index(), match.end()),
                "选区必须恰好等于实际匹配到的末段（旧实现按整串长度会多选 5 个字符）");
    }

    @Test
    @DisplayName("整串命中时选区就是锚点本身")
    void locateAnchorFullMatchUsesAnchorLength() {
        String xhtml = "<img src=\"images/cover.png\"/>";
        String anchor = "images/cover.png";

        TextSearch.AnchorMatch match = TextSearch.locateAnchor(xhtml, anchor, false);

        assertTrue(match != null, "整串应能直接匹配");
        assertEquals(anchor, xhtml.substring(match.index(), match.end()));
    }

    @Test
    @DisplayName("片段锚点优先按 id 属性定位，选区是 id 本身")
    void locateAnchorFragmentPrefersIdAttribute() {
        String xhtml = "<p id=\"sec2\">正文</p>";

        TextSearch.AnchorMatch match = TextSearch.locateAnchor(xhtml, "sec2", true);

        assertTrue(match != null, "id 属性应能定位");
        assertEquals("sec2", xhtml.substring(match.index(), match.end()));
        assertEquals(TextSearch.indexOfIdAttribute(xhtml, "sec2"), match.index());
    }

    @Test
    @DisplayName("找不到锚点或入参为空时返回 null")
    void locateAnchorReturnsNullWhenMissing() {
        assertTrue(TextSearch.locateAnchor("<p>正文</p>", "缺失", false) == null);
        assertTrue(TextSearch.locateAnchor(null, "a", false) == null);
        assertTrue(TextSearch.locateAnchor("<p>正文</p>", "", false) == null);
        assertTrue(TextSearch.locateAnchor("<p>正文</p>", "foo/bar/baz", false) == null,
                "末段也搜不到时同样返回 null");
    }

    // ---- 全书查找的章节扫描（nextChapterWithHit） ----

    private static final java.util.List<String> CHAPTERS =
            java.util.List.of("第一章 无命中", "第二章 有目标", "第三章 也有目标", "第四章 无");

    @Test
    @DisplayName("正向扫描：从起点之后的章节里找第一个含命中的")
    void forwardScanFindsFirstChapterWithHit() {
        assertEquals(1, TextSearch.nextChapterWithHit(CHAPTERS, 0, 4, 1, "目标", false));
        assertEquals(2, TextSearch.nextChapterWithHit(CHAPTERS, 2, 4, 1, "目标", false));
    }

    @Test
    @DisplayName("反向扫描：从起点之前的章节里找最后一个含命中的")
    void backwardScanFindsLastChapterWithHit() {
        assertEquals(2, TextSearch.nextChapterWithHit(CHAPTERS, 3, -1, -1, "目标", false));
        assertEquals(1, TextSearch.nextChapterWithHit(CHAPTERS, 1, -1, -1, "目标", false));
    }

    @Test
    @DisplayName("扫描范围不含终点：全书回绕语义由调用方分两段拼出")
    void scanStopsBeforeExclusiveEnd() {
        // 正向 0..1（不含 1）：第一章无命中，第二章虽有但被排除 → -1
        assertEquals(-1, TextSearch.nextChapterWithHit(CHAPTERS, 0, 1, 1, "目标", false));
        // 反向 3..2（不含 2）：第四章无命中，第三章虽有但被排除 → -1
        assertEquals(-1, TextSearch.nextChapterWithHit(CHAPTERS, 3, 2, -1, "目标", false));
        // 放开终点即命中，对照上面的排除
        assertEquals(1, TextSearch.nextChapterWithHit(CHAPTERS, 0, 2, 1, "目标", false));
        assertEquals(2, TextSearch.nextChapterWithHit(CHAPTERS, 3, 1, -1, "目标", false));
    }

    @Test
    @DisplayName("区分大小写与无命中各自成立")
    void scanHonorsCaseAndReturnsNegativeWhenMissing() {
        java.util.List<String> mixed = java.util.List.of("Alpha alpha", "ALPHA");
        assertEquals(1, TextSearch.nextChapterWithHit(mixed, 0, 2, 1, "ALPHA", true),
                "区分大小写：第一章没有全大写 ALPHA，应命中第二章");
        assertEquals(0, TextSearch.nextChapterWithHit(mixed, 0, 2, 1, "alpha", false),
                "忽略大小写：第一章即命中");
        assertEquals(-1, TextSearch.nextChapterWithHit(mixed, 0, 2, 1, "缺失", false),
                "整段扫描无命中应返回 -1");
    }

    @Test
    @DisplayName("step 为 0 直接拒绝：方向必须显式")
    void scanRejectsZeroStep() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TextSearch.nextChapterWithHit(CHAPTERS, 0, 4, 0, "目标", false));
    }
}
