package org.chobit.epubra.lib.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Hrefs#relativePath(String, String)} 的验证。
 *
 * <p>这组用例的由来：正文插图原先用 {@link Hrefs#relativize(String, String)} 算相对路径，
 * 而它只做前缀剥离。章节位于 {@code OEBPS/text/} 这类子目录（外部 EPUB 的常见布局）时，
 * 会写出 {@code OEBPS/images/a.png} 这种包内绝对路径 —— 预览与阅读器都解析不到，
 * 结构校验还会判为断链。新建的书章节恰好在 {@code OEBPS/} 与图片同父目录，把它掩盖了。
 */
class HrefsTest {

    @Test
    void sameDirectoryYieldsBareFileName() {
        assertEquals("foo.png", Hrefs.relativePath("OEBPS/", "OEBPS/foo.png"));
    }

    @Test
    void childDirectoryYieldsRelativePath() {
        assertEquals("images/foo.png", Hrefs.relativePath("OEBPS/", "OEBPS/images/foo.png"));
    }

    @Test
    void siblingDirectoryBacktracksWithDotDot() {
        // 唯一的暴露形态：章节在 OEBPS/text/，图片在 OEBPS/images/
        assertEquals("../images/foo.png",
                Hrefs.relativePath("OEBPS/text/", "OEBPS/images/foo.png"));
    }

    @Test
    void nestedChapterDirectoryBacktracksTwice() {
        assertEquals("../../images/foo.png",
                Hrefs.relativePath("OEBPS/text/part1/", "OEBPS/images/foo.png"));
    }

    @Test
    void rootBaseDirReturnsContainerPathUnchanged() {
        assertEquals("OEBPS/images/foo.png",
                Hrefs.relativePath("", "OEBPS/images/foo.png"));
        assertEquals("OEBPS/images/foo.png",
                Hrefs.relativePath(null, "OEBPS/images/foo.png"));
    }

    @Test
    void baseDirWithoutTrailingSlashBehavesTheSame() {
        assertEquals("../images/foo.png",
                Hrefs.relativePath("OEBPS/text", "OEBPS/images/foo.png"));
    }

    @Test
    void leadingSlashIsTreatedAsContainerRoot() {
        assertEquals("images/foo.png",
                Hrefs.relativePath("OEBPS/", "/OEBPS/images/foo.png"));
    }

    @Test
    void fileNameNeverConsumedByCommonPrefix() {
        // 同目录：末段不参与比对，不能退化成 "../OEBPS/x.png"
        assertEquals("x.png", Hrefs.relativePath("OEBPS/", "OEBPS/x.png"));
        // 同名文件（目录视角下 base 是 OEBPS/x.png/）→ 需要回溯一层
        assertEquals("../x.png", Hrefs.relativePath("OEBPS/x.png", "OEBPS/x.png"));
    }

    @Test
    void blankPathYieldsEmpty() {
        assertEquals("", Hrefs.relativePath("OEBPS/", ""));
        assertEquals("", Hrefs.relativePath("OEBPS/", null));
    }

    @Test
    void roundTripsThroughResolve() {
        // relativePath 的结果必须能被 resolve 还原回原路径（真正的自洽性判据）
        String[][] cases = {
                {"OEBPS/", "OEBPS/images/a.png"},
                {"OEBPS/text/", "OEBPS/images/a.png"},
                {"OEBPS/text/part1/", "OEBPS/images/a.png"},
                {"", "OEBPS/images/a.png"},
                {"OEBPS/text/", "OEBPS/styles/main.css"},
        };
        for (String[] c : cases) {
            String relative = Hrefs.relativePath(c[0], c[1]);
            assertEquals(c[1], Hrefs.resolve(c[0], relative),
                    "round-trip failed: base=" + c[0] + " path=" + c[1]);
        }
    }
}
