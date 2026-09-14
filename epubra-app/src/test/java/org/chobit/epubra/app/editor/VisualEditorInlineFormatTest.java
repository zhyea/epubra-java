package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行内格式（加粗 / 斜体 / 下划线 / 删除线 / 行内代码 / 链接）与其工具条点亮语义。
 *
 * <p>行内格式的契约集中在两处：<b>产出确定性标签</b>（strong / em / u / del / code，绝不产出 &lt;b&gt; 或行内 style）与<b>切换对称性</b>（点亮 ⇔ 选区相交 ⇔ 点击移除，见 #56）。本类把这两条连同「光标态不插入空标签」「不安全 URL 拒绝」放在一起——改 toggleInline / epubraQuery 时只需看这一个文件。
 *
 * <p>脚手架（WebView 启动 / 文档加载 / 选区清理 / DOM 断言助手）见
 * {@link VisualEditorTestSupport}。
 */
class VisualEditorInlineFormatTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("加粗把选中内容包成 <strong>，且序列化后仍是合法 XHTML")
    void boldWrapsSelectionIntoStrong() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")),
                "在正文选区上执行加粗应成功");

        String xhtml = serialized();
        assertTrue(xhtml.contains("<strong"), "应产出 <strong>，而不是 <b> 或行内 style：" + xhtml);
        assertTrue(xhtml.contains("正文"), "原文本不能丢：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("斜体包成 <em>")
    void italicWrapsSelectionIntoEm() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")));

        String xhtml = serialized();
        assertTrue(xhtml.contains("<em"), "应产出 <em>，而不是 <i>：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("无选区点行内格式按钮不再插入空的 <strong>/<em>/<code> 占位标签")
    void collapsedCaretFormattingDoesNotInsertEmptyTags() throws Exception {
        caretIntoParagraph();
        // 光标收进块内后确保没有选区（collapse 成光标）
        runScript("(function () {"
                + " var s = window.getSelection();"
                + " if (s.rangeCount) { s.getRangeAt(0).collapse(true); }"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('bold')")),
                "光标态命令按「有意不作为」处理（返回 true，避免 Java 侧 fallback 去源码区插片段）");
        runScript("window.epubraFormat('italic')");
        runScript("window.epubraFormat('code')");

        String xhtml = serialized();
        assertFalse(bodyHasTag("strong"), "无选区不该出现空 <strong>：" + xhtml);
        assertFalse(bodyHasTag("em"), "无选区不该出现空 <em>：" + xhtml);
        assertFalse(bodyHasTag("code"), "无选区不该出现空 <code>：" + xhtml);
        assertTrue(xhtml.contains("正文"), "原有文字不能被动：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("光标落在斜体文字内点「斜体」仍取消整段斜体（#54 保留行为）")
    void caretInsideEmStillTogglesOff() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        // 光标收进 <em> 内部
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")),
                "光标在格式内点按钮 = 取消（返回 true）");

        assertFalse(bodyHasTag("em"), "光标在 <em> 内点「斜体」应拆掉包裹：" + serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("无选区插入链接不再产生空 <a href>")
    void collapsedCaretLinkDoesNotInsertEmptyAnchor() throws Exception {
        caretIntoParagraph();
        runScript("(function () {"
                + " var s = window.getSelection();"
                + " if (s.rangeCount) { s.getRangeAt(0).collapse(true); }"
                + " return true; })()");

        assertTrue(Boolean.TRUE.equals(withMember("__testHref", "https://example.com",
                        "window.epubraFormat('link', window.__testHref)")),
                "光标态插链接同样按「有意不作为」处理");
        String xhtml = serialized();
        assertFalse(bodyHasTag("a"), "无选区不该出现空 <a href>：" + xhtml);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("下划线 / 删除线 / 行内代码分别产出 <u> / <del> / <code>")
    void inlineCommandsProduceSemanticTags() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('underline')")));
        assertTrue(serialized().matches("(?s).*<u[\\s>].*"),
                "下划线应产出 <u>（不是 <ul>）：" + serialized());
        assertWellFormedXhtml(serialized());

        loadEditableDocument();
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('strike')")));
        assertTrue(serialized().contains("<del"), "删除线应产出 <del> 而非 <s>/<strike>：" + serialized());
        assertWellFormedXhtml(serialized());

        loadEditableDocument();
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('code')")));
        assertTrue(serialized().contains("<code"), "行内代码应产出 <code>：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("行内格式（加粗/斜体/下划线/删除线）再点一次取消包裹，不再嵌套")
    void inlineFormatsToggleOff() throws Exception {
        String[][] cases = {{"bold", "strong"}, {"italic", "em"},
                {"underline", "u"}, {"strike", "del"}};
        for (String[] c : cases) {
            loadEditableDocument();
            selectParagraphContents();
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('" + c[0] + "')")),
                    c[0] + " 第一次应用应成功");
            Object active = runScript("window.epubraQuery()");
            assertTrue(active instanceof String && ((String) active).contains(c[0]),
                    "刚应用 " + c[0] + " 应报告激活态（工具条靠它点亮），实际：" + active);

            // wrapInline 之后选区落在包裹层内容上，第二次应用走的是「拆层」路径
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('" + c[0] + "')")),
                    c[0] + " 第二次应用应取消包裹");

            String xhtml = serialized();
            assertFalse(bodyHasTag(c[1]),
                    c[0] + " 取消后不应残留 <" + c[1] + ">：" + xhtml);
            assertTrue(xhtml.contains("正文"), "取消包裹不能丢文字：" + xhtml);
            assertWellFormedXhtml(xhtml);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("行内语义标签在画布上有视觉样式：em 斜体 / strong 加粗 / u 下划线")
    void inlineSemanticTagsCarryVisualStyles() throws Exception {
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " p.innerHTML = '前<em>斜</em>中<strong>粗</strong>后<u>下</u>';"
                + " return true; })()");
        Object report = runScript("(function () {"
                + " function styleOf(sel, prop) {"
                + "   var el = document.body.querySelector(sel);"
                + "   return el ? document.defaultView.getComputedStyle(el)[prop] : 'MISSING';"
                + " }"
                + " return styleOf('em', 'fontStyle') + '|' + styleOf('strong', 'fontWeight')"
                + "      + '|' + styleOf('u', 'textDecorationLine');"
                + "})()");
        // computed style 只能证明 CSS 链路通（引擎不做合成斜体，中文斜体的「可见」
        // 由 Theme.previewStyleCss 的 em 字体栈负责：西文真斜体字面 + 楷体替代）
        assertEquals("italic|700|underline", String.valueOf(report),
                "行内语义标签的 computed style 被破坏，画布上强调格式会隐形");
    }

    @Test
    @Timeout(60)
    @DisplayName("链接包成 <a href>；javascript: 这类地址被拒绝且不改文档")
    void linkWrapsSelectionAndRejectsUnsafeUrl() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(
                runScript("window.epubraFormat('link', 'https://example.com/a?b=1')")));
        String xhtml = serialized();
        assertTrue(xhtml.contains("<a href=\"https://example.com/a?b=1\""),
                "选区应被包成带 href 的链接：" + xhtml);
        assertWellFormedXhtml(xhtml);

        loadEditableDocument();
        selectParagraphContents();
        String before = serialized();
        assertFalse(Boolean.TRUE.equals(
                        runScript("window.epubraFormat('link', 'javascript:alert(1)')")),
                "javascript: 地址必须被拒绝");
        assertTrue(before.equals(serialized()), "被拒绝的链接不应改动文档");
    }

    @Test
    @Timeout(60)
    @DisplayName("取消链接：拆掉 <a> 保留文字；queryLink 可回填当前链接地址")
    void unlinkRemovesAnchorKeepsText() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(
                runScript("window.epubraFormat('link', 'https://example.com/x')")));
        Object href = runScript("window.epubraQueryLink()");
        assertTrue(String.valueOf(href).equals("https://example.com/x"),
                "包裹后选区在链接内，queryLink 应回填地址，实际：" + href);

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('unlink')")),
                "取消链接应成功");
        String xhtml = serialized();
        assertFalse(bodyHasTag("a"), "取消后不应残留 <a>：" + xhtml);
        assertTrue(xhtml.contains("正文"), "链接里的文字要保留：" + xhtml);
        assertTrue(String.valueOf(runScript("window.epubraQueryLink()")).isEmpty(),
                "取消后 queryLink 应为空串");
        assertWellFormedXhtml(xhtml);

        // 光标不在链接里时取消链接是无操作
        loadEditableDocument();
        selectParagraphContents();
        assertFalse(Boolean.TRUE.equals(runScript("window.epubraFormat('unlink')")),
                "不在链接内时 unlink 应返回 false");
    }

    @Test
    @Timeout(60)
    @DisplayName("光标状态查询：报告当前生效的格式名，供工具条点亮")
    void queryReportsActiveFormats() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraQuery().indexOf('paragraph') >= 0")),
                "光标在段落里应报告 paragraph");

        runScript("window.epubraFormat('bold')");
        Object active = runScript("window.epubraQuery()");
        assertTrue(active instanceof String && ((String) active).contains("bold"),
                "刚加粗过，应报告 bold，实际：" + active);
        assertTrue(((String) active).contains("paragraph"),
                "块级状态也应一起报告，实际：" + active);
    }

    @Test
    @Timeout(60)
    @DisplayName("斜体后光标落回 / 重新选中，epubraQuery 与选区上报流都仍报 italic（工具条点亮的前置契约）")
    void italicStillReportedAfterReactivation() throws Exception {
        // 装 stub 桥记录 onSelectionChanged 上报流
        runScript("window.__selReports = [];"
                + "window.epubraBridge = {"
                + " onSelectionChanged: function (q) { window.__selReports.push(q); },"
                + " onEdited: function () {}, onUndo: function () {}, onRedo: function () {} };");

        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        runScript("document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}))");

        // 用户操作模拟：点别处收起选区，再把光标落回斜体文字上
        runScript("(function () {"
                + " var s = window.getSelection(); s.removeAllRanges();"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        Object caretQuery = runScript("window.epubraQuery()");
        assertTrue(String.valueOf(caretQuery).contains("italic"),
                "光标落在斜体文字上时 epubraQuery 必须报 italic，实际：" + caretQuery);

        // 再模拟拖选斜体文字
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        Object selectQuery = runScript("window.epubraQuery()");
        Object reports = runScript("window.__selReports.join(' || ')");
        assertTrue(String.valueOf(selectQuery).contains("italic"),
                "重新选中斜体文字时 epubraQuery 必须报 italic，实际：" + selectQuery);
        assertTrue(String.valueOf(reports).contains("italic"),
                "mouseup / selectionchange 上报流必须包含 italic，实际：" + reports);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区内只要含有斜体文字（部分覆盖）也点亮 italic")
    void partialSelectionAcrossEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        // p 变成 <p><em>正文</em></p>；在包裹外补一段文字，构造部分覆盖的选区
        runScript("document.body.querySelector('p').appendChild(document.createTextNode('续写'))");

        // 选区：斜体文字的前半段 + 包裹外的「续」字——混有格式外文字也应点亮
        Object q = runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var tail = em.nextSibling;"
                + " var r = document.createRange();"
                + " r.setStart(em.firstChild, 0); r.setEnd(tail, 1);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "选区内含有斜体文字就应报 italic（亮 ⇔ 可移除），实际：" + q);
    }

    @Test
    @Timeout(60)
    @DisplayName("部分覆盖选区点「斜体」= 移除选区内相交的 <em>（整标签拆掉，文字保留）")
    void partialSelectionTogglesItalicOffRemovesTag() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        runScript("document.body.querySelector('p').appendChild(document.createTextNode('续写'))");

        // 部分覆盖选区（斜体前半段 + 包裹外的字），点「斜体」→ 移除 <em>
        assertTrue(Boolean.TRUE.equals(runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var tail = em.nextSibling;"
                + " var r = document.createRange();"
                + " r.setStart(em.firstChild, 0); r.setEnd(tail, 1);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraFormat('italic'); })()")),
                "点亮状态下点击应执行移除（返回 true）");

        String body = bodyOf(serialized());
        assertFalse(body.contains("<em>"), "选区内相交的 <em> 应被移除：" + body);
        assertTrue(body.contains("正文") && body.contains("续写"),
                "文字一律保留：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("整段选中完全覆盖斜体包裹（三击选段形态）也点亮 italic")
    void fullParagraphSelectionCoveringEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");

        // 三击选段形态：选区起点/终点都在 p 上（包裹之外），但语义上完整覆盖了 em。
        // #54 用户实测「把斜体字选中后按钮不亮」正是这种形态。
        Object q = runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange(); r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "完整覆盖斜体包裹的整段选区应报 italic，实际：" + q);
    }

    @Test
    @Timeout(60)
    @DisplayName("整段选中后点「斜体」应拆掉包裹（toggle off），而不是再嵌套一层")
    void fullParagraphSelectionTogglesItalicOff() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");
        assertTrue(Boolean.TRUE.equals(runScript("!!document.body.querySelector('em')")),
                "前置确认：斜体已设置");

        // 再次整段选中（起点在包裹外），点「斜体」= 取消
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('italic')")),
                "整段选中已斜体内容后点「斜体」应执行取消");
        String serialized = serialized();
        assertFalse(bodyHasTag("em"),
                "取消后不应再有 <em>，实际：" + serialized);
        assertFalse(serialized.contains("<em><em>"),
                "绝不能嵌套包裹（取消被实现成了再加一层），实际：" + serialized);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区两端都在斜体内才点亮 italic")
    void fullSelectionInsideEmReportsItalic() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('italic')");

        Object q = runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return window.epubraQuery(); })()");
        assertTrue(String.valueOf(q).contains("italic"),
                "两端都在 em 内的选区应报 italic，实际：" + q);
    }
}
