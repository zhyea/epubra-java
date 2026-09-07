package org.chobit.epubra.app.ui.support.editor;

import org.chobit.epubra.lib.domain.Metadata;

/**
 * 元数据表单上的 5 个字符串字段。{@link MetadataOps} 用它在 UI 与 {@link
 * Metadata} 之间往返：{@code snapshot} 从内核读出，
 * {@code matches} 比对，{@code apply} 写回。
 *
 * <p>不可变 record 避免 UI 端持有"半新半旧"的中间态——只要传进
 * {@link MetadataOps} 的就是完整草稿。compact constructor 自动把 null 归一为空串，
 * 调用方不必每次 trim/toString。
 */
public record MetadataDraft(
        String title,
        String authors,
        String language,
        String publisher,
        String description) {

    /** compact constructor：所有 nullable 字段归一为 ""，避免下游 NPE 与 equals 语义混乱。 */
    public MetadataDraft {
        title = normalize(title);
        authors = normalize(authors);
        language = normalize(language);
        publisher = normalize(publisher);
        description = normalize(description);
    }

    /** 返回全空字段，用于 "清空" 按钮或 {@code null} 草稿兜底。 */
    public static MetadataDraft empty() {
        return new MetadataDraft("", "", "", "", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
