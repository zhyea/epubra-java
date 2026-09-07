package org.chobit.epubra.app.support.editor;

import org.chobit.epubra.lib.domain.Metadata;

import java.util.Objects;

/**
 * 元数据表单 → {@link Metadata} 的纯逻辑桥。
 *
 * <p>把 MainController 中与 JavaFX 控件耦合的「读字段 / 比对 / 写回」三段
 * （720–740、1050–1070、1130–1140 行附近）抽成可 JVM 直跑的助手，从而
 * 在不启动 JavaFX Toolkit 的前提下写单元测试。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link MetadataDraft} 是不可变快照，承载「表单上用户看到的 5 个字符串字段」。</li>
 *   <li>{@code matches} 用 {@link Objects#equals} 做字段级比较，null / 空串语义与
 *       {@link Metadata} 当前 getter 保持一致（例如 {@code language()} 默认 {@code "zh-CN"}，
 *       所以草稿里写 {@code "zh-CN"} 也算"未变化"）。</li>
 *   <li>{@code apply} 直接调用 setter：{@code setFirstTitle} 接受 {@code null} 而不抛；
 *       {@code setCreatorsInline} 内部已处理 null/blank 后跳过。{@code null} 之外的值原样落库，
 *       不在校验环节做"清洗"。</li>
 * </ul>
 */
public final class MetadataOps {

    private MetadataOps() {
    }

    /** 从 {@link Metadata} 抓取一份 UI 可绑定的不可变草稿。 */
    public static MetadataDraft snapshot(Metadata metadata) {
        if (metadata == null) {
            return MetadataDraft.empty();
        }
        return new MetadataDraft(
                metadata.firstTitle(),
                metadata.creatorsInline(),
                metadata.language(),
                metadata.publisher(),
                metadata.description());
    }

    /** UI 草稿与图书当前元数据是否逐字段相等。{@code null} 草稿按"全空"对待。 */
    public static boolean matches(Metadata metadata, MetadataDraft draft) {
        MetadataDraft current = snapshot(metadata);
        MetadataDraft other = draft == null ? MetadataDraft.empty() : draft;
        return Objects.equals(current.title(), other.title())
                && Objects.equals(current.authors(), other.authors())
                && Objects.equals(current.language(), other.language())
                && Objects.equals(current.publisher(), other.publisher())
                && Objects.equals(current.description(), other.description());
    }

    /** UI 草稿与图书当前元数据是否有实质差异。是 {@link #matches} 的反向语义。 */
    public static boolean isDirty(Metadata metadata, MetadataDraft draft) {
        return !matches(metadata, draft);
    }

    /** 把 UI 草稿写回图书元数据。{@code null} 草稿视为空表，按"清空"语义处理。 */
    public static void apply(Metadata metadata, MetadataDraft draft) {
        if (metadata == null) {
            return;
        }
        MetadataDraft src = draft == null ? MetadataDraft.empty() : draft;
        metadata.setFirstTitle(src.title());
        metadata.setCreatorsInline(src.authors());
        metadata.setLanguage(src.language());
        metadata.setPublisher(src.publisher());
        metadata.setDescription(src.description());
    }

    /** 判断草稿是否全部为空（用作"清空所有元数据"按钮之类的场景）。 */
    public static boolean isBlank(MetadataDraft draft) {
        MetadataDraft src = draft == null ? MetadataDraft.empty() : draft;
        return isBlank(src.title())
                && isBlank(src.authors())
                && isBlank(src.language())
                && isBlank(src.publisher())
                && isBlank(src.description());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
