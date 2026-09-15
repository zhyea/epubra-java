package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可视化编辑器内查找 / 替换的契约（真实 WebView）。
 *
 * <p>背景：查找条原来只作用在源码区 TextArea，可视化页签上点「查找」其实在隐藏的源码区里
 * 选中了，页面上毫无可见变化——用户看到「查找功能没有生效」。修复后查找 / 替换在
 * {@code editor-script.js} 里走纯文本索引 ↔ 文本节点映射（{@code epubraFindNext / Prev /
 * ReplaceOne / ReplaceAll}），这里验证这套语义与源码区（{@code TextSearch / FindOps}）一致。
 *
 * <p>调用走与 {@code VisualEditorSession} 相同的 window 临时成员通道，不拼脚本字符串。
 */
class VisualEditorFindTest extends VisualEditorTestSupport {

    private static final String DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
            + "<body><p>前面正文内容后面</p><p>第二段正文</p></body></html>";

    private static final String MIXED_CASE_DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
            + "<body><p>Alpha body alpha</p></body></html>";

    private static final String INLINE_DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第一章</title></head>"
            + "<body><p>第一<b>段</b>正文</p><p>第二段</p></body></html>";

    /** 与 VisualEditorSession 同款通道：挂 window 临时成员 → 调命令 → 摘成员。 */
    private static Object callFind(String script, String kw, String rep, boolean cs) throws Exception {
        java.util.LinkedHashMap<String, Object> members = new java.util.LinkedHashMap<>();
        members.put("__epubraFindKeyword", kw);
        if (rep != null) {
            members.put("__epubraFindReplacement", rep);
        }
        members.put("__epubraFindCase", Boolean.valueOf(cs));
        return runScriptWithMembers(members, script);
    }

    private static Object findNext(String kw, boolean cs) throws Exception {
        return callFind("window.epubraFindNext(window.__epubraFindKeyword, window.__epubraFindCase)",
                kw, null, cs);
    }

    private static Object findPrev(String kw, boolean cs) throws Exception {
        return callFind("window.epubraFindPrev(window.__epubraFindKeyword, window.__epubraFindCase)",
                kw, null, cs);
    }

    private static Object replaceOne(String kw, String rep, boolean cs) throws Exception {
        return callFind("window.epubraReplaceOne(window.__epubraFindKeyword,"
                        + " window.__epubraFindReplacement, window.__epubraFindCase)",
                kw, rep, cs);
    }

    private static Object replaceAll(String kw, String rep, boolean cs) throws Exception {
        return callFind("window.epubraReplaceAll(window.__epubraFindKeyword,"
                        + " window.__epubraFindReplacement, window.__epubraFindCase)",
                kw, rep, cs);
    }

    /** 当前选区的纯文本（查找命中的直接证据——只看返回值会漏「选错位置」）。 */
    private static String selectedText() throws Exception {
        Object out = runScript("window.getSelection().toString()");
        return out instanceof String s ? s : "";
    }

    private static String bodyText() throws Exception {
        Object out = runScript("document.body.textContent");
        return out instanceof String s ? s : "";
    }

    @Test
    @Timeout(60)
    @DisplayName("查找下一个：逐处推进，到尾回卷到开头；选区落在命中文字上")
    void findNextMovesThroughHitsThenWraps() throws Exception {
        loadEditable(DOC);

        assertEquals("hit", findNext("正文", false), "第一次查找应直接命中");
        assertEquals("正文", selectedText(), "选区应落在命中文字上");

        assertEquals("hit", findNext("正文", false), "第二次查找应推进到第二处");
        assertEquals("正文", selectedText());

        assertEquals("wrap", findNext("正文", false), "已到末处，再找应回卷到开头");
        assertEquals("正文", selectedText());
    }

    @Test
    @Timeout(60)
    @DisplayName("查找上一个：从选区前推进，到头回卷到末尾")
    void findPrevGoesBackwardAndWraps() throws Exception {
        loadEditable(DOC);
        assertEquals("hit", findNext("正文", false), "先定位到第一处");
        assertEquals("hit", findNext("正文", false), "再定位到第二处");

        assertEquals("hit", findPrev("正文", false), "向上应回到第一处");
        assertEquals("wrap", findPrev("正文", false), "已在第一处，再向上应回卷到末尾");
        assertEquals("正文", selectedText());
    }

