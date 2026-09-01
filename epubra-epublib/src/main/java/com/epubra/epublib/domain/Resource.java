package com.epubra.epublib.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * EPUB 容器中的一份资源（章节 XHTML、图片、样式、字体等）。
 *
 * <p>{@code href} 为相对于 OPF 文件所在目录的路径，也是资源在容器内的逻辑标识。
 */
public class Resource {

    private String id;
    private String href;
    private String mediaType;
    /** OPF manifest item 的 properties，例如 {@code nav}、{@code cover-image}。 */
    private String properties;
    private byte[] data;

    public Resource(String id, String href, String mediaType, byte[] data) {
        this.id = id;
        this.href = href;
        this.mediaType = mediaType != null ? mediaType : MediaTypes.guessByExtension(href);
        this.data = data != null ? data : new byte[0];
    }

    public Resource(String id, String href, String mediaType) {
        this(id, href, mediaType, new byte[0]);
    }

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String href() {
        return href;
    }

    public void setHref(String href) {
        this.href = href;
    }

    public String mediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String properties() {
        return properties;
    }

    public void setProperties(String properties) {
        this.properties = properties;
    }

    public byte[] data() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }

    /** 是否属于线性阅读的主要文本内容（用于目录与正文编辑）。 */
    public boolean isText() {
        return MediaTypes.XHTML.equals(mediaType);
    }

    public boolean isNavDocument() {
        return properties != null && properties.contains("nav");
    }

    /** 以 UTF-8 读取文本内容；二进制资源同样按 UTF-8 解码，调用方需自行判断。 */
    public String asString() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public void setString(String text) {
        this.data = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    /** href 中的文件名部分，例如 {@code "OEBPS/ch1.xhtml"} 返回 {@code "ch1.xhtml"}。 */
    public String fileName() {
        if (href == null) {
            return "";
        }
        int slash = href.lastIndexOf('/');
        return slash < 0 ? href : href.substring(slash + 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Resource other)) {
            return false;
        }
        return Objects.equals(href, other.href) && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(href, id);
    }

    @Override
    public String toString() {
        return "Resource{href='" + href + "', id='" + id + "', mediaType='" + mediaType + "'}";
    }
}
