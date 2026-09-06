package org.chobit.epubra.lib.validation;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.EpubVersion;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.SpineReference;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.util.Hrefs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.chobit.epubra.lib.validation.StructureSupport.isNavDocument;
import static org.chobit.epubra.lib.validation.StructureSupport.nullSafe;
import static org.chobit.epubra.lib.validation.StructureSupport.resolveTocResource;

/**
 * B 组（OPF / manifest 自身）与 C 组（spine ↔ manifest）规则。
 */
final class OpfSpineRules {

    private OpfSpineRules() {
    }

    private static final String OCTET_STREAM = "application/octet-stream";

    static void check(Book book, boolean containerMode, List<ValidationIssue> issues) {
        checkOpfBasics(book, containerMode, issues);
        checkSpine(book, issues);
    }

    private static void checkOpfBasics(Book book, boolean containerMode, List<ValidationIssue> issues) {
        // B01
        String opfPath = book.opfPath();
        if (opfPath == null || opfPath.isBlank()
                || opfPath.startsWith("/")
                || Hrefs.resolve("", opfPath).isEmpty()
                || opfPath.contains("..")) {
            issues.add(new ValidationIssue(IssueKind.OPF_PATH_INVALID,
                    "包文档路径 '" + nullSafe(opfPath) + "' 不合法（不能为空、以 / 开头或包含 ..）",
                    null,
                    "opfPath=" + nullSafe(opfPath)));
        }

        // B02：manifest 中没有任何条目
        if (book.resources().size() == 0) {
            issues.add(new ValidationIssue(IssueKind.MANIFEST_EMPTY, "manifest 中没有任何条目"));
        }

        // B03 / B04 / B05 / B06 / B07 / B10
        Set<String> seenIds = new LinkedHashSet<>();
        Set<String> seenHrefs = new LinkedHashSet<>();
        for (Resource resource : book.resources().allList()) {
            String id = resource.id();
            if (id == null || id.isBlank()) {
                issues.add(new ValidationIssue(IssueKind.MANIFEST_ID_BLANK,
                        "manifest 条目缺少 id",
                        resource.href()));
            } else if (!seenIds.add(id)) {
                issues.add(new ValidationIssue(IssueKind.OPF_DUPLICATE_ID,
                        "manifest 中存在重复的 id '" + id + "'",
                        resource.href(),
                        "id=" + id));
            }

            String href = resource.href();
            if (href == null || href.isBlank() || href.endsWith("/")) {
                issues.add(new ValidationIssue(IssueKind.MANIFEST_HREF_BLANK,
                        "manifest 条目的 href 为空或指向目录",
                        null,
                        "id=" + nullSafe(id)));
            } else if (!seenHrefs.add(href)) {
                issues.add(new ValidationIssue(IssueKind.OPF_DUPLICATE_HREF,
                        "manifest 中有多个条目指向同一个文件 '" + href + "'",
                        null,
                        "href=" + href));
            }

            String mediaType = resource.mediaType();
            if (mediaType == null || mediaType.isBlank()) {
                issues.add(new ValidationIssue(IssueKind.MANIFEST_MEDIA_TYPE_BLANK,
                        "manifest 条目缺少 media-type",
                        resource.href(),
                        "id=" + nullSafe(id)));
            } else if (resource.fileName() != null) {
                String expected = MediaTypes.guessByExtension(resource.fileName());
                if (expected != null && !OCTET_STREAM.equals(expected) && !expected.equals(mediaType)) {
                    issues.add(new ValidationIssue(IssueKind.MANIFEST_MEDIA_TYPE_MISMATCH,
                            "manifest 条目 " + resource.fileName() + " 声明的媒体类型 '" + mediaType
                                    + "' 与文件扩展名推断的 '" + expected + "' 不一致",
                            resource.href(),
                            "declared=" + mediaType + ", expected=" + expected));
                }
            }

            // B07
            if (!containerMode && resource.data().length == 0 && !resource.isNavDocument()
                    && !MediaTypes.NCX.equals(resource.mediaType())) {
                issues.add(new ValidationIssue(IssueKind.MANIFEST_ITEM_EMPTY_DATA,
                        "资源内容为空，写出后会得到一个 0 字节的文件",
                        resource.href(),
                        "id=" + nullSafe(id)));
            }
        }

        // B11
        if (book.version() != EpubVersion.EPUB_3
                && book.version() != EpubVersion.EPUB_2) {
            issues.add(new ValidationIssue(IssueKind.OPF_VERSION_INVALID,
                    "包文档未声明合法的 EPUB 版本（必须是 EPUB 2 或 EPUB 3）",
                    null,
                    "version=" + book.version()));
        }
    }