    @Test
    @Timeout(60)
    @DisplayName("未命中返回 miss；区分大小写与忽略大小写各自成立")
    void findHonorsCaseSensitivityAndMiss() throws Exception {
        loadEditable(MIXED_CASE_DOC);

        assertEquals("miss", findNext("不存在", false), "未找到应返回 miss");

        assertEquals("hit", findNext("alpha", true), "区分大小写：命中小写的 alpha");
        assertEquals("alpha", selectedText());

        assertEquals("wrap", findNext("alpha", false), "已在最后一处，向后找应回绕到开头命中大写 Alpha");
        assertEquals("Alpha", selectedText(), "忽略大小写时选中的是原文（大写 Alpha）");
    }

    @Test
    @Timeout(60)
    @DisplayName("替换一处：当前选区命中才替换，替换后选区落在替换词上")
    void replaceOneReplacesCurrentSelection() throws Exception {
        loadEditable(DOC);
        assertEquals("hit", findNext("正文", false), "先定位到第一处");

        assertEquals("hit", replaceOne("正文", "文字", false), "选区命中 → 替换成功");
        assertTrue(bodyText().contains("前面文字内容后面"), "替换词应落进正文：" + bodyText());
        assertEquals("文字", selectedText(), "替换后选区应落在替换词上");

        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("替换一处：当前选区不是命中时不动正文（nomatch），未选中报 miss")
    void replaceOneRequiresMatchingSelection() throws Exception {
        loadEditable(DOC);

        assertEquals("miss", replaceOne("正文", "文字", false), "没有任何选区 → miss");
        assertTrue(bodyText().contains("前面正文内容后面"), "miss 不得改正文");

        assertEquals("hit", findNext("正文内容", false));
        assertEquals("nomatch", replaceOne("正文", "文字", false), "选区文本与关键词不一致 → nomatch");
        assertTrue(bodyText().contains("前面正文内容后面"), "nomatch 不得改正文");
    }

    @Test
    @Timeout(60)
    @DisplayName("本章全部替换：计数正确（含 <b> 里的字），且支持跨行内元素的命中")
    void replaceAllReplacesAllOccurrencesIncludingAcrossInlineElements() throws Exception {
        loadEditable(INLINE_DOC);

        // 全局文本 =「第一段正文」(「段」来自 <b>) +「第二段」→「段」共 2 处
        Object count = replaceAll("段", "章节", false);
        assertEquals(2, ((Number) count).intValue(), "两处「段」都应替换");
        assertEquals("第一章节正文第二章节", bodyText(), "替换后全文：" + bodyText());

        String xhtml = serialized();
        assertWellFormedXhtml(xhtml);
        String body = bodyOf(xhtml);
        assertTrue(body.contains("第一") && body.contains("章节") && body.contains("正文"),
                "替换后文字不能丢：" + body);
    }

    @Test
    @Timeout(60)
    @DisplayName("本章全部替换：命中跨行内元素（「第一段」横跨正文与 <b>）也能整体替换")
    void replaceAllHandlesMatchesSpanningInlineElements() throws Exception {
        loadEditable(INLINE_DOC);

        Object count = replaceAll("第一段", "壹", false);
        assertEquals(1, ((Number) count).intValue(), "「第一段」跨 <p> 文本与 <b>，应作为一处命中");
        assertEquals("壹正文第二段", bodyText(), "跨元素命中应整体替换：" + bodyText());

        String xhtml = serialized();
        assertWellFormedXhtml(xhtml);
        String body = bodyOf(xhtml);
        assertTrue(body.contains("壹") && body.contains("正文") && body.contains("第二段"),
                "替换后文字不能丢：" + body);
    }

    @Test
    @Timeout(60)
    @DisplayName("本章全部替换：未命中返回 0 且不改正文")
    void replaceAllReturnsZeroWhenMissing() throws Exception {
        loadEditable(DOC);

        Object count = replaceAll("不存在", "文字", false);
        assertEquals(0, ((Number) count).intValue());
        assertTrue(bodyText().contains("前面正文内容后面"), "未命中不得改正文");
    }
}
