package org.chobit.epubra.app.ui.support.resource;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源面板的纯逻辑：行构建、被正文中其它资源引用判定、插入正文图片模板生成。
 *
 * <p>UI 操作（选行、文件选择器、确认对话框等）在 {@code ResourceController} 里组装，
 * 这里只做不依赖 JavaFX 的判定与渲染。这样可以在单元测试里直接覆盖筛选/引用判定。
 */
public final class ResourceOps {

    private ResourceOps() {
    }

    /**
     * 过滤出可以展示给用户看的资源：剔除 nav / NCX —— 它们由写出流程自动维护，
     * 让用户编辑反而会破坏一致性。
     */
    public static List<Resource> userVisible(Book book) {
        Resource nav = book.navResource();
        List<Resource> rows = new ArrayList<>();
        for (Resource resource : book.resources().all()) {
            if (resource == nav || resource.isNavDocument() || MediaTypes.NCX.equals(resource.mediaType())) {
                continue;
            }
            rows.add(resource);
        }
        return rows;
    }

    /**
     * 通过文件名粗略判断资源是否被任意章节的 XHTML 提到。
     *
     * <p>这是为了删除前的提示，不要求精确：full-reference 走
     * {@link Book#unreferencedResources()}。
     */
    public static boolean isReferencedByChapters(Book book, Resource resource) {
        String fileName = resource.fileName();
        if (fileName.isEmpty()) {
            return false;
        }
        for (Resource chapter : book.spineResources()) {
            if (chapter != resource && chapter.asString().contains(fileName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成本地引用图片的 {@code <img>} 标签：相对路径相对于章节所在目录。
     *
     * @param chapterHref 章节 href，用于计算相对路径
     * @param imageHref    图片 href
     * @param alt          作为 alt 文本
     * @return 形如 {@code <img src="../images/foo.png" alt="foo.png"/>}
     */
    public static String buildInsertImageTag(String chapterHref, String imageHref, String alt) {
        String chapterDir = Hrefs.parentDirectory(chapterHref);
        String relative = Hrefs.relativize(chapterDir, imageHref);
        return String.format("<img src=\"%s\" alt=\"%s\"/>", relative, alt);
    }
}
