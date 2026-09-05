package com.epubra.app.support;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.BookFactory;
import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Resource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // baseDir = "OEBPS/"：图片路径以 baseDir 开头 → 去掉前缀 → "images/foo.png"
        assertEquals("<img src=\"images/foo.png\" alt=\"foo.png\"/>", tag);

        // 同目录 → 纯文件名
        assertEquals("<img src=\"foo.png\" alt=\"foo.png\"/>",
                ResourceOps.buildInsertImageTag("OEBPS/foo.xhtml", "OEBPS/foo.png", "foo.png"));
    }
}
