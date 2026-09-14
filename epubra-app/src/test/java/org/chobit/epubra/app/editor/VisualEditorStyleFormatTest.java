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

    /** 数子串出现次数（用来断言「只有一层 span」这类结构约束）。 */
    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
