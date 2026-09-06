package org.chobit.epubra.lib.validation;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.util.Hrefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A 组（mimetype / container.xml）与容器增强规则（原始 OPF 的重复 id / href、版本、清单条目是否在 ZIP）。
 *
 * <p>只在能读到真实磁盘文件时执行：内存里 {@code Resources} 用双索引 LinkedHashMap，
 * 重复的 id / href 在读入时就被覆盖，检测不到；mimetype 也只有真实文件才有意义。
 */
public final class ContainerRules {

    private static final String MIMETYPE_VALUE = "application/epub+zip";
    private static final String CONTAINER_ENTRY = "META-INF/container.xml";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("2.0", "3.0");

    private ContainerRules() {
    }

    /** 执行全部容器级规则。 */
    public static List<ValidationIssue> check(ContainerFacts facts, Book book) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (facts == null) {
            return issues;
        }
        checkMimetype(facts, issues);
        checkContainerXml(facts, issues);
        checkRawOpf(facts, book, issues);
        checkManifestEntriesInZip(facts, book, issues);
        return issues;
    }

    private static void checkMimetype(ContainerFacts facts, List<ValidationIssue> issues) {
        // A01
        if (!facts.hasEntry("mimetype")) {
            issues.add(new ValidationIssue(IssueKind.MIMETYPE_MISSING,
                    "容器中缺少 mimetype 文件，阅读器可能无法识别这是 EPUB"));
            // 后续两条都以 mimetype 存在为前提
            return;
        }
        // A02
        if (!"mimetype".equals(facts.firstEntryName())) {
            String actual = facts.firstEntryName() == null ? "（读不到首个条目）" : facts.firstEntryName();
            issues.add(new ValidationIssue(IssueKind.MIMETYPE_NOT_FIRST,
                    "mimetype 必须是归档中的第一个条目，当前第一个条目是 " + actual,
                    null,
                    "firstEntry=" + actual));
        }
        // A03
        if (facts.mimetypePresent() && !facts.mimetypeStored()) {
            issues.add(new ValidationIssue(IssueKind.MIMETYPE_NOT_STORED,
                    "mimetype 被压缩存储了，规范要求不压缩（STORED）",
                    null,
                    "method=DEFLATED"));
        }
        // A04
        String content = facts.mimetypeContent();
        if (content == null) {
            issues.add(new ValidationIssue(IssueKind.MIMETYPE_CONTENT_INVALID,
                    "mimetype 文件读不出内容", null, "content=null"));
        } else if (!MIMETYPE_VALUE.equals(content)) {
            issues.add(new ValidationIssue(IssueKind.MIMETYPE_CONTENT_INVALID,
                    "mimetype 的内容应为 " + MIMETYPE_VALUE + "，实际为 '" + visible(content) + "'",
                    null,
                    "content=" + visible(content)));
        }
    }

    private static void checkContainerXml(ContainerFacts facts, List<ValidationIssue> issues) {
        // A05
        if (!facts.hasEntry(CONTAINER_ENTRY)) {
            issues.add(new ValidationIssue(IssueKind.CONTAINER_XML_MISSING,
                    "缺少 META-INF/container.xml，阅读器找不到包文档"));
            return;
        }
        // A06
        String fullPath = facts.rootfileFullPath();
        if (fullPath == null || fullPath.isBlank()) {
            issues.add(new ValidationIssue(IssueKind.CONTAINER_ROOTFILE_INVALID,
                    "container.xml 解析失败，或其中没有声明有效的 rootfile/@full-path",
                    null,
                    "present=" + facts.containerPresent()));
            return;
        }
        // A07
        if (!facts.hasEntry(fullPath)) {
            issues.add(new ValidationIssue(IssueKind.CONTAINER_ROOTFILE_MISSING,
                    "container.xml 的 rootfile 指向 " + fullPath + "，但容器中没有这个条目"));
        }
    }

    private static void checkRawOpf(ContainerFacts facts, Book book, List<ValidationIssue> issues) {
        // A08
        String opfPath = book.opfPath();
        if (opfPath == null || opfPath.isBlank() || !facts.hasEntry(opfPath)) {
            issues.add(new ValidationIssue(IssueKind.OPF_ENTRY_MISSING,
                    "包文档 " + nullSafe(opfPath) + " 在容器中不存在"));
        }

        ContainerFacts.RawOpf opf = facts.opf();
        if (opf == null || !opf.parsed()) {
            return;
        }

        // B09 重复 id
        Map<String, Integer> idCounts = new LinkedHashMap<>();
        for (ContainerFacts.ManifestItem item : opf.items()) {
            String id = item.id();
            if (id == null || id.isBlank()) {
                continue;
            }
            idCounts.merge(id, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : idCounts.entrySet()) {
            if (entry.getValue() > 1) {
                issues.add(new ValidationIssue(IssueKind.OPF_DUPLICATE_ID,
                        "manifest 中 id '" + entry.getKey() + "' 出现了 " + entry.getValue() + " 次，后出现的条目会覆盖前面的",
                        null,
                        "id=" + entry.getKey() + ", count=" + entry.getValue()));
            }
        }

        // B10 重复 href（按容器内路径归一化后比较）
        String baseDir = Hrefs.parentDirectory(opfBasePath(facts, book));
        Map<String, Integer> hrefCounts = new LinkedHashMap<>();
        for (ContainerFacts.ManifestItem item : opf.items()) {
            String href = item.href();
            if (href == null || href.isBlank()) {
                continue;
            }
            hrefCounts.merge(Hrefs.resolve(baseDir, href), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : hrefCounts.entrySet()) {
            if (entry.getValue() > 1) {
                issues.add(new ValidationIssue(IssueKind.OPF_DUPLICATE_HREF,
                        "manifest 中有 " + entry.getValue() + " 个条目指向 " + entry.getKey(),
                        null,
                        "href=" + entry.getKey() + ", count=" + entry.getValue()));
            }
        }

        // B11 版本
        String version = opf.version();
        if (version == null || version.isBlank()) {
            issues.add(new ValidationIssue(IssueKind.OPF_VERSION_INVALID,
                    "包文档没有声明 version 属性",
                    null,
                    "version=<missing>"));
        } else if (!SUPPORTED_VERSIONS.contains(version)) {
            issues.add(new ValidationIssue(IssueKind.OPF_VERSION_INVALID,
                    "包文档声明的 EPUB 版本 '" + version + "' 不受支持（应为 2.0 或 3.0）",
                    null,
                    "version=" + version));
        }
    }

    /** B08：清单 item 解析出的容器内路径是否真实存在于 ZIP。 */
    private static void checkManifestEntriesInZip(ContainerFacts facts, Book book, List<ValidationIssue> issues) {
        ContainerFacts.RawOpf opf = facts.opf();
        if (opf == null || !opf.parsed()) {
            return;
        }
        String baseDir = Hrefs.parentDirectory(opfBasePath(facts, book));
        Set<String> reported = new HashSet<>();
        for (ContainerFacts.ManifestItem item : opf.items()) {
            String href = item.href();
            if (href == null || href.isBlank() || href.endsWith("/")) {
                continue;
            }
            String containerPath = Hrefs.resolve(baseDir, href);
            if (containerPath.isEmpty() || facts.hasEntry(containerPath) || !reported.add(containerPath)) {
                continue;
            }
            issues.add(new ValidationIssue(IssueKind.MANIFEST_ITEM_NOT_IN_ZIP,
                    "manifest 条目 '" + item.id() + "' 指向 " + containerPath + "，容器中没有这个文件",
                    null,
                    "id=" + item.id() + ", href=" + href + ", resolved=" + containerPath));
        }
    }

    private static String opfBasePath(ContainerFacts facts, Book book) {
        if (facts.rootfileFullPath() != null && !facts.rootfileFullPath().isBlank()) {
            return facts.rootfileFullPath();
        }
        return book.opfPath() == null ? "" : book.opfPath();
    }

    /** 把不可见字符转成可见形式，便于用户理解 mimetype 到底多了什么。 */
    private static String visible(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String nullSafe(String value) {
        return value == null ? "（未设置）" : value;
    }
}
