package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纸面配色不得压掉作者在工具条上选的样式。
 *
 * <h2>为什么需要这条守卫</h2>
 * <p>{@code editor-paper.css} 与 {@link Theme#previewStyleCss()} 用 {@code !important} 把画布
 * 固定成浅色纸面（背景 / 边框 / 链接色）——这是设计口径，必须保住。但<b>样式表里的
 * {@code !important} 声明优先于内联 {@code style}</b>，而工具条的「文字颜色 / 清档」正是
 * 往元素上写<b>内联</b> {@code color}。少了这道闸，就出现用户实际看到的现象：
 * 源码区明明写着 {@code color: #c00000}，画布上的字还是纸面黑。
 *
 * <p>修法是把「文字颜色」那几条收窄成 {@code :not([style*="color"])}：只压文档自带的
 * 样式表配色，不压作者显式指定的那一条。
 *
 * <h2>断言为什么落在 getComputedStyle</h2>
 * <p>「内联 style 写进 DOM 了」与「画面真的变了」是两件事。既有用例（
 * {@link VisualEditorStyleFormatTest}）只断言了序列化文本，于是这个 bug 一路溜过门禁——
 * 所以这里问浏览器：<b>它打算用什么颜色 / 字体 / 字号来画这段字</b>。
 *
 * <p>脚手架见 {@link VisualEditorTestSupport}。
 */
class VisualEditorPaperCssTest extends VisualEditorTestSupport {

    /** 纸面文字色（{@code editor-paper.css} 的 {@code #2b2b28}），浏览器计算值形态。 */
    private static final String PAPER_RGB = "rgb(43, 43, 40)";

    /** 作者选的红色 {@code #c00000} 的计算值形态。 */
    private static final String RED_RGB = "rgb(192, 0, 0)";

    @Test
    @Timeout(60)
    @DisplayName("工具条设的文字颜色必须真的渲染出来（内联 color 赢过纸面配色）")
    void authorColorActuallyRenders() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('color', '#c00000')")),
                "选区上设颜色应成功");

        assertEquals(RED_RGB, computed("span", "color"),
                "颜色写进了内联 style，画布却还是纸面色 —— 纸面配色用 !important 压掉了作者的声明");
        assertTrue(String.valueOf(runScript(
                "(function () { var s = document.body.querySelector('span');"
                        + " return s ? String(s.getAttribute('style')) : ''; })()")).contains("color"),
                "前置确认：内联 color 确实在 DOM 上");
    }

    @Test
    @Timeout(60)
    @DisplayName("清档（「默认」）后文字颜色回到纸面色，不是留在上一次的颜色上")
    void clearingColorFallsBackToPaper() throws Exception {
        selectParagraphContents();
        runScript("window.epubraFormat('color', '#c00000')");
        assertEquals(RED_RGB, computed("span", "color"), "前置确认：颜色先真的生效了");

        selectParagraphContents();
        runScript("window.epubraFormat('color', '')");

        assertEquals(PAPER_RGB, computed("p", "color"),
                "清掉 color 声明后，段落应回到纸面配色（没有声明就该由纸面兜底）");
    }

    @Test
    @Timeout(60)
    @DisplayName("没有内联 color 的元素仍由纸面配色统一上色——画布恒浅色的口径不能丢")
    void paperStillColorsUntouchedElements() throws Exception {
        // h1 上没有内联样式：它必须继续吃纸面配色，否则「编辑器画布永远是浅色纸面」就破了
        assertEquals(PAPER_RGB, computed("h1", "color"),
                "没有内联 color 的元素必须仍由纸面配色统一上色");
    }

    @Test
    @Timeout(60)
    @DisplayName("字体以计算样式为准：画布上真的换了字面（工具条下发的是带引号族名）")
    void authorFontActuallyRenders() throws Exception {
        selectParagraphContents();
        // 工具条实际下发的是带双引号的族名形态（见 EditorStyleControls#cssFontValue）
        assertTrue(Boolean.TRUE.equals(
                        withMember("__testFont", "\"SimSun\"", "window.epubraFormat('font', window.__testFont)")),
                "字体命令应成功");

        // 落点是包住选区的那一层 span，所以问它（问段落只会问到继承自 body 的字体栈）
        String family = computed("span", "fontFamily");
        assertTrue(family.contains("SimSun"),
                "画布上的字体没有跟着内联 font-family 走，实际：" + family);

        // 序列化这一跳也不能丢：工具条下发的是**带双引号**的族名形态，写进属性后是
        // font-family: &quot;SimSun&quot; —— 合并同层 span / 剥裸壳这两步都在 serialize 的 clone 上跑，
        // 谁把带引号的声明弄丢，现象同样是「选了字体没反应」。
        String body = bodyOf(serialized());
        assertTrue(body.contains("font-family"), "序列化后字体声明不能丢：" + body);
        assertTrue(body.contains("SimSun"), "序列化后族名不能丢：" + body);
    }

    @Test
    @Timeout(60)
    @DisplayName("字号以计算样式为准：画布上真的变大了")
    void authorSizeActuallyRenders() throws Exception {
        selectParagraphContents();
        assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('size', '28px')")),
                "字号命令应成功");

        assertEquals("28px", computed("span", "fontSize"),
                "画布上的字号没有跟着内联 font-size 走，实际：" + computed("span", "fontSize"));
    }

    /**
     * 问浏览器某个元素「打算用什么值来画这个属性」。
     *
     * <p>查不到元素或引擎没有 {@code getComputedStyle} 时返回空串——让断言带着实际值失败，
     * 而不是在脚本里抛错（抛错的堆栈看不出「算出来是什么」）。
     */
    private static String computed(String selector, String property) throws Exception {
        return String.valueOf(runScript(
                "(function () {"
                        + " var el = document.body.querySelector('" + selector + "');"
                        + " if (!el || !window.getComputedStyle) { return ''; }"
                        + " return String(getComputedStyle(el)['" + property + "']);"
                        + " })()"));
    }
}
