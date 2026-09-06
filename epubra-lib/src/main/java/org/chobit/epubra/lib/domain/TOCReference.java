package org.chobit.epubra.lib.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录中的一个节点，可递归包含子节点。
 *
 * <p>href 允许携带片断标识符，例如 {@code "chapter1.xhtml#section2"}。
 */
public class TOCReference {

    private String title;
    private String href;
    private final List<TOCReference> children = new ArrayList<>();

    public TOCReference() {
        this("", "");
    }

    public TOCReference(String title, String href) {
        this.title = title == null ? "" : title;
        this.href = href == null ? "" : href;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String href() {
        return href;
    }

    public void setHref(String href) {
        this.href = href == null ? "" : href;
    }

    public List<TOCReference> children() {
        return children;
    }

    public void addChild(TOCReference child) {
        children.add(child);
    }

    /** 去掉片断标识符后的资源 href。 */
    public String resourceHref() {
        int hash = href.indexOf('#');
        return hash < 0 ? href : href.substring(0, hash);
    }

    /** 片断标识符，无锚点时为空串。 */
    public String fragmentId() {
        int hash = href.indexOf('#');
        return hash < 0 ? "" : href.substring(hash + 1);
    }

    @Override
    public String toString() {
        return "TOCReference{title='" + title + "', href='" + href + "', children=" + children.size() + "}";
    }
}
