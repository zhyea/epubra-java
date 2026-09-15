package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link XhtmlProlog#repairProlog} 的契约：只修坏声明、其余一字不动。
 *
 * <p>背景（2026-09-15 实测事故）：源码区被 fallback 插进 {@code <u></u>}，落在 XML
 * 声明里得到 {@code encoding="UTF-8<u></u>"}，任何解析器都是 Fatal Error。消毒器是
 * 字符串层的最后一道防线。
 */
class XhtmlPrologTest {

    private static final String CANONICAL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    @Test
    @DisplayName("事故现场：encoding 里混进 <u></u>，修复为规范声明且正文原样保留")
    void repairsUnderlineInjectedIntoEncoding() {
        String dirty = "<?xml version=\"1.0\" encoding=\"UTF-8<u></u>\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第三章</title></head>"
                + "<body><p>正文</p></body></html>";
        String fixed = XhtmlProlog.repairProlog(dirty);
        assertEquals(CANONICAL + "\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第三章</title></head>"
                + "<body><p>正文</p></body></html>", fixed);
    }

    @Test
    @DisplayName("规范声明原样返回（同一引用，不做多余重写）")
    void leavesCanonicalDeclarationUntouched() {
        String clean = CANONICAL + "\n<html><body><p>ok</p></body></html>";
        assertSame(clean, XhtmlProlog.repairProlog(clean));
    }

    @Test
    @DisplayName("无声明的片段原样返回")
    void leavesFragmentWithoutDeclarationUntouched() {
        String fragment = "<p>没有声明的片段</p>";
        assertSame(fragment, XhtmlProlog.repairProlog(fragment));
    }

    @Test
    @DisplayName("残缺声明（未闭合）整行丢弃并补规范声明")
    void repairsTruncatedDeclaration() {
        String dirty = "<?xml version=\"1.0\" encoding=\"UTF-8\n<p>正文</p>";
        assertEquals(CANONICAL + "\n<p>正文</p>", XhtmlProlog.repairProlog(dirty));
        assertEquals(CANONICAL + "\n", XhtmlProlog.repairProlog("<?xml version=\"1.0\""));
    }

    @Test
    @DisplayName("null 与空串安全通过")
    void toleratesNullAndEmpty() {
        assertNull(XhtmlProlog.repairProlog(null));
        assertEquals("", XhtmlProlog.repairProlog(""));
    }
}
