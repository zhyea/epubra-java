package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快捷键「按键标识」契约：JavaFX WebView 的真实按键映射下，快捷键必须仍然生效。
 *
 * <h2>为什么需要这一类测试</h2>
 * <p>JavaFX WebView 把真实按键送给 WebKit 时，{@code KeyboardEvent} 的 <b>{@code key} 与
 * {@code code} 恒为空串</b>，只有 {@code keyCode} / {@code which} 有值（实测 Tab 为 9、
 * Ctrl+B 为 66）；修饰键标志 {@code ctrlKey} / {@code shiftKey} / {@code altKey} 则可靠。
 *
 * <p>此前所有编辑器测试都用 {@code new KeyboardEvent('keydown', {key: 'Tab'})} 手工塞上
 * {@code key}——那<b>照不到真实映射</b>：{@code (e.key || '') === 'Tab'} 在真机里恒为假，
 * Tab 缩进与 Ctrl+B/I/U 全部静默失效，且 Tab 不 {@code preventDefault} 会让焦点被
 * JavaFX 的焦点遍历带出编辑器（#68，用户实测「按 Tab 操作超出了编辑器」）。
 *
 * <p>本类用 {@link #pressLikeJavaFx} 复刻真实映射（无窗口焦点依赖，可稳定进日常门禁），
 * 并断言 {@code defaultPrevented}——它正是「默认行为有没有被拦住」的直接证据。
 * 真·端到端（含焦点是否外逃）由 {@link VisualEditorTabFocusEscapeTest} 用 Robot 守卫。
 */
class VisualEditorKeyMappingTest extends VisualEditorTestSupport {

    @Test
    @Timeout(60)
    @DisplayName("JavaFX 真实映射下按 Tab：列表缩进生效且拦住默认行为")
    void tabMappedLikeJavaFxIndentsAndBlocksDefault() throws Exception {
        flatList("ul", "甲", "乙");
        caretInLi("乙");

        Object defaultPrevented = pressLikeJavaFx(9, false, false);

        assertTrue(Boolean.TRUE.equals(runScript(
                "!!document.querySelector('ul > li > ul > li')")),
                "keyCode=9 的 Tab 必须能缩进列表（key 为空串）：" + serialized());
        assertEquals(Boolean.TRUE, defaultPrevented,
                "必须 preventDefault —— 否则默认行为会把焦点带出编辑器");
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("旧式显式 key:'Tab' 的事件仍然生效（兼容既有测试与其它调用方）")
    void explicitKeyNameStillWorks() throws Exception {
        flatList("ul", "甲", "乙");
        caretInLi("乙");

        runScript("document.dispatchEvent(new KeyboardEvent('keydown',"
                + " {key: 'Tab', bubbles: true, cancelable: true}))");

        assertTrue(Boolean.TRUE.equals(runScript(
                "!!document.querySelector('ul > li > ul > li')")),
                "显式 key 名的事件也要能缩进：" + serialized());
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("JavaFX 真实映射下按 Ctrl+B：加粗生效（原实现因 e.key 为空串而失效）")
    void ctrlBMappedLikeJavaFxBolds() throws Exception {
        // 加粗需要真实选区（折叠光标下 format 不产出包裹层）
        selectParagraphContents();

        Object defaultPrevented = pressLikeJavaFx(66, true, false);

        String body = bodyOf(serialized());
        assertTrue(body.contains("<strong") || body.contains("<b>"),
                "Ctrl+B（keyCode=66 + ctrlKey）必须能加粗：" + body);
        assertEquals(Boolean.TRUE, defaultPrevented,
                "识别出的快捷键必须拦住默认行为");
        assertWellFormedXhtml(serialized());
    }

    @Test
    @Timeout(60)
    @DisplayName("不在列表里按 Tab 照样吞掉：不缩进、不插制表符、也不让焦点逃走")
    void tabOutsideListIsSwallowedToo() throws Exception {
        caretIntoParagraph();
        String before = bodyOf(serialized());

        Object defaultPrevented = pressLikeJavaFx(9, false, false);

        assertEquals(Boolean.TRUE, defaultPrevented,
                "编辑器内一律吞掉 Tab —— 不拦的话默认行为会把焦点带出编辑器（#68）");
        String after = bodyOf(serialized());
        assertEquals(before, after, "有意不作为：正文不该被改动");
        assertFalse(after.contains("\t"), "绝不能把制表符当文本插进正文：" + after);
    }

    // ------------------------------------------------------------------ 助手

    /**
     * 复刻 JavaFX WebView 的真实按键映射并派发 keydown，返回 {@code defaultPrevented}。
     *
     * <p>{@code key} / {@code code} 显式置为空串——这正是真机形态；{@code keyCode} / {@code which}
     * 与修饰键按真机值给出。{@code cancelable: true} 是 {@code preventDefault()} 生效的前提。
     */
    private Object pressLikeJavaFx(int keyCode, boolean ctrl, boolean shift) throws Exception {
        return runScript("(function () {"
                + " var e = new KeyboardEvent('keydown', {bubbles: true, cancelable: true});"
                + " Object.defineProperty(e, 'key', {value: ''});"
                + " Object.defineProperty(e, 'code', {value: ''});"
                + " Object.defineProperty(e, 'keyCode', {value: " + keyCode + "});"
                + " Object.defineProperty(e, 'which', {value: " + keyCode + "});"
                + " Object.defineProperty(e, 'ctrlKey', {value: " + ctrl + "});"
                + " Object.defineProperty(e, 'shiftKey', {value: " + shift + "});"
                + " document.dispatchEvent(e);"
                + " return e.defaultPrevented; })()");
    }

    /** 把 body 换成一条干净的扁平列表（首元素为标签名，其余为各列表项文本）。 */
    private void flatList(String tag, String... items) throws Exception {
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

    /** 把光标收进文本恰好等于 text 的列表项。 */
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
}
