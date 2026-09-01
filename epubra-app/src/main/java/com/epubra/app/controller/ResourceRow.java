package com.epubra.app.controller;

import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Resource;

/**
 * 资源表的行视图模型（供 JavaFX PropertyValueFactory 按 Bean 属性取值）。
 */
public class ResourceRow {

    private final Resource resource;

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
