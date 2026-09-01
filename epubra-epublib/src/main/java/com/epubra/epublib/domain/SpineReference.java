package com.epubra.epublib.domain;

/**
 * spine 中的一个 itemref：指向 manifest 资源 id，linear 标识是否属于主阅读流。
 */
public record SpineReference(String resourceId, boolean linear) {

    public SpineReference(String resourceId) {
        this(resourceId, true);
    }
}
