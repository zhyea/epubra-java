package com.epubra.epublib.domain;

/**
 * EPUB 规范版本。
 */
public enum EpubVersion {

    EPUB_2("2.0"),
    EPUB_3("3.0");

    private final String specVersion;

    EpubVersion(String specVersion) {
        this.specVersion = specVersion;
    }

    /** OPF package 元素 version 属性上的字面值，例如 {@code "3.0"}。 */
    public String specVersion() {
        return specVersion;
    }

    public static EpubVersion fromSpecVersion(String value) {
        if (value != null && value.startsWith("3")) {
            return EPUB_3;
        }
        return EPUB_2;
    }
}
