package com.epubra.epublib.validation;

/**
 * 全部校验项的唯一枚举源。
 *
 * <p>每条规则由 {@code code}（如 {@code A01}）、所属分组、默认级别与中文说明模板构成。
 * 新增规则 = 加一个枚举常量 + 在对应规则类中加一个判定分支，规则清单与代码一一对应。
 *
 * <p>{@code template()} 描述规则本身（通用说明），具体值由
 * {@link ValidationIssue#message()} 填充，技术细节放 {@link ValidationIssue#detail()}。
 */
public enum IssueKind {

    // ---------------------------------------------------------------- A 组 · 容器与 mimetype（仅容器校验）
    MIMETYPE_MISSING("A01", IssueGroup.CONTAINER, Severity.ERROR, "容器中缺少 mimetype 文件"),
    MIMETYPE_NOT_FIRST("A02", IssueGroup.CONTAINER, Severity.ERROR, "mimetype 必须是归档中的第一个条目"),
    MIMETYPE_NOT_STORED("A03", IssueGroup.CONTAINER, Severity.ERROR, "mimetype 必须以不压缩的方式存储"),
    MIMETYPE_CONTENT_INVALID("A04", IssueGroup.CONTAINER, Severity.ERROR, "mimetype 的内容必须是 application/epub+zip，不能包含多余空白或换行"),
    CONTAINER_XML_MISSING("A05", IssueGroup.CONTAINER, Severity.ERROR, "缺少 META-INF/container.xml"),
    CONTAINER_ROOTFILE_INVALID("A06", IssueGroup.CONTAINER, Severity.ERROR, "container.xml 中缺少有效的 rootfile 声明"),
    CONTAINER_ROOTFILE_MISSING("A07", IssueGroup.CONTAINER, Severity.ERROR, "container.xml 的 rootfile 指向的包文档在容器中不存在"),
    OPF_ENTRY_MISSING("A08", IssueGroup.CONTAINER, Severity.ERROR, "包文档（OPF）在容器中不存在"),
    CONTAINER_UNREADABLE("A09", IssueGroup.CONTAINER, Severity.ERROR, "无法读取 EPUB 容器，容器级规则已跳过"),

    // ---------------------------------------------------------------- B 组 · OPF 与 manifest
    OPF_PATH_INVALID("B01", IssueGroup.OPF, Severity.ERROR, "OPF 路径不合法"),
    MANIFEST_EMPTY("B02", IssueGroup.OPF, Severity.ERROR, "manifest 中没有任何条目"),
    MANIFEST_ID_BLANK("B03", IssueGroup.OPF, Severity.ERROR, "manifest 条目缺少 id"),
    MANIFEST_HREF_BLANK("B04", IssueGroup.OPF, Severity.ERROR, "manifest 条目的 href 为空或指向目录"),
    MANIFEST_MEDIA_TYPE_BLANK("B05", IssueGroup.OPF, Severity.ERROR, "manifest 条目缺少 media-type"),
    MANIFEST_MEDIA_TYPE_MISMATCH("B06", IssueGroup.OPF, Severity.WARNING, "manifest 条目声明的媒体类型与文件扩展名不一致"),
    MANIFEST_ITEM_EMPTY_DATA("B07", IssueGroup.OPF, Severity.WARNING, "资源内容为空"),
    MANIFEST_ITEM_NOT_IN_ZIP("B08", IssueGroup.OPF, Severity.ERROR, "manifest 条目在容器中找不到对应的文件"),
    OPF_DUPLICATE_ID("B09", IssueGroup.OPF, Severity.ERROR, "manifest 中存在重复的 id"),
    OPF_DUPLICATE_HREF("B10", IssueGroup.OPF, Severity.ERROR, "manifest 中有多个条目指向同一个文件"),
    OPF_VERSION_INVALID("B11", IssueGroup.OPF, Severity.ERROR, "包文档未声明合法的 EPUB 版本"),

    // ---------------------------------------------------------------- C 组 · spine 与 manifest 的对应关系
    SPINE_EMPTY("C01", IssueGroup.SPINE, Severity.ERROR, "spine 中没有任何 itemref"),
    SPINE_IDREF_UNRESOLVED("C02", IssueGroup.SPINE, Severity.ERROR, "spine 中的 idref 在 manifest 中不存在"),
    SPINE_IDREF_DUPLICATE("C03", IssueGroup.SPINE, Severity.WARNING, "spine 中重复引用了同一个资源"),
    SPINE_TOC_ID_UNRESOLVED("C04", IssueGroup.SPINE, Severity.ERROR, "spine 的 toc 属性指向的目录资源不存在"),
    SPINE_TOC_NOT_NCX("C05", IssueGroup.SPINE, Severity.WARNING, "spine 的 toc 属性未指向 NCX 资源"),
    SPINE_NON_CONTENT_DOCUMENT("C06", IssueGroup.SPINE, Severity.ERROR, "spine 中引用了非内容文档（只允许 XHTML 与 SVG）"),
    MANIFEST_DOC_NOT_IN_SPINE("C07", IssueGroup.SPINE, Severity.WARNING, "正文文档没有出现在 spine 中，阅读器不会显示它"),
    SPINE_ALL_NON_LINEAR("C08", IssueGroup.SPINE, Severity.WARNING, "spine 中所有条目都是辅助内容（linear=no）"),
    SPINE_ORDER_VS_TOC("C09", IssueGroup.SPINE, Severity.WARNING, "目录顺序与阅读顺序不一致"),