    private static void checkSpine(Book book, List<ValidationIssue> issues) {
        List<SpineReference> references = book.spine().references();
        // LinkedHashSet：保留 spine 的实际顺序，C09 顺序对比依赖此顺序
        Set<String> spineIds = new LinkedHashSet<>();

        // C01
        if (references.isEmpty() && book.resources().size() > 0) {
            issues.add(new ValidationIssue(IssueKind.SPINE_EMPTY, "spine 中没有任何 itemref"));
        }

        boolean anyLinear = false;
        for (SpineReference reference : references) {
            String id = reference.resourceId();
            if (id == null || id.isBlank()) {
                continue;
            }
            spineIds.add(id);

            // C02
            Resource resource = book.resources().getById(id);
            if (resource == null) {
                issues.add(new ValidationIssue(IssueKind.SPINE_IDREF_UNRESOLVED,
                        "spine 中的 idref '" + id + "' 在 manifest 中不存在",
                        null,
                        "idref=" + id));
                continue;
            }

            // C06
            String mediaType = resource.mediaType();
            if (mediaType == null
                    || (!MediaTypes.XHTML.equals(mediaType) && !MediaTypes.SVG.equals(mediaType))) {
                issues.add(new ValidationIssue(IssueKind.SPINE_NON_CONTENT_DOCUMENT,
                        "spine 引用的 " + resource.fileName() + " 不是内容文档（仅允许 XHTML / SVG）",
                        resource.href(),
                        "mediaType=" + nullSafe(mediaType)));
            }

            if (reference.linear()) {
                anyLinear = true;
            }
        }

        // C03
        Set<String> seen = new LinkedHashSet<>();
        for (SpineReference reference : references) {
            String id = reference.resourceId();
            if (id != null && !seen.add(id)) {
                issues.add(new ValidationIssue(IssueKind.SPINE_IDREF_DUPLICATE,
                        "spine 中重复引用了同一个资源 " + id,
                        null,
                        "idref=" + id));
            }
        }

        // C04 / C05：spine 的 toc 属性
        String tocId = book.spine().tocResourceId();
        Resource toc = tocId == null || tocId.isBlank() ? null : book.resources().getById(tocId);
        if (tocId != null && !tocId.isBlank() && toc == null) {
            issues.add(new ValidationIssue(IssueKind.SPINE_TOC_ID_UNRESOLVED,
                    "spine 的 toc 属性 '" + tocId + "' 指向的目录资源不存在",
                    null,
                    "toc=" + tocId));
        } else if (toc != null && !MediaTypes.NCX.equals(toc.mediaType())) {
            issues.add(new ValidationIssue(IssueKind.SPINE_TOC_NOT_NCX,
                    "spine 的 toc 属性未指向 NCX 资源（当前指向 " + nullSafe(toc.mediaType()) + "）",
                    toc.href(),
                    "toc=" + tocId + ", mediaType=" + nullSafe(toc.mediaType())));
        }

        // C07：正文文档未入 spine
        for (Resource resource : book.resources().allList()) {
            if (!MediaTypes.XHTML.equals(resource.mediaType()) || isNavDocument(resource)) {
                continue;
            }
            if (!spineIds.contains(resource.id())) {
                issues.add(new ValidationIssue(IssueKind.MANIFEST_DOC_NOT_IN_SPINE,
                        resource.fileName() + " 是正文文档却没有出现在 spine 中，阅读器不会显示它",
                        resource.href()));
            }
        }

        // C08
        if (!references.isEmpty() && !anyLinear) {
            issues.add(new ValidationIssue(IssueKind.SPINE_ALL_NON_LINEAR,
                    "spine 中所有条目都是 linear=no，读者打开书时不会看到任何正文"));
        }

        // C09
        checkSpineOrderVersusToc(book, spineIds, issues);
    }

    private static void checkSpineOrderVersusToc(Book book, Set<String> spineIds, List<ValidationIssue> issues) {
        if (book.spine().size() == 0 || book.toc().isEmpty()) {
            return;
        }
        List<String> tocIds = new ArrayList<>();
        Set<String> tocSeen = new LinkedHashSet<>();
        for (TOCReference node : book.toc().flatten()) {
            Resource resource = resolveTocResource(book, node);
            if (resource == null || resource.id() == null || !tocSeen.add(resource.id())) {
                continue;
            }
            tocIds.add(resource.id());
        }
        Set<String> common = new LinkedHashSet<>(tocIds);
        common.retainAll(spineIds);
        if (common.size() < 2) {
            return;
        }
        List<String> spineOrder = new ArrayList<>();
        for (String id : spineIds) {
            if (common.contains(id)) {
                spineOrder.add(id);
            }
        }
        List<String> tocOrder = new ArrayList<>();
        for (String id : tocIds) {
            if (common.contains(id)) {
                tocOrder.add(id);
            }
        }
        if (!spineOrder.equals(tocOrder)) {
            issues.add(new ValidationIssue(IssueKind.SPINE_ORDER_VS_TOC,
                    "目录顺序与阅读顺序（spine）不一致，读者按目录跳转时会感到错乱",
                    null,
                    "spine=" + spineOrder + ", toc=" + tocOrder));
        }
    }
}
