package org.chobit.epubra.app.components;

import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;

/**
 * 资源表的行视图模型（供 JavaFX PropertyValueFactory 按 Bean 属性取值）。
 *
 * <p>封面相关属性（{@link #isCover()} / {@link #getCoverBadge()}）由资源控制器在加载行时
 * 注入当前 {@link Book#coverResourceId()}；纯靠行构造
 * 没法独立判断「这张图是不是当前书的封面」（取决于外部书状态），所以这里加
 * {@code coverBadge} 字符串字段做缓存——{@code PropertyValueFactory} 实际调的是
 * {@code getXxx()}，不依赖字段名，方法之间互相一致即可。
 */
public class ResourceRow {

    private final Resource resource;
    private String coverBadge = "";

    public ResourceRow(Resource resource) {
        this.resource = resource;
    }

    public String getName() {
        return resource.fileName();
    }

    public String getType() {
        return typeLabel(resource.mediaType());
    }

    public String getSize() {
        int bytes = resource.data().length;
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** 是否为可用于封面与正文插入的图片。 */
    public boolean isImage() {
        return resource.mediaType() != null && resource.mediaType().startsWith("image/");
    }

    /** 这份资源是否被当前 Book 指定为封面（被 markCoverBadgeFor 标过）。 */
    public boolean isCover() {
        return coverBadge != null && !coverBadge.isEmpty();
    }

    /**
     * 给 PropertyValueFactory 取值用：行名前面加个 ● 徽章以便用户一眼看出哪张是封面。
     * 没被标过就返回原文件名（不加前缀），保持空状态时一致。
     */
    public String getCoverBadge() {
        return coverBadge == null ? "" : coverBadge;
    }

    /** 资源控制器在每次 refresh 时按 coverResourceId() 调用本方法刷新徽章。 */
    public void markCoverBadgeFor(String coverResourceId) {
        if (coverResourceId != null && coverResourceId.equals(resource.id())) {
            this.coverBadge = "● " + resource.fileName();
        } else {
            this.coverBadge = "";
        }
    }

    public Resource getResource() {
        return resource;
    }

    private static String typeLabel(String mediaType) {
        if (mediaType == null) {
            return "其他";
        }
        if (mediaType.startsWith("image/")) {
            return "图片";
        }
        if (MediaTypes.CSS.equals(mediaType)) {
            return "样式";
        }
        if (mediaType.startsWith("font/") || mediaType.equals("application/font-woff")) {
            return "字体";
        }
        if (MediaTypes.XHTML.equals(mediaType)) {
            return "正文";
        }
        return "其他";
    }
}
