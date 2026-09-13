package org.chobit.epubra.app.resource;

import org.chobit.epubra.app.resource.ResourceOps;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.ResourceReferences;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void isReferencedByChaptersMatchesXmlEscapedFileName() {
        // P2：buildInsertImageTag 会把文件名 XML 转义后写进 src/alt，
        // 因此磁盘上的 "Tom & Jerry.png" 在正文里是 "Tom &amp; Jerry.png"。
        // 只比对原文件名会漏判 → 删除这张被引用的图时不再提示「正文存在引用」。
        Book book = BookFactory.createEmpty("转义");
        Resource image = new Resource("img-1", "OEBPS/images/Tom & Jerry.png", MediaTypes.PNG);
        image.setData(new byte[]{1, 2, 3});
        book.resources().add(image);

        Resource chapter = book.spineResources().get(0);
        chapter.setString("<html><body><p><img src=\"images/Tom &amp; Jerry.png\""
                + " alt=\"Tom &amp; Jerry.png\"/></p></body></html>");
        assertTrue(ResourceOps.isReferencedByChapters(book, image),
                "转义后的文件名必须能匹配上，否则删除提示会漏");

        chapter.setString("<html><body><p>与本图无关。</p></body></html>");
        assertFalse(ResourceOps.isReferencedByChapters(book, image));
    }

    @Test
    void isReferencedByChaptersToleratesNullArguments() {
        Book book = BookFactory.createEmpty("空引用");
        assertFalse(ResourceOps.isReferencedByChapters(null, null));
        assertFalse(ResourceOps.isReferencedByChapters(book, null));
    }

    @Test
    void joinInsertFragmentsSeparatesMultipleImages() throws Exception {
        // P2：裸连的 <img/><img/> 是两个行内元素，会挤在同一行。
        String joined = ResourceOps.joinInsertFragments(List.of(
                "<img src=\"a.png\" alt=\"a.png\"/>",
                "<img src=\"b.png\" alt=\"b.png\"/>",
                "<img src=\"c.png\" alt=\"c.png\"/>"));
        assertEquals("<img src=\"a.png\" alt=\"a.png\"/>"
                        + "<br/>"
                        + "<img src=\"b.png\" alt=\"b.png\"/>"
                        + "<br/>"
                        + "<img src=\"c.png\" alt=\"c.png\"/>",
                joined);
        // 结果仍必须是合法 XHTML——<br/> 在 <p> 内外都允许
        assertParsable(joined);
    }

    @Test
    void joinInsertFragmentsLeavesSingleFragmentUntouched() {
        assertEquals("<img src=\"a.png\" alt=\"a.png\"/>",
                ResourceOps.joinInsertFragments(List.of("<img src=\"a.png\" alt=\"a.png\"/>")));
        assertEquals("", ResourceOps.joinInsertFragments(List.of()));
        assertEquals("", ResourceOps.joinInsertFragments(null));
    }

    @Test
    void findByContentReusesBytesRegardlessOfName() {
        Book book = BookFactory.createEmpty("去重");
        Resource first = book.addResource("dup.png", new byte[]{1, 2, 3});

        assertSame(first, ResourceOps.findByContent(book, new byte[]{1, 2, 3}),
                "同内容必须复用——哪怕书里那份是旧命名（原名挂载的历史资源也要认出来）");
        assertNull(ResourceOps.findByContent(book, new byte[]{9}),
                "不同内容是两张不同的图，不能合并");
        assertNull(ResourceOps.findByContent(book, new byte[]{}), "空字节不算有效内容");
        assertNull(ResourceOps.findByContent(book, null));
        assertNull(ResourceOps.findByContent(null, new byte[]{1}));
    }

    @Test
    void contentAddressedFileNameUsesMd5AndKeepsExtension() {
        // md5("hello") 的公认向量，钉死摘要算法与大小写
        assertEquals("5d41402abc4b2a76b9719d911017c592",
                ResourceOps.md5Hex("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // 空输入的 md5 向量
        assertEquals("d41d8cd98f00b204e9800998ecf8427e",
                ResourceOps.md5Hex(new byte[0]));

        assertEquals("5d41402abc4b2a76b9719d911017c592.png",
                ResourceOps.contentAddressedFileName("风景照.png", "hello".getBytes()));
        assertEquals("5d41402abc4b2a76b9719d911017c592.jpeg",
                ResourceOps.contentAddressedFileName("Tom & Jerry.jpeg", "hello".getBytes()));
        // 无扩展名 → 不造后缀（媒体类型会落到 misc，属上游口径）
        assertEquals("5d41402abc4b2a76b9719d911017c592",
                ResourceOps.contentAddressedFileName("noext", "hello".getBytes()));
        // 原名缺失也只影响扩展名，摘要仍要产出
        assertEquals("5d41402abc4b2a76b9719d911017c592",
                ResourceOps.contentAddressedFileName(null, "hello".getBytes()));
        // 同内容不同名 → 同一目标文件名（排重的另一半：命名即寻址）
        assertEquals(
                ResourceOps.contentAddressedFileName("a.png", new byte[]{7}),
                ResourceOps.contentAddressedFileName("b.png", new byte[]{7}));
    }

    @Test
    void extractImageSrcsPicksImgTagsAndUnescapesEntities() {
        String fragment = "<img src=\"../images/a&amp;b.png\" alt=\"x\"/><br/>"
                + "<IMG SRC='../images/c.png'/>" // 单引号 + 大写不是本应用产物，允许不识别
                + "<img src=\"images/d.png\"/><p>正文</p>";
        assertEquals(java.util.List.of("../images/a&b.png", "images/d.png"),
                ResourceOps.extractImageSrcs(fragment));
        assertEquals(java.util.List.of(), ResourceOps.extractImageSrcs(""));
        assertEquals(java.util.List.of(), ResourceOps.extractImageSrcs(null));
        assertEquals(java.util.List.of(), ResourceOps.extractImageSrcs("<p>没有图片</p>"));
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
        assertEquals("../../images/foo.png", src);
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

    /** 片段放进带 XHTML 命名空间的 div 里解析——模拟它被插进章节后的真实上下文。 */
    private static void assertParsable(String fragment) throws Exception {
        String wrapped = "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + fragment + "</div>";
        assertNotNull(DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(wrapped))));
    }
}
