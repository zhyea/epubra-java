package com.epubra.epublib.validation;

/**
 * 校验规则的分组，同时决定报告排序的段位与界面「分类」列的取值。
 *
 * <p>枚举声明顺序即排序顺序：容器 → OPF → 阅读顺序 → 目录 → 资源引用 → 元数据。
 */
public enum IssueGroup {

    CONTAINER("容器"),
    OPF("OPF"),
    SPINE("阅读顺序"),
    NAVIGATION("目录"),
    REFERENCE("资源引用"),
    METADATA("元数据");

    private final String label;

    IssueGroup(String label) {
        this.label = label;
    }

    /** 中文标签，用于界面展示。 */
    public String label() {
        return label;
    }
}
