package org.chobit.epubra.app.editor;

import org.chobit.epubra.app.resource.ResourceOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

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
    @DisplayName("插入单张图：img 独占一个段落，不与光标所在段落内联混排")
    void insertImageGetsItsOwnParagraph() throws Exception {
        caretIntoParagraph();
        String tag = "<img src=\"images/cover.png\" alt=\"封面\"/>";
        assertTrue(Boolean.TRUE.equals(insertHtml(tag)), "在光标处插入 img 应成功");

        String body = bodyOf(serialized());
        assertTrue(body.contains("images/cover.png"), "图片地址应在正文里：" + body);
        assertFalse(hasNestedParagraph(), "绝不能产生 p 套 p：" + body);
        // 关键断言：装着图片的那个 <p> 里不能有别的文字——否则就是「和正文挤在一段」
        assertTrue(imageOwnsItsParagraph("images/cover.png"),
                "图片必须独占一个段落，不能与光标前的文字同段：" + body);
        assertTrue(body.contains("正文"), "原段落文字必须原样保留：" + body);
        assertTrue(body.matches("(?s).*<img[^>]*\\s*/>.*"),
                "img 必须自闭合，否则回写正文会校验失败：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("光标落在段落中间插图，图片仍独占段落，且不产生 p 套 p")
    void insertImageMidParagraphAvoidsNesting() throws Exception {
        // 光标塞进「正文」两个字中间——插入点在一个 <p> 内部，最容易造出非法嵌套
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange();"
                + " r.setStart(p.firstChild, 1); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(insertHtml("<p><img src=\"images/a.png\" alt=\"a\"/></p>")));

        String body = bodyOf(serialized());
        assertFalse(hasNestedParagraph(), "p 套 p 会让章节结构损坏：" + body);
        assertTrue(imageOwnsItsParagraph("images/a.png"), "图片必须独占段落：" + body);
        assertTrue(body.contains("正文"), "段落文字必须完整保留：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("在空行处插图：空行占位被图片顶替，不残留空段落")
    void insertImageReplacesEmptyParagraph() throws Exception {
        runScript("(function () {"
                + " var X = 'http://www.w3.org/1999/xhtml';"
                + " var p = document.createElementNS(X, 'p');"
                + " p.appendChild(document.createElementNS(X, 'br'));"
                + " document.body.appendChild(p);"
                + " var r = document.createRange(); r.selectNodeContents(p); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(insertHtml("<p><img src=\"images/b.png\" alt=\"b\"/></p>")));

        String body = bodyOf(serialized());
        assertTrue(body.contains("images/b.png"), "图片应在正文里：" + body);
        assertTrue(imageOwnsItsParagraph("images/b.png"), "图片必须独占段落：" + body);
        // 空行占位（contenteditable 的 <p><br/></p>）应被图片顶替；整篇里不该再有 <br/>
        assertFalse(Boolean.TRUE.equals(runScript("!!document.body.querySelector('br')")),
                "点了空行插图，那个空行占位段落应被顶替掉而不是残留：" + body);
        assertFalse(hasNestedParagraph(), body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("一次插多张图：每张各占一个段落，不挤在同一段")
    void insertMultipleImagesEachGetsOwnParagraph() throws Exception {
        caretIntoParagraph();
        String joined = ResourceOps.joinInsertFragments(List.of(
                "<img src=\"images/a.png\" alt=\"a\"/>",
                "<img src=\"images/b.png\" alt=\"b\"/>"));
        assertTrue(Boolean.TRUE.equals(insertHtml(joined)), "多图插入应成功");

        String body = bodyOf(serialized());
        assertTrue(body.contains("images/a.png") && body.contains("images/b.png"), body);
        assertTrue(imageOwnsItsParagraph("images/a.png"), "第一张图必须独占段落：" + body);
        assertTrue(imageOwnsItsParagraph("images/b.png"), "第二张图必须独占段落：" + body);
        assertTrue(Boolean.TRUE.equals(runScript(
                        "document.body.querySelectorAll('img').length === 2")),
                "两张图都要在，不能互相吞掉：" + body);
        assertFalse(Boolean.TRUE.equals(runScript(
                        "document.body.querySelector('img[src=\"images/a.png\"]').parentNode"
                                + " === document.body"
                                + ".querySelector('img[src=\"images/b.png\"]').parentNode")),
                "两张图不能共用同一个父段落：" + body);
        assertFalse(hasNestedParagraph(), body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("在列表项里插图：图片段落在 <li> 内，且不把 <p> 塞到 <ul> 下")
    void insertImageInsideListItemStaysValid() throws Exception {
        runScript("(function () {"
                + " var X = 'http://www.w3.org/1999/xhtml';"
                + " var li = document.createElementNS(X, 'li');"
                + " li.appendChild(document.createTextNode('条目'));"
                + " var ul = document.createElementNS(X, 'ul');"
                + " ul.appendChild(li);"
                + " document.body.replaceChild(ul, document.body.querySelector('p'));"
                + " var r = document.createRange(); r.selectNodeContents(li); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(insertHtml("<p><img src=\"images/c.png\" alt=\"c\"/></p>")));

        String body = bodyOf(serialized());
        // li 能容纳 <p>，所以图片段落在光标处就地落位（不额外造列表项）——这是合法结构
        assertFalse(Boolean.TRUE.equals(runScript("!!document.body.querySelector('ul > p')")),
                "<p> 不能是 <ul> 的直接子元素：" + body);
        assertTrue(Boolean.TRUE.equals(
                        runScript("!!document.body.querySelector('ul > li > p > img')")),
                "图片段落应落在列表项内：" + body);
        assertTrue(imageOwnsItsParagraph("images/c.png"), "图片独占一个段落：" + body);
        assertTrue(body.contains("条目"), "原列表项文字保留：" + body);
        assertFalse(hasNestedParagraph(), body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("没有光标/选区时插图返回 false，走 Java 侧降级且不改动文档")
    void insertImageWithoutCaretReturnsFalse() throws Exception {
        runScript("(function () {"
                + " var s = window.getSelection(); if (s) { s.removeAllRanges(); }"
                + " return true; })()");
        String before = serialized();

        assertFalse(Boolean.TRUE.equals(insertHtml("<p><img src=\"images/x.png\" alt=\"x\"/></p>")),
                "没有选区必须返回 false——Java 侧据此走源码区降级，"
                        + "谎报 true 会让「未能插入」被说成「已插入」");
        assertTrue(before.equals(serialized()), "失败不应改动文档");
    }

    /** 装该图片的父元素必须是 {@code <p>}，且这段里除了图片没有别的文字（= 图片独占段落）。 */
    private boolean imageOwnsItsParagraph(String src) throws Exception {
        return Boolean.TRUE.equals(runScript(
                "(function () { var i = document.body.querySelector('img[src=\"" + src + "\"]');"
                        + " if (!i) { return false; }"
                        + " var p = i.parentNode;"
                        + " if (!p || p.nodeName.toLowerCase() !== 'p') { return false; }"
                        + " return !(p.textContent || '').replace(/\\s/g, '').length; })()"));
    }

    /** 文档里是否出现 {@code <p>} 套 {@code <p>}——回写进书会让章节结构损坏。 */
    private boolean hasNestedParagraph() throws Exception {
        return Boolean.TRUE.equals(runScript(
                "(function () {"
                        + " var all = document.body.getElementsByTagName('p');"
                        + " for (var i = 0; i < all.length; i++) {"
                        + "   var n = all[i].parentNode;"
                        + "   while (n && n !== document.body) {"
                        + "     if (n.nodeName && n.nodeName.toLowerCase() === 'p') { return true; }"
                        + "     n = n.parentNode;"
                        + "   }"
                        + " }"
                        + " return false; })()"));
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
