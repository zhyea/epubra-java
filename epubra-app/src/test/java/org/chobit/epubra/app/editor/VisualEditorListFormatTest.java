package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表（ul / ol）的转换、退回段落、层级升降与整列表选中语义。
 *
 * <p><b>列表是这套编辑器最容易出结构性问题的地方</b>：li 的 topBlock 是列表容器本身、contenteditable 会给空块塞 &lt;li&gt;&lt;br/&gt; 占位、整列表被选中时 formatBlock 找不到 li。本类只放列表，回归时定位面收窄到一处。
 *
 * <p>脚手架（WebView 启动 / 文档加载 / 选区清理 / DOM 断言助手）见
 * {@link VisualEditorTestSupport}。
 */
class VisualEditorListFormatTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("列表把光标所在块转成 <ul><li>")
    void listConvertsCurrentBlock() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<ul"), "应包出 ul：" + xhtml);
        assertTrue(xhtml.contains("<li"), "应包出 li：" + xhtml);
        assertTrue(xhtml.contains("正文"), "文字内容要保留：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("列表状态再点一次退回段落（可逆）")
    void listTogglesBackToParagraph() throws Exception {
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "已在列表里再点列表应退回段落");

        String xhtml = serialized();
        assertFalse(xhtml.contains("<ul"), "退回后不应残留列表：" + xhtml);
        assertTrue(xhtml.contains("<p"), "应恢复成段落：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("编号列表：ol 产出 <ol> 并点亮 ol 态；再点退回段落；ul 可直接换型")
    void orderedListConvertsAndToggles() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")));
        assertTrue(bodyHasTag("ol"), "应包出 ol：" + serialized());
        assertFalse(bodyHasTag("ul"), "点编号列表不应误产出 ul：" + serialized());
        assertWellFormedXhtml(serialized());
        Object active = runScript("window.epubraQuery()");
        assertTrue(active instanceof String && ((String) active).contains("ol"),
                "有序列表应报告 ol，实际：" + active);

        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")),
                "已在 ol 里再点一次应退回段落");
        assertFalse(bodyHasTag("ol"), "退回后不应残留 ol：" + serialized());
        assertTrue(serialized().contains("<p"), "应恢复成段落：" + serialized());
        assertWellFormedXhtml(serialized());

        // ul → ol 直接换型，不必先退回段落
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('ol')")),
                "在 ul 上点编号列表应换型为 ol");
        assertTrue(bodyHasTag("ol"), "应换成 ol：" + serialized());
        assertFalse(bodyHasTag("ul"), "换型后不应残留 ul：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("列表内 Tab 缩进成子列表，Shift+Tab 降级还原")
    void listTabIndentsAndShiftTabOutdents() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")));
        // 造第二个列表项；光标收进它，Tab 才有「前一项」可挂子列表（第一项缩不了）
        runScript("(function () {"
                + " var ul = document.body.querySelector('ul');"
                + " var li = document.createElementNS('http://www.w3.org/1999/xhtml', 'li');"
                + " li.textContent = '第二条';"
                + " ul.appendChild(li);"
                + " var r = document.createRange(); r.selectNodeContents(li); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");

        // 合成 Tab 键盘事件，走 document 上的捕获监听器
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', bubbles: true, cancelable: true}))");
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                + " var sub = document.querySelector('ul > li > ul > li');"
                + " return !!sub && sub.textContent === '第二条'; })()")),
                "Tab 应把当前项缩进成前一项内的子列表：" + serialized());
        assertWellFormedXhtml(serialized());

        // 缩进后光标仍留在被挪动的 li 里，Shift+Tab 直接降级
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', shiftKey: true, bubbles: true, cancelable: true}))");
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                + " var lis = document.querySelectorAll('ul > li');"
                + " return lis.length === 2 && !document.querySelector('ul li ul'); })()")),
                "Shift+Tab 应把子列表降级回顶层并删掉空子列表：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("带子列表的列表项退回段落：子列表提出去，绝不产生 p>ul 非法嵌套")
    void indentedSublistExitsToParagraphWithoutIllegalNesting() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('list')");
        // 模拟 Tab 缩进结果：外层 li 里有一个子列表
        runScript("(function () {"
                + " var li = document.body.querySelector('li');"
                + " var sub = document.createElementNS('http://www.w3.org/1999/xhtml', 'ul');"
                + " var subLi = document.createElementNS('http://www.w3.org/1999/xhtml', 'li');"
                + " subLi.appendChild(document.createTextNode('子项'));"
                + " sub.appendChild(subLi);"
                + " li.appendChild(sub);"
                + " return true; })()");
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "带子列表的列表项退回段落应成功");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<p><ul") && body.contains("</ul></p>"),
                "绝不能产生 p>ul 非法嵌套：" + body);
        assertTrue(body.contains("<ul><li>子项</li></ul>"),
                "子列表应作为独立列表保留：" + body);
        assertTrue(body.contains("正文"), "外层文字不能丢：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整列表被选中再点「列表」：所有 li 退回成段（绝不 fallback 往源码区插骨架）")
    void wholeListSelectionTogglesToParagraphs() throws Exception {
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        // 整列表选中（selectNodeContents(ul)：起点就是列表本身）
        runScript("(function () {"
                + " var ul = document.body.querySelector('ul');"
                + " var r = document.createRange(); r.selectNodeContents(ul);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                "整列表选中点「列表」应退回段落（返回 true，触发源码 fallback 会把内容搞乱）");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<ul"), "整列表退回后不应残留列表：" + body);
        assertTrue(body.contains("正文"), "文字不能丢：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("空列表（零 li / li 空 / 嵌套空子列表）绝不回写进书，有内容的列表不受影响")
    void emptyListsAreNeverWrittenBack() throws Exception {
        // ① 真实可达路径：建列表后把列表项内容删空
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript("(function () { var li = document.body.querySelector('li');"
                + " while (li.firstChild) { li.removeChild(li.firstChild); } return true; })()");
        assertTrue(bodyHasTag("ul"), "前置：实时 DOM 里列表还在（编辑中的落脚点）");
        assertFalse(bodyOf(serialized()).contains("<ul"),
                "列表项被清空后，空列表不该回写：" + bodyOf(serialized()));

        // ② 零 li 的空列表（历史损坏 / 引擎删除残留），同批正文不能受牵连
        runScript("(function () {"
                + " var ns = 'http://www.w3.org/1999/xhtml';"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " b.appendChild(document.createElementNS(ns, 'ul'));"
                + " b.appendChild(document.createElementNS(ns, 'ol'));"
                + " var p = document.createElementNS(ns, 'p');"
                + " p.appendChild(document.createTextNode('正文'));"
                + " b.appendChild(p);"
                + " return true; })()");
        String bare = bodyOf(serialized());
        assertFalse(bare.contains("<ul"), "零 li 的空 <ul> 不该回写：" + bare);
        assertFalse(bare.contains("<ol"), "零 li 的空 <ol> 不该回写：" + bare);
        assertTrue(bare.contains("<p>正文</p>"), "正文不能被净化牵连：" + bare);

        // ③ 嵌套：内层空子列表删掉，外层（有文字）必须保留
        runScript("(function () {"
                + " var ns = 'http://www.w3.org/1999/xhtml';"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var ul = document.createElementNS(ns, 'ul');"
                + " var li = document.createElementNS(ns, 'li');"
                + " li.appendChild(document.createTextNode('有条目'));"
                + " li.appendChild(document.createElementNS(ns, 'ul'));"
                + " ul.appendChild(li);"
                + " b.appendChild(ul);"
                + " return true; })()");
        String nested = bodyOf(serialized());
        assertTrue(nested.contains("有条目"), "有内容的列表不能误删：" + nested);
        assertEquals(1, nested.split("<ul", -1).length - 1,
                "只该剩外层列表（内层空子列表已净化）：" + nested);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整列表选中点「段落」：退回段落且返回 true（绝不触发源码 fallback）")
    void paragraphCommandOnWholeListSelectionDoesNotFallBackToSource() throws Exception {
        String selectWholeList = "(function () {"
                + " var L = document.body.querySelector('ul,ol');"
                + " var r = document.createRange(); r.selectNodeContents(L);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()";

        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript(selectWholeList);
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('paragraph')")),
                "整列表选中点「段落」必须返回 true——false 会让 Java 侧往源码区插 <p></p>，"
                        + "切 tab 时用源码覆盖章节，可视化改动全丢");
        String body = bodyOf(serialized());
        assertFalse(body.contains("<ul"), "整列表应退回段落：" + body);
        assertTrue(body.contains("正文"), "文字不能丢：" + body);

        // 标题对整列表没有明确语义：按「有意不作为」处理，但同样必须返回 true
        caretIntoParagraph();
        runScript("window.epubraFormat('list')");
        runScript(selectWholeList);
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('heading')")),
                "整列表选中点「标题」也必须返回 true——有意不作为，而不是触发源码 fallback");
        assertTrue(bodyOf(serialized()).contains("正文"),
                "「不作为」不等于丢内容：" + bodyOf(serialized()));
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("Tab 链式缩进：后一项并入前一项已有的同型子列表，不并出两个平级列表")
    void tabMergesIntoExistingSublistInsteadOfFragmenting() throws Exception {
        setFlatList("ul", "甲", "乙", "丙");

        caretInLi("乙");
        pressTab();
        caretInLi("丙");
        pressTab();

        String body = bodyOf(serialized());
        // 丙 的前一项此时是「甲」（乙 已挪进甲的子列表），其尾部已有 ul——必须并入而不是再开一个
        assertEquals(2, countIn(body, "<ul"),
                "丙 应并入 乙 所在的子列表；再开平级列表会得到 3 个 ul：" + body);
        assertEquals("NESTED", liParentIsTopLevel("丙"), "丙 应位于子列表里：" + body);
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                        + " var sub = document.querySelector('ul > li > ul');"
                        + " return !!sub && sub.textContent === '乙丙'; })()")),
                "子列表应同时含 乙、丙 两项：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("Tab 可缩到第三层，Shift+Tab 逐级降回顶层")
    void tabDeepensToThirdLevelAndShiftTabClimbsBack() throws Exception {
        setFlatList("ul", "甲", "乙", "丙");

        caretInLi("乙");
        pressTab();   // 乙 进 甲 的子列表（第 2 层）
        caretInLi("丙");
        pressTab();   // 丙 并入 甲 的子列表（第 2 层第二项）
        caretInLi("丙");
        pressTab();   // 丙 进 乙 的子列表（第 3 层）

        String deepBody = bodyOf(serialized());
        assertEquals(3, countIn(deepBody, "<ul"), "应形成三层列表：" + deepBody);
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                        + " var deep = document.querySelector('ul > li > ul > li > ul');"
                        + " return !!deep && deep.textContent === '丙'; })()")),
                "第三层应挂在 乙 之下：" + deepBody);
        assertWellFormedXhtml(serialized());

        pressShiftTab();
        pressShiftTab();
        pressShiftTab();

        String flat = bodyOf(serialized());
        assertEquals("TOP", liParentIsTopLevel("丙"), "连按 Shift+Tab 应把 丙 降回顶层：" + flat);
        assertEquals(3, countIn(flat, "<li"), "三项都不能丢：" + flat);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("有序列表缩进同样并成一条子列表，序号不得被拆成从 1 重来")
    void tabOnOrderedListKeepsOneSublist() throws Exception {
        setFlatList("ol", "一", "二", "三");

        caretInLi("二");
        pressTab();
        caretInLi("三");
        pressTab();

        String body = bodyOf(serialized());
        assertEquals(2, countIn(body, "<ol"),
                "同型子列表必须合并成一条，否则 三 的序号会从 1 重新开始：" + body);
        assertEquals(0, countIn(body, "<ul"), "不该混入无序列表：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("首项没有前项可挂：Tab 为无操作，绝不把制表符当文本插进正文")
    void tabOnFirstItemIsNoOp() throws Exception {
        setFlatList("ul", "甲", "乙");

        caretInLi("甲");
        pressTab();

        String body = bodyOf(serialized());
        assertEquals(1, countIn(body, "<ul"), "首项缩不了，列表结构不该变：" + body);
        assertEquals(2, countIn(body, "<li"), "两项都还在：" + body);
        assertFalse(body.contains("\t"), "Tab 不能退化成插入制表符文本：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("多层级结构合法：列表必须嵌在 li 里，不能直接相邻嵌套、不能裸 li 挂 body")
    void nestedListsStayStructurallyLegal() throws Exception {
        setFlatList("ul", "甲", "乙", "丙", "丁");

        caretInLi("乙");
        pressTab();
        caretInLi("丙");
        pressTab();
        caretInLi("丁");
        pressTab();

        String body = bodyOf(serialized());
        assertTrue(Boolean.TRUE.equals(runScript(
                "(function () {"
                        + " var bad = document.querySelectorAll('ul > ul, ol > ol, ul > ol, ol > ul,"
                        + " body > li, li > li');"
                        + " return bad.length === 0; })()")),
                "列表只能嵌在 li 内，且不能有裸 li：" + body);
        assertWellFormedXhtml(serialized());
    }

    // ------------------------------------------------------------------ 脚本与断言助手

    /** 把 body 换成一条干净的扁平列表（首元素为标签名，其余为各列表项文本）。 */
    private void setFlatList(String tag, String... items) throws Exception {
        withMember("__flat", tag + "|" + String.join("|", items), "(function () {"
                + " var parts = String(window.__flat).split('|');"
                + " var ns = 'http://www.w3.org/1999/xhtml';"
                + " var b = document.body;"
                + " while (b.firstChild) { b.removeChild(b.firstChild); }"
                + " var list = document.createElementNS(ns, parts[0]);"
                + " for (var i = 1; i < parts.length; i++) {"
                + "   var li = document.createElementNS(ns, 'li');"
                + "   li.appendChild(document.createTextNode(parts[i]));"
                + "   list.appendChild(li); }"
                + " b.appendChild(list);"
                + " return true; })()");
    }

    /** 把光标收进文本恰好等于 text 的列表项——层级演练要精确落在某一项上。 */
    private void caretInLi(String text) throws Exception {
        Object placed = withMember("__liText", text, "(function () {"
                + " var lis = document.body.querySelectorAll('li');"
                + " for (var i = 0; i < lis.length; i++) {"
                + "   if (lis[i].textContent === window.__liText) {"
                + "     var r = document.createRange(); r.selectNodeContents(lis[i]);"
                + "     var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + "     return true; } }"
                + " return false; })()");
        assertTrue(Boolean.TRUE.equals(placed), "找不到文本为「" + text + "」的列表项");
    }

    /** 合成 Tab 键盘事件，走 document 捕获阶段的监听器（真实用户按键即这条路径）。 */
    private void pressTab() throws Exception {
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', bubbles: true, cancelable: true}))");
    }

    private void pressShiftTab() throws Exception {
        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', shiftKey: true, bubbles: true, cancelable: true}))");
    }

    /** 数 body 区间内某子串出现次数——head 里嵌着编辑脚本源码，不能整篇 contains。 */
    private int countIn(String body, String needle) {
        int n = 0;
        for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /** 文本恰好等于 text 的那一项，父节点是最外层列表（TOP）还是子列表（NESTED）。 */
    private Object liParentIsTopLevel(String text) throws Exception {
        return withMember("__liText", text, "(function () {"
                + " var lis = document.body.querySelectorAll('li');"
                + " var top = document.body.querySelector('ul, ol');"
                + " for (var i = 0; i < lis.length; i++) {"
                + "   if (lis[i].textContent === window.__liText) {"
                + "     return lis[i].parentNode === top ? 'TOP' : 'NESTED'; } }"
                + " return 'MISSING'; })()");
    }
}
