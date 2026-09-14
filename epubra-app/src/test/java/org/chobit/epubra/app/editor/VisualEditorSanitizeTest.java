package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回写净化与粘贴白名单：写进书里的字节必须干净。
 *
 * <p>序列化路径上挂着四道净化（stripInjected / tidyHeadWhitespace / rescueStrayNodes / pruneEmptyInline / pruneEmptyLists），顺序不能乱、且都在 clone 上做。本类守「哪些东西绝不该出现在回写结果里」：空壳标签、空列表、注入脚本、body 外残留。
 *
 * <p>脚手架（WebView 启动 / 文档加载 / 选区清理 / DOM 断言助手）见
 * {@link VisualEditorTestSupport}。
 */
class VisualEditorSanitizeTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("序列化剔除空强调标签（含空白壳与嵌套空壳），带图片等内容的保留")
    void serializePrunesEmptyInlineTags() throws Exception {
        // 直接在 DOM 里注入各类空壳（模拟历史文档 / 粘贴带进来的），验证回写兜底净化
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " p.appendChild(document.createElementNS('http://www.w3.org/1999/xhtml', 'strong'));"
                + " var emBlank = document.createElementNS('http://www.w3.org/1999/xhtml', 'em');"
                + " emBlank.appendChild(document.createTextNode('  '));"
                + " p.appendChild(emBlank);"
                + " var shell = document.createElementNS('http://www.w3.org/1999/xhtml', 'strong');"
                + " shell.appendChild(document.createElementNS('http://www.w3.org/1999/xhtml', 'em'));"
                + " p.appendChild(shell);"
                + " var alias = document.createElementNS('http://www.w3.org/1999/xhtml', 'b');"
                + " p.appendChild(alias);"
                + " var withImg = document.createElementNS('http://www.w3.org/1999/xhtml', 'em');"
                + " var img = document.createElementNS('http://www.w3.org/1999/xhtml', 'img');"
                + " img.setAttribute('src', 'x.png');"
                + " withImg.appendChild(img);"
                + " p.appendChild(withImg);"
                + " return true; })()");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertFalse(body.contains("<strong"), "空 <strong> 与嵌套空壳都应被剔除：" + body);
        assertFalse(body.contains("<b>"), "别名 <b> 的空壳也应被剔除：" + body);
        assertTrue(body.contains("<em><img"), "带 <img> 内容的 <em> 必须保留：" + body);
        assertTrue(body.contains("x.png"), "图片本身不能被动：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("body 被清空后点格式按钮：直接在 body 内建块，绝不把标签打到 body 外或插去源码区")
    void emptyBodyFormattingCreatesBlockInsideBody() throws Exception {
        // 清空 body，光标落在 body 本身（topBlock 的越界形态）
        runScript("(function () {"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var r = document.createRange(); r.selectNodeContents(b); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "空 body 上点「列表」应建列表（返回 true，触发源码 fallback 会把骨架插到源码区）");
        // 落点看实时 DOM：#58 要守的是「块建在 body 内、不打到 body 外」。而空列表按 #60
        // 不写进书——所以序列化结果里反而**不该**出现 <ul>。
        assertTrue(bodyHasTag("ul"), "body 内应出现列表（实时 DOM）：" + bodyOf(serialized()));
        assertFalse(bodyOf(serialized()).contains("<ul"),
                "只有空壳的列表不该被回写进书（#60）：" + bodyOf(serialized()));
        assertWellFormedXhtml(serialized());

        // 再清空，试段落按钮
        runScript("(function () {"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var r = document.createRange(); r.selectNodeContents(b); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('paragraph')")),
                "空 body 上点「段落」应建段落");
        String paraBody = bodyOf(serialized());
        assertTrue(paraBody.contains("<p"), "body 内应出现段落：" + paraBody);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("历史损坏残留：body 之外的元素在回写时收编回 body 内")
    void strayNodesOutsideBodyRescuedOnSerialize() throws Exception {
        // 模拟历史损坏：把段落挂到 body 之外（html 层）
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " document.body.parentNode.appendChild(p);"
                + " return true; })()");
        assertFalse(bodyOf(serialized()).isEmpty(), "序列化输出应有 body");

        String body = bodyOf(serialized());
        assertTrue(body.contains("<p>正文</p>"), "body 外的段落应在回写时收编回 body：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("head 不会被残留节点收编逻辑误搬进 body")
    void headStaysOutsideBodyThroughStrayRescue() throws Exception {
        // rescueStrayNodes 曾用 root.head 判定哪个节点是 head，而文档按 XML 解析时
        // Element 上并不保证有 head 属性（那是 HTMLDocument 的接口）：取到 undefined 后
        // 判定恒真，head 就会被当成「body 之外的残留」搬进 body，注入的样式与脚本全乱。
        loadEditable("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>第一章</title></head>"
                + "<body><p>正文</p></body></html>");
        String out = serialized();

        assertTrue(out.contains("<head"), "序列化结果应当仍带 head：" + out);
        assertTrue(out.indexOf("<head") < out.indexOf("<body"), "head 必须排在 body 之前：" + out);
        assertFalse(bodyOf(out).contains("head"), "head 不得被收编进 body：" + bodyOf(out));
    }

    @Test
    @Timeout(60)
    @DisplayName("粘贴净化：剥掉危险标签/事件属性/内联样式，b 归并成 strong")
    void pasteSanitizerKeepsOnlyWhitelistedMarkup() throws Exception {
        String dirty = "<div style=\"color:red\"><b>粗体</b>"
                + "<script>evil()</script>"
                + "<span onclick=\"steal()\">普通</span></div>"
                + "<p class=\"x\" style=\"margin:0\">段落</p>"
                + "<img src=\"a.png\" onerror=\"boom()\"/>"
                + "<a href=\"javascript:alert(1)\">坏链</a>";
        Object cleaned = sanitize(dirty);
        assertTrue(cleaned instanceof String, "净化器应返回序列化后的片段");
        String out = (String) cleaned;

        assertFalse(out.contains("<script"), "脚本必须被丢掉：" + out);
        assertFalse(out.contains("onclick"), "事件属性必须被丢掉：" + out);
        assertFalse(out.contains("onerror"), "事件属性必须被丢掉：" + out);
        assertFalse(out.contains("style="), "内联样式必须被丢掉：" + out);
        assertFalse(out.contains("class="), "外来 class 必须被丢掉：" + out);
        assertTrue(out.contains("<strong>粗体</strong>"), "b 应归并成 strong：" + out);
        assertTrue(out.contains("普通"), "未知标签要拆掉但保留文字：" + out);
        assertTrue(out.contains("src=\"a.png\""), "img 的 src 应保留：" + out);
        assertFalse(out.contains("javascript:"), "危险 href 必须被丢掉：" + out);
        assertTrue(out.contains("段落"), "段落文字不能丢：" + out);
    }

    @Test
    @Timeout(60)
    @DisplayName("回写序列化不携带编辑脚本——正文里不应混入任何 <script>")
    void serializedOutputCarriesNoEditorScript() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")));
        String xhtml = serialized();

        assertTrue(xhtml.contains("<strong"), "命令本身要生效：" + xhtml);
        assertFalse(xhtml.contains("<script"),
                "编辑脚本必须从回写结果里剥掉，否则每次编辑都往书里灌几百行 JS：" + xhtml);
        assertFalse(xhtml.contains("epubraFormat"), xhtml);
        assertWellFormedXhtml(xhtml);
    }
}
