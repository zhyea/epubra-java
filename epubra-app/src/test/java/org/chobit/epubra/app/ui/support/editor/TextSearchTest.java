package org.chobit.epubra.app.ui.support.editor;

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
}
