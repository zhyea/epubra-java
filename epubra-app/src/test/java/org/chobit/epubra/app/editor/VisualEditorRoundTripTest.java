package org.chobit.epubra.app.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨 reload 周期的往返：同一章反复加载 / 回写后，结构与空白不得漂移。
 *
 * <p>这类缺陷的形态是「单次操作全对，来回几次才坏」——head 空白节点跨周期线性累积（#59）就是典型。本类必须真的换文档重载（loadEditable），不能只做单次断言。
 *
 * <p>脚手架（WebView 启动 / 文档加载 / 选区清理 / DOM 断言助手）见
 * {@link VisualEditorTestSupport}。
 */
class VisualEditorRoundTripTest extends VisualEditorTestSupport {

    @Test
    @Timeout(120)
    @DisplayName("head 内的空行不随「加载→回写」周期累积（#59）")
    void headWhitespaceDoesNotAccumulateAcrossRoundTrips() throws Exception {
        String chapter = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head>\n  <title>第一章</title>\n  <meta charset=\"utf-8\"/>\n</head>"
                + "<body>\n  <p>正文</p>\n</body></html>";

        int baseline = -1;
        for (int round = 1; round <= 4; round++) {
            loadEditable(chapter);
            chapter = serialized();
            int blanks = headNewlines(chapter);
            if (round == 1) {
                baseline = blanks;
            } else {
                assertEquals(baseline, blanks,
                        "第 " + round + " 轮回写后 head 换行数应稳定在 " + baseline
                                + "，实际 " + blanks + "：\n" + chapter);
            }
            assertWellFormedXhtml(chapter);
        }
        assertTrue(chapter.contains("<p>正文</p>"), "正文内容应原样保留：" + chapter);
    }

    @Test
    @Timeout(60)
    @DisplayName("列表/段落多次往返结构始终合法，内容不丢")
    void listRoundTripsKeepStructureLegal() throws Exception {
        caretIntoParagraph();
        for (int round = 0; round < 3; round++) {
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                    "第 " + (round + 1) + " 轮转列表应成功");
            String asList = serialized();
            assertWellFormedXhtml(asList);
            assertFalse(asList.contains("<p><ul") || asList.contains("<p><ol"),
                    "列表内不应出现段落嵌套列表的非法结构：" + bodyOf(asList));

            caretIntoParagraph();
            assertTrue(Boolean.TRUE.equals(runScript("window.epubraFormat('list')")),
                    "第 " + (round + 1) + " 轮退回段落应成功");
            String asParagraph = serialized();
            assertWellFormedXhtml(asParagraph);
            assertTrue(asParagraph.contains("正文"), "往返后文字不能丢：" + bodyOf(asParagraph));
        }
    }
}
