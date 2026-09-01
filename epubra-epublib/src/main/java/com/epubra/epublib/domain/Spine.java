package com.epubra.epublib.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * OPF 的 spine：定义正文的线性阅读顺序。
 */
public class Spine {

    private final List<SpineReference> references = new ArrayList<>();
    /** spine 的 toc 属性，指向 NCX 资源 id（EPUB 2 目录）。 */
    private String tocResourceId;

    public void add(SpineReference reference) {
        references.add(reference);
    }

    public void addResourceId(String resourceId) {
        references.add(new SpineReference(resourceId, true));
    }

    public List<SpineReference> references() {
        return references;
    }

    public int size() {
        return references.size();
    }

    public SpineReference get(int index) {
        return references.get(index);
    }

    public void clear() {
        references.clear();
    }

    public void removeResourceId(String resourceId) {
        references.removeIf(ref -> ref.resourceId().equals(resourceId));
    }

    public String tocResourceId() {
        return tocResourceId;
    }

    public void setTocResourceId(String tocResourceId) {
        this.tocResourceId = tocResourceId;
    }
}
