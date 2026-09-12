package org.chobit.epubra.app.resource;

import org.chobit.epubra.app.resource.ResourceOps;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceOps} 头切 JavaFX 的纯逻辑覆盖：
 * 资源过滤、章节引用判定、图片标签的相对路径生成。
 */
class ResourceOpsTest {

    @Test
    void userVisibleFiltersNavAndNcx() {
        Book book = BookFactory.createEmpty("过滤");
        Resource visible = book.spineResources().get(0);
        long before = ResourceOps.userVisible(book).size();

        Resource font = new Resource("font-1", "OEBPS/fonts/Source.ttf", MediaTypes.TTF);
        font.setData(new byte[]{1, 2, 3});
        book.resources().add(font);

        var rows = ResourceOps.userVisible(book);
        // nav / ncx 由 EpubWriter 自动维护，它们的资源对象 id="nav"/"ncx"，应当被过滤掉
        assertTrue(rows.stream().noneMatch(r -> r.isNavDocument()));
        assertTrue(rows.stream().noneMatch(r -> MediaTypes.NCX.equals(r.mediaType())));
        // 加进用户资源后数量应当增长
        assertEquals(before + 1, rows.size());
        assertTrue(rows.contains(visible));
        assertTrue(rows.contains(font));
    }

    @Test
    void isReferencedByChaptersDetectsFileNameMention() {
        Book book = BookFactory.createEmpty("引用");
        Resource image = new Resource("img-1", "OEBPS/images/foo.png", MediaTypes.PNG);
        image.setData(new byte[]{1, 2, 3});
        book.resources().add(image);

        Resource chapter = book.spineResources().get(0);
        chapter.setString("<html><body><p>插图见 foo.png。</p></body></html>");
        assertTrue(ResourceOps.isReferencedByChapters(book, image));

        chapter.setString("<html><body><p>无图。</p></body></html>");
        assertFalse(ResourceOps.isReferencedByChapters(book, image));
    }

    @Test
    void isReferencedByChaptersFalseForUnnamedResource() {
        Book book = BookFactory.createEmpty("空");
        Resource chapter = book.spineResources().get(0);
        Resource anon = new Resource("img-x", "", MediaTypes.PNG);
        assertFalse(ResourceOps.isReferencedByChapters(book, anon));
    }

    @Test
    void buildInsertImageTagComputesRelativePath() {
        // chapter 与图片不同目录 → 相对路径要回溯
        String tag = ResourceOps.buildInsertImageTag(
                "OEBPS/chapter-1.xhtml",
                "OEBPS/images/foo.png",
                "foo.png");
        // baseDir = "OEBPS/"：图片在同一父目录的子目录下 → "images/foo.png"
        assertEquals("<img src=\"images/foo.png\" alt=\"foo.png\"/>", tag);

        // 同目录 → 纯文件名
        assertEquals("<img src=\"foo.png\" alt=\"foo.png\"/>",
                ResourceOps.buildInsertImageTag("OEBPS/foo.xhtml", "OEBPS/foo.png", "foo.png"));
    }

    @Test
    void buildInsertImageTagBacktracksWhenChapterSitsInSubdirectory() {
        // 外部 EPUB 的常见布局：章节在 OEBPS/text/，图片在 OEBPS/images/。
        // 修复前这里会产出 "OEBPS/images/foo.png"（包内绝对路径），预览与阅读器都解析不到。
        assertEquals("<img src=\"../images/foo.png\" alt=\"foo.png\"/>",
                ResourceOps.buildInsertImageTag(
                        "OEBPS/text/chapter-1.xhtml", "OEBPS/images/foo.png", "foo.png"));
    }

    @Test
    void generatedRelativePathResolvesBackToImageHref() {
        // 真正的不变量：生成的 src 经校验侧的引用解析必须还原成图片的容器内路径。
        // 算错一步就会被 ReferenceRules 判为断链，或让图片在预览里静默消失。
        String chapterHref = "OEBPS/text/part1/chapter-1.xhtml";
        String imageHref = "OEBPS/images/foo.png";
        String tag = ResourceOps.buildInsertImageTag(chapterHref, imageHref, "foo.png");
        String src = tag.substring(tag.indexOf("src=\"") + 5, tag.indexOf("\" alt="));
        assertEquals("../images/foo.png", src);
        assertEquals(imageHref,
                ResourceReferences.resolveTarget(Hrefs.parentDirectory(chapterHref), src));
    }

    @Test
    void buildInsertImageTagEscapesXmlSpecialChars() {
        // 文件名是用户数据：从本机选图时完全可能叫 "Tom & Jerry.png"。
        // 不转义会拼出非法 XHTML，写进正文后整章解析失败。
        String tag = ResourceOps.buildInsertImageTag(
                "OEBPS/chapter-1.xhtml",
                "OEBPS/images/Tom & Jerry \"1\".png",
                "Tom & Jerry \"1\".png");
        assertTrue(tag.contains("src=\"images/Tom &amp; Jerry &quot;1&quot;.png\""), tag);
        assertTrue(tag.contains("alt=\"Tom &amp; Jerry &quot;1&quot;.png\""), tag);
        assertFalse(tag.contains("Tom & Jerry"), "裸 & 必须被转义：" + tag);
    }

    @Test
    void buildInsertImageTagOutputStaysParsableXml() throws Exception {
        String tag = ResourceOps.buildInsertImageTag(
                "OEBPS/chapter-1.xhtml", "OEBPS/images/a<b>c.png", "a<b>c.png");
        assertTrue(tag.contains("&lt;b&gt;"), tag);
        assertFalse(tag.contains("<b>"), "裸 < 必须被转义：" + tag);

        // 能被 XML 解析器吃掉，才说明写回正文后不会破坏文档结构
        String wrapped = "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + tag + "</div>";
        assertNotNull(DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(wrapped))));
    }
}
