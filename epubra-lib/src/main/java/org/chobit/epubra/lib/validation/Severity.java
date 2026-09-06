package org.chobit.epubra.lib.validation;

/**
 * 校验问题的严重级别。
 */
public enum Severity {

    ERROR("错误"),
    WARNING("警告");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /** 中文标签，用于界面展示。 */
    public String label() {
        return label;
    }
}