    // ---------------------------------------------------------------- D 组 · 目录一致性：Nav / NCX / TOC / 封面
    TOC_EMPTY("D01", IssueGroup.NAVIGATION, Severity.WARNING, "书籍没有任何目录条目"),
    TOC_TARGET_MISSING("D02", IssueGroup.NAVIGATION, Severity.ERROR, "目录条目指向的资源不存在"),
    TOC_TARGET_NOT_IN_SPINE("D03", IssueGroup.NAVIGATION, Severity.WARNING, "目录条目指向的资源不在阅读顺序中"),
    TOC_FRAGMENT_MISSING("D04", IssueGroup.NAVIGATION, Severity.WARNING, "目录条目指向的锚点在目标文档中不存在"),
    TOC_TITLE_BLANK("D05", IssueGroup.NAVIGATION, Severity.WARNING, "目录条目缺少标题"),
    NAV_MISSING("D06", IssueGroup.NAVIGATION, Severity.ERROR, "EPUB 3 书籍缺少导航文档（nav）"),
    NAV_MULTIPLE("D07", IssueGroup.NAVIGATION, Severity.WARNING, "存在多个标记为 nav 的导航文档"),
    NAV_NOT_XHTML("D08", IssueGroup.NAVIGATION, Severity.ERROR, "导航文档的媒体类型不是 XHTML"),
    NAV_EMPTY("D09", IssueGroup.NAVIGATION, Severity.WARNING, "导航文档中没有目录条目"),
    NAV_PARSE_FAILED("D10", IssueGroup.NAVIGATION, Severity.ERROR, "导航文档无法解析"),
    NAV_TARGET_MISSING("D11", IssueGroup.NAVIGATION, Severity.ERROR, "导航文档中的链接指向不存在的资源"),
    NAV_VS_TOC_INCONSISTENT("D12", IssueGroup.NAVIGATION, Severity.WARNING, "导航文档的条目与书籍目录不一致"),
    NCX_MISSING("D13", IssueGroup.NAVIGATION, Severity.WARNING, "缺少 NCX 目录，EPUB 2 阅读器将无法显示目录"),
    NCX_NOT_REFERENCED("D14", IssueGroup.NAVIGATION, Severity.WARNING, "NCX 没有被 spine 的 toc 属性引用"),
    NCX_PARSE_FAILED("D15", IssueGroup.NAVIGATION, Severity.ERROR, "NCX 目录无法解析"),
    NCX_TARGET_MISSING("D16", IssueGroup.NAVIGATION, Severity.ERROR, "NCX 中的链接指向不存在的资源"),
    NCX_VS_TOC_INCONSISTENT("D17", IssueGroup.NAVIGATION, Severity.WARNING, "NCX 的条目与书籍目录不一致"),
    COVER_ID_UNRESOLVED("D18", IssueGroup.NAVIGATION, Severity.ERROR, "封面资源 id 在 manifest 中不存在"),
    COVER_NOT_IMAGE("D19", IssueGroup.NAVIGATION, Severity.WARNING, "封面资源不是图片"),

    // ---------------------------------------------------------------- E 组 · 资源引用完整性
    XHTML_NOT_WELL_FORMED("E01", IssueGroup.REFERENCE, Severity.ERROR, "文档不是良构的 XML，严格阅读器会拒绝加载"),
    REFERENCE_TARGET_MISSING("E02", IssueGroup.REFERENCE, Severity.ERROR, "引用指向的资源在 manifest 中不存在"),
    REFERENCE_TARGET_OUTSIDE("E03", IssueGroup.REFERENCE, Severity.ERROR, "引用越出了容器根目录"),
    FRAGMENT_MISSING("E04", IssueGroup.REFERENCE, Severity.WARNING, "引用指向的锚点在目标文档中不存在"),
    RESOURCE_ORPHAN("E05", IssueGroup.REFERENCE, Severity.WARNING, "资源没有被任何地方引用，可以安全清理"),

    // ---------------------------------------------------------------- F 组 · 元数据
    METADATA_TITLE_MISSING("F01", IssueGroup.METADATA, Severity.ERROR, "书籍缺少标题"),
    METADATA_IDENTIFIER_MISSING("F02", IssueGroup.METADATA, Severity.ERROR, "书籍缺少唯一标识符"),
    METADATA_LANGUAGE_MISSING("F03", IssueGroup.METADATA, Severity.ERROR, "书籍缺少语言或语言代码不合法"),
    METADATA_MODIFIED_MISSING("F04", IssueGroup.METADATA, Severity.WARNING, "EPUB 3 书籍缺少 dcterms:modified 时间戳");

    private final String code;
    private final IssueGroup group;
    private final Severity severity;
    private final String template;

    IssueKind(String code, IssueGroup group, Severity severity, String template) {
        this.code = code;
        this.group = group;
        this.severity = severity;
        this.template = template;
    }

    /** 规则编号，例如 {@code "A01"}。 */
    public String code() {
        return code;
    }

    public IssueGroup group() {
        return group;
    }

    /** 默认级别，允许在具体问题上覆盖。 */
    public Severity severity() {
        return severity;
    }

    /** 规则的通用中文说明，不含具体值。 */
    public String template() {
        return template;
    }
}
