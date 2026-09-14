package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块级格式（段落 / 标题 / 引用 / 分隔线 / 插图 / 未知命令）。
 *
 * <p>块级命令的共同陷阱是<b>非法嵌套</b>：引用里套引用、分隔线前后缺块、在整列表被选中时退回源码区（#61）。本类收拢这些块级命令的产出与降级行为。
 *
 * <p>脚手架（WebView 启动 / 文档加载 / 选区清理 / DOM 断言助手）见
 * {@link VisualEditorTestSupport}。
 */
class VisualEditorBlockFormatTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("标题把光标所在块转成 <h2>（不新增空块）")
    void headingConvertsCurrentBlock() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('heading')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<h2"), "段落应变成 h2：" + xhtml);
        assertTrue(xhtml.contains("正文"), "块的文字内容要跟着搬过去：" + xhtml);
        // 原来的 <p> 应当被换掉而不是并列多出一个
        assertFalse(xhtml.contains("<p>正文</p>"), "原段落不应残留：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("引用在段落与 <blockquote> 之间可逆切换")
    void quoteTogglesWithParagraph() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")));
        assertTrue(serialized().contains("<blockquote"), "应变成引用：" + serialized());
        assertWellFormedXhtml(serialized());

        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")));
        String xhtml = serialized();
        assertFalse(xhtml.contains("<blockquote"), "再点一次应退回段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("分隔线插在段落之后并补一个空段落，序列化为 <hr/>")
    void ruleInsertsHrAndTrailingParagraph() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('rule')")));

        String xhtml = serialized();
        // XMLSerializer 会写成 <hr />（带空格），两种写法都合法，别只认一种
        assertTrue(xhtml.matches("(?s).*<hr\\s*/>.*"), "分隔线必须自闭合：" + xhtml);
        // 分隔线之后要有落点，否则光标无处可去
        assertTrue(xhtml.indexOf("<hr") < xhtml.lastIndexOf("<p"), "hr 之后应有空段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("插入图片：img 元素落在光标处，序列化为自闭合标签")
    void insertImageAtCaret() throws Exception {
        caretIntoParagraph();
        String tag = "<img src=\"images/cover.png\" alt=\"封面\"/>";
        assertTrue(Boolean.TRUE.equals(insertHtml(tag)), "在光标处插入 img 应成功");

        String xhtml = serialized();
        assertTrue(xhtml.contains("images/cover.png"), "图片地址应在正文里：" + xhtml);
        assertTrue(xhtml.matches("(?s).*<img[^>]*\\s*/>.*"),
                "img 必须自闭合，否则回写正文会校验失败：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("未就绪/未知命令不改变文档，也不抛异常")
    void unknownCommandIsNoop() throws Exception {
        selectParagraphContents();
        String before = serialized();
        assertFalse(Boolean.TRUE.equals(runScript("window.epubraFormat('nonsense')")),
                "未知命令应返回 false，让 Java 侧走降级路径");
        assertTrue(before.equals(serialized()), "失败的命令不应改动文档");
    }

    @Test
    @Timeout(60)
    @DisplayName("引用块内含多个段落时退回段落：块级孩子提出去，绝不产生 p>p")
    void quoteWithBlocksExitsToParagraphWithoutIllegalNesting() throws Exception {
        // 直接构造引用块含两个段落的形态
        runScript("(function () {"
                + " var body = document.body;"
                + " var p0 = body.querySelector('p');"
                + " var bq = document.createElementNS('http://www.w3.org/1999/xhtml', 'blockquote');"
                + " var p1 = document.createElementNS('http://www.w3.org/1999/xhtml', 'p');"
                + " p1.appendChild(document.createTextNode('引文一'));"
                + " var p2 = document.createElementNS('http://www.w3.org/1999/xhtml', 'p');"
                + " p2.appendChild(document.createTextNode('引文二'));"
                + " bq.appendChild(p1); bq.appendChild(p2);"
                + " body.replaceChild(bq, p0);"
                + " return true; })()");
        // 光标落进引用块内
        runScript("(function () {"
                + " var p = document.body.querySelector('blockquote p');"
                + " var r = document.createRange(); r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('quote')")),
                "引用块再点「引用」应退回段落");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<p><p>"), "绝不能产生 p>p 非法嵌套：" + body);
        assertTrue(body.contains("引文一") && body.contains("引文二"),
                "两段引文都要保留：" + body);
        assertWellFormedXhtml(serialized());
    }
}
