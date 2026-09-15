package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具条样式四件套（字体 / 字号 / 文字颜色 / 对齐）的真实 WebView 契约。
 *
 * <p>这四类样式在 XHTML 里没有对应标签，只能落到内联 {@code style} 上，于是本类的重心是
 * 三条容易写成「门禁全绿但功能没生效」的口径：
 * <ol>
 *   <li><b>合并而不是覆盖</b>——同一处连改字号再改颜色必须落在同一层 {@code span} 上，
 *       且两条声明都在。把 {@code style} 直接 setAttribute 覆盖就会把前一条冲掉。</li>
 *   <li><b>不留空壳、不叠层</b>——清档（「默认」）后不能留下裸 {@code span}；
 *       整段重选后再改样式不能一层层套 {@code span}（{@code mergeStyledSpans} /
 *       {@code unwrapBareSpans} 就在 {@code serialize()} 的 clone 上收这两件事）。</li>
 *   <li><b>不吞作者自己的声明</b>——正文里 {@code text-indent: 2em} 这类排版信息很常见，
 *       工具条改个对齐把它抹掉就是数据丢失（{@code writeStyle} 只重排受管的四个属性）。</li>
 * </ol>
 *
 * <p>对齐落在<b>块</b>上、字体/字号/颜色落在<b>行内</b>；左对齐是默认值，写进正文纯属噪音，
 * 命中「默认」档时是清除而不是写 {@code text-align: left}。
 *
 * <p>本批新增两组用例，钉住两条用户实测出来的口径：
 * <ol>
 *   <li><b>「同一样式再设一次」要按选区拆分</b>——选区端点切在既有样式元素中间时，
 *       只有选中的那一段换值；判据是 {@code getComputedStyle}（序列化文本看不出
 *       「外壳 18px、内壳 24px」这种内层胜出的情形）。</li>
 *   <li><b>「清除格式」</b>一次清掉受控四类声明、强调类包裹与块结构，但不碰作者自己写的
 *       {@code text-indent} 这类排版声明，也不连累未选中的部分。</li>
 * </ol>
 *
 * <p>脚手架见 {@link VisualEditorTestSupport}。
 */
class VisualEditorStyleFormatTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("字号：选区被包成带受控内联样式的 span，序列化后仍是合法 XHTML")
    void fontSizeWrapsSelectionIntoStyledSpan() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size', '18px')")),
                "选区上设字号应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertTrue(body.contains("font-size: 18px"),
                "字号只能落在内联 style 上（XHTML 没有字号标签）：" + body);
        assertTrue(body.contains("<span"), "字号应包在 span 上：" + body);
        assertTrue(body.contains("正文"), "原文本不能丢：" + body);
        assertWellFormedXhtml(xhtml);

        // 选区此刻落在 span 内容上，回显通道必须能报出字号——工具条靠它回填下拉框
        Object state = runScript("window.epubraQueryStyle()");
        assertTrue(String.valueOf(state).contains("font-size=18px"),
                "epubraQueryStyle 应回显字号，实际：" + state);
    }

    @Test
    @Timeout(60)
    @DisplayName("同一处连改字号与颜色落在同一层 span，两条声明都在（读-改-写，不是覆盖）")
    void fontSizeThenColorMergeIntoSingleSpan() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('size', '18px')");
        runScript("window.epubraFormat('color', '#c00000')");

        String body = bodyOf(serialized());
        assertEquals(1, occurrences(body, "<span"),
                "先字号后颜色必须合并进同一层 span，不能各套一层：" + body);
        assertTrue(body.contains("font-size: 18px"), "前一条声明被冲掉了：" + body);
        assertTrue(body.contains("color: #c00000"), "颜色没写进去：" + body);
    }

    @Test
    @Timeout(60)
    @DisplayName("字号步进：按固定档位表跳档；到顶 / 到底有意不动，但必须返回 true")
    void fontSizeStepWalksTheFixedLadder() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size', '18px')")),
                "前置确认：先把字号设到 18px");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size-up')")),
                "放大一档应成功");
        assertTrue(bodyOf(serialized()).contains("font-size: 20px"),
                "18px 的下一档是 20px：" + bodyOf(serialized()));

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size-down')")),
                "缩小一档应成功");
        assertTrue(bodyOf(serialized()).contains("font-size: 18px"),
                "20px 的上一档是 18px：" + bodyOf(serialized()));

        // 到顶：72px 已是最大档 → 有意不作为，但**必须返回 true**。返回 false 会让 Java 侧
        // 判定「命令被拒」而退回源码区插片段（#61 口径），那会在正文里凭空塞一段 HTML。
        runScript("window.epubraFormat('size', '72px')");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size-up')")),
                "到顶后放大仍要返回 true（有意不作为，不是失败）");
        assertTrue(bodyOf(serialized()).contains("font-size: 72px"),
                "到顶后不该被改成别的值：" + bodyOf(serialized()));

        // 到底：10px 是最小档
        runScript("window.epubraFormat('size', '10px')");
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size-down')")),
                "到底后缩小仍要返回 true（有意不作为）");
        assertTrue(bodyOf(serialized()).contains("font-size: 10px"),
                "到底后不该被改成别的值：" + bodyOf(serialized()));
    }

    @Test
    @Timeout(60)
    @DisplayName("清档（「默认」）后不留裸 span，也不残留声明")
    void clearingStyleLeavesNoBareSpan() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('size', '18px')");
        assertTrue(bodyOf(serialized()).contains("font-size: 18px"),
                "前置确认：样式已写入（否则本用例测的是别的东西）");

        runScript("window.epubraFormat('size', '')");
        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertFalse(body.contains("<span"),
                "清档后不该留下不带任何属性的裸 span：" + body);
        assertFalse(body.contains("font-size"), "清档后不该残留字号声明：" + body);
        assertTrue(body.contains("正文"), "清档不能丢文字：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("整段重选后再改样式不叠层：span 套 span 会被合并成一层")
    void reselectingParagraphAndRestylingDoesNotNestSpans() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('size', '18px')");

        // 用户重新整段选中（三击选段形态），此时选区内容恰好是上一层那个 span
        selectParagraphContents();
        runScript("window.epubraFormat('color', '#c00000')");

        String body = bodyOf(serialized());
        assertEquals(1, occurrences(body, "<span"),
                "重新整段后改样式不得层层嵌套 span：" + body);
        assertTrue(body.contains("font-size: 18px") && body.contains("color: #c00000"),
                "合并必须保留两侧的声明：" + body);
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("对齐落在块上；「左对齐」是默认值，命中即清除而不是写进正文")
    void alignAppliesToBlockAndLeftClears() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('align', 'center')")),
                "居中对齐应成功");

        String body = bodyOf(serialized());
        assertTrue(body.contains("text-align: center"), "对齐应写到块上：" + body);
        assertTrue(body.matches("(?s).*<p\\s+style=[^>]*>.*"),
                "对齐要写在段落自己身上，而不是新加的 span：" + body);
        assertFalse(body.contains("<span"), "对齐不该产生行内包裹：" + body);

        runScript("window.epubraFormat('align', 'left')");
        String cleared = bodyOf(serialized());
        assertFalse(cleared.contains("text-align"),
                "左对齐 = 默认值，应清除声明（写进正文纯属噪音）：" + cleared);
        assertTrue(cleared.contains("正文"), "清除声明不能丢文字：" + cleared);
    }

    @Test
    @Timeout(60)
    @DisplayName("改对齐不得抹掉作者自己在块上写的排版声明（如 text-indent）")
    void alignKeepsAuthorWrittenDeclarations() throws Exception {
        // EPUB 正文最常见的写法之一：段落自带首行缩进
        runScript("document.body.querySelector('p').setAttribute('style', 'text-indent: 2em')");
        selectParagraphContents();
        runScript("window.epubraFormat('align', 'center')");

        String body = bodyOf(serialized());
        assertTrue(body.contains("text-indent: 2em"),
                "工具条只该管自己的四个属性，作者写的排版声明必须原样保留：" + body);
        assertTrue(body.contains("text-align: center"), "受管属性仍要写进去：" + body);
    }

    @Test
    @Timeout(60)
    @DisplayName("光标收起时改颜色落到所在块上，且 epubraQueryStyle 能回显")
    void caretStyleLandsOnBlockAndIsEchoed() throws Exception {
        caretIntoParagraph();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('color', '#c00000')")),
                "光标态改颜色应成功（落在所在块上，用户不划选也有反馈）");

        String body = bodyOf(serialized());
        assertTrue(body.contains("color: #c00000"), "颜色应落在块上：" + body);
        assertTrue(body.matches("(?s).*<p\\s+style=[^>]*>.*"),
                "光标态的颜色要写在段落自己身上：" + body);

        Object state = runScript("window.epubraQueryStyle()");
        assertTrue(String.valueOf(state).contains("color=#c00000"),
                "epubraQueryStyle 必须回显当前颜色（工具条靠它回填），实际：" + state);
    }

    @Test
    @Timeout(60)
    @DisplayName("对齐（块级）与字体（行内）互不干扰，可共存")
    void blockAlignAndInlineFontCoexist() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('align', 'center')");
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(withMember("__testFont", "SimSun, 宋体, serif",
                "window.epubraFormat('font', window.__testFont)")), "字体命令应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertTrue(body.contains("text-align: center"), "块级声明不能丢：" + body);
        assertTrue(body.contains("font-family: SimSun, 宋体, serif"),
                "行内字体栈要原样写进去：" + body);
        assertEquals(1, occurrences(body, "<span"), "字体只包一层 span：" + body);
        assertWellFormedXhtml(xhtml);

        Object state = runScript("window.epubraQueryStyle()");
        assertTrue(String.valueOf(state).contains("font-family=SimSun"),
                "回显串要带出字体：实际：" + state);
        assertTrue(String.valueOf(state).contains("text-align=center"),
                "回显串要带出块级对齐：实际：" + state);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区切在既有样式元素中间时，只改中选的那一段（前半段保留原值）")
    void restylingPartOfStyledRunSplitsTheStyle() throws Exception {
        loadEditable(styledRunDocument());
        assertTrue(Boolean.TRUE.equals(selectText("文内")), "测试脚手架：应能选中 span 里的「文内」");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size', '24px')")),
                "在带样式的文字中间设字号应成功");

        // 判据是**计算样式**：只看序列化文本会漏掉「外壳 18px、内壳 24px，内层胜出」这种写法
        assertEquals("24px", computedOf("文内", "fontSize"),
                "中选的那一段应换成新值（旧样式壳留在外层也不能赢）");
        assertEquals("18px", computedOf("正", "fontSize"),
                "没选中的那一段必须保留原样式（拆分不能让整条样式串一起变）");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertTrue(body.contains("font-size: 18px") && body.contains("font-size: 24px"),
                "两个值都该在正文里（一段是旧的、一段是新的）：" + body);
        // 拆分 = 中选的那一段**自己**一层元素，前后两段仍是原来那层（样式各归各的）
        assertEquals(3, occurrences(body, "<span"),
                "中选的一段应单独成层、前后两段各留原层：" + body);
        assertTrue(body.contains("正") && body.contains("文内") && body.contains("容"),
                "只改样式，文字不能丢：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("选区从样式元素中间一直到段尾时，新值不被「克隆出来的旧样式壳」压住")
    void restylingTailOfStyledRunDoesNotKeepOldValue() throws Exception {
        // 用户实测的现象：选中「内容后面」改成 24px，画面没变化——
        // 旧实现在不能 surroundContents 时退化成「抽出 → 插入」，抽出的片段里带着
        // 克隆的 18px 外壳，它嵌在新 span 内层，于是旧值赢了。
        loadEditable(styledRunDocument());
        assertTrue(Boolean.TRUE.equals(runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var span = p.querySelector('span');"
                + " var text = span.firstChild;"
                + " var tail = p.lastChild;"
                + " var r = document.createRange();"
                + " r.setStart(text, 2);"                       // 「内」之前
                + " r.setEnd(tail, tail.nodeValue.length);"     // 段尾
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()")), "测试脚手架：应能选中「内容后面」");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size', '24px')")),
                "跨界选区的字号命令应成功");

        assertEquals("24px", computedOf("内", "fontSize"),
                "中选的「内」必须真的变成新字号（克隆的旧样式壳不能压住它）");
        assertEquals("24px", computedOf("后面", "fontSize"),
                "中选的「后面」也要跟着新字号");
        assertEquals("18px", computedOf("正", "fontSize"), "没选中的「正」保留原字号");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertEquals(2, occurrences(body, "<span"),
                "拆分后应恰好两层 span（旧的留前半段、新的包后半段），实际：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("清除格式：受控声明 + 强调包裹 + 块结构（标题）一次清掉，作者自己的声明保留")
    void clearFormatStripsStylesEmphasisAndBlockStructure() throws Exception {
        loadEditable("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
                + "<body><h2>标题<strong>加粗</strong></h2></body></html>");
        // 标题上同时挂着工具条的受控声明（字号 / 对齐）与作者自己写的排版声明（首行缩进）
        runScript("document.body.querySelector('h2').setAttribute('style',"
                + " 'font-size: 28px; text-align: center; text-indent: 2em')");
        assertTrue(Boolean.TRUE.equals(selectContentsOf("h2")), "测试脚手架：应能选中 h2 的内容");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('clear')")),
                "清除格式命令应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertFalse(body.contains("<h2"), "标题结构应退回普通段落：" + body);
        assertFalse(body.contains("<strong"), "强调类包裹应拆掉：" + body);
        assertFalse(body.contains("font-size"), "受控字号声明应清掉：" + body);
        assertFalse(body.contains("text-align"), "块级对齐声明应清掉：" + body);
        assertTrue(body.contains("text-indent: 2em"),
                "作者自己写的排版声明不归工具条管，不能清掉（那是数据丢失）：" + body);
        assertTrue(body.contains("标题") && body.contains("加粗"), "只清格式，文字必须留：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("清除格式：只把中选的列表项退回段落，其余项仍是列表")
    void clearFormatOnListTouchesOnlySelectedItems() throws Exception {
        loadEditable("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
                + "<body><ul><li>甲项</li><li>乙项</li><li>丙项</li></ul></body></html>");
        assertTrue(Boolean.TRUE.equals(selectContentsOf("li:nth-child(2)")),
                "测试脚手架：应能选中第二个列表项");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('clear')")), "清除格式应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertTrue(body.contains("<ul"), "列表本身还在（没选中的项要保住列表结构）：" + body);
        assertEquals(2, occurrences(body, "<li"), "只剩两项还挂在列表上：" + body);
        assertTrue(body.matches("(?s).*<p[^>]*>\\s*乙项\\s*</p>.*"),
                "中选的那一项应退回普通段落：" + body);
        assertTrue(body.contains("甲项") && body.contains("丙项"), "其余项不能被牵连：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("清除格式只作用于中选的那一段，未选中部分保留原样式")
    void clearFormatKeepsUnselectedPartStyled() throws Exception {
        loadEditable(styledRunDocument());
        assertTrue(Boolean.TRUE.equals(selectText("文内")), "测试脚手架：应能选中「文内」");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('clear')")), "清除格式应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertEquals("18px", computedOf("正", "fontSize"),
                "没选中的「正」仍该带原样式（清除只作用于中选的那一段）");
        assertFalse("18px".equals(computedOf("文内", "fontSize")),
                "中选的「文内」应回到默认字号，实际：" + computedOf("文内", "fontSize"));
        assertTrue(body.contains("正") && body.contains("文内") && body.contains("容"),
                "清除不能丢文字（拆分后各段仍在）：" + body);
        assertWellFormedXhtml(xhtml);
    }

    @Test
    @Timeout(60)
    @DisplayName("清除格式：引用块（含内层段落）退回普通段落")
    void clearFormatTurnsQuoteIntoParagraph() throws Exception {
        loadEditable("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
                + "<body><blockquote><p>引用的话</p></blockquote></body></html>");
        assertTrue(Boolean.TRUE.equals(selectContentsOf("blockquote")),
                "测试脚手架：应能选中引用块的内容");

        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('clear')")), "清除格式应成功");

        String xhtml = serialized();
        String body = bodyOf(xhtml);
        assertFalse(body.contains("<blockquote"), "引用结构应退回普通段落：" + body);
        assertTrue(body.contains("引用的话"), "文字必须留：" + body);
        assertTrue(occurrences(body, "<p") >= 1, "应留下段落承载原文字：" + body);
        assertWellFormedXhtml(xhtml);
    }

    // ---- 测试脚手架 --------------------------------------------------

    /** 「前面 + 一段带样式的字 + 后面」——拆分类用例共用这份正文。 */
    private static String styledRunDocument() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
                + "<body><p>前面<span style=\"font-size: 18px\">正文内容</span>后面</p></body></html>";
    }

    /** 选中正文里第一次出现的某段文字（模拟用户在已有内容里只划中几个字）。 */
    private Object selectText(String needle) throws Exception {
        return withMember("__testNeedle", needle, "(function () {"
                + " var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);"
                + " var n;"
                + " while ((n = walker.nextNode())) {"
                + "   var at = n.nodeValue.indexOf(window.__testNeedle);"
                + "   if (at < 0) { continue; }"
                + "   var r = document.createRange();"
                + "   r.setStart(n, at); r.setEnd(n, at + window.__testNeedle.length);"
                + "   var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + "   return true;"
                + " }"
                + " return false; })()");
    }

    /** 选中某个元素（选择器命中的第一个）的全部内容。 */
    private static Object selectContentsOf(String selector) throws Exception {
        return runScript("(function () {"
                + " var el = document.body.querySelector('" + selector + "');"
                + " if (!el) { return false; }"
                + " var r = document.createRange(); r.selectNodeContents(el);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
    }

    /**
     * 问浏览器：包含某段文字的元素，把某个属性算成了什么值。
     *
     * <p>「内联 style 写进 DOM 了」与「画面真的变了」是两件事——同属性嵌套时内层胜出，
     * 序列化文本看着两条声明都在，画面却还停在旧值上。所以这里一律问计算样式。
     * 查不到该文字时返回 {@code MISSING}，让断言带着实际值失败。
     */
    private String computedOf(String needle, String property) throws Exception {
        return String.valueOf(withMember("__testNeedle", needle, "(function () {"
                + " var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);"
                + " var n;"
                + " while ((n = walker.nextNode())) {"
                + "   if (n.nodeValue.indexOf(window.__testNeedle) < 0) { continue; }"
                + "   var el = n.parentNode;"
                + "   if (!el || !window.getComputedStyle) { return ''; }"
                + "   return String(getComputedStyle(el)['" + property + "']);"
                + " }"
                + " return 'MISSING'; })()"));
    }

    /** 数子串出现次数（用来断言「只有一层 span」这类结构约束）。 */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
