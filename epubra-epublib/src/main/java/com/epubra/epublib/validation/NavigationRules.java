package com.epubra.epublib.validation;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.EpubVersion;
import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.SpineReference;
import com.epubra.epublib.domain.TOCReference;
import com.epubra.epublib.util.Hrefs;
import com.epubra.epublib.util.ResourceReferences;
import com.epubra.epublib.util.Xmls;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.epubra.epublib.validation.StructureSupport.dedupe;
import static com.epubra.epublib.validation.StructureSupport.displayTitle;
import static com.epubra.epublib.validation.StructureSupport.fragmentIds;
import static com.epubra.epublib.validation.StructureSupport.navResources;
import static com.epubra.epublib.validation.StructureSupport.nullSafe;
import static com.epubra.epublib.validation.StructureSupport.pickTocNav;
import static com.epubra.epublib.validation.StructureSupport.stripFragment;

/**
 * D 组：TOC（书籍目录树） / Nav（EPUB 3 导航文档） / NCX（EPUB 2 目录） / 封面。
 */
final class NavigationRules {

    private NavigationRules() {
    }

    static void check(Book book, boolean containerMode, List<ValidationIssue> issues,
                      Map<String, Set<String>> fragmentCache) {
        checkToc(book, fragmentCache, issues);
        checkNav(book, containerMode, issues);
        checkNcx(book, containerMode, issues);
        checkCover(book, issues);
    }

    // ----- TOC（书籍内存目录）-----

    private static void checkToc(Book book, Map<String, Set<String>> fragmentCache, List<ValidationIssue> issues) {
        List<TOCReference> nodes = book.toc().flatten();

        if (book.toc().isEmpty()) {
            issues.add(new ValidationIssue(IssueKind.TOC_EMPTY, "书籍没有任何目录条目，读者无法按章节跳转"));
            return;
        }

        Set<String> spineIds = new HashSet<>();
        for (SpineReference reference : book.spine().references()) {
            spineIds.add(reference.resourceId());
        }

        for (TOCReference node : nodes) {
            String rawHref = node.resourceHref();

            if (node.title() == null || node.title().isBlank()) {
                issues.add(new ValidationIssue(IssueKind.TOC_TITLE_BLANK,
                        "指向 " + nullSafe(rawHref) + " 的目录条目没有标题", null, "href=" + nullSafe(rawHref)));
            }

            String resolved = Hrefs.resolve(book.contentDirectory(), rawHref);
            ResourceReferences.Lookup lookup = ResourceReferences.findResource(book.resources(), resolved);
            Resource target = lookup.resource();
            if (target == null) {
                issues.add(new ValidationIssue(IssueKind.TOC_TARGET_MISSING,
                        "目录条目「" + displayTitle(node) + "」指向 " + nullSafe(rawHref) + "，manifest 中没有这个资源",
                        null,
                        "href=" + nullSafe(rawHref) + ", resolved=" + resolved));
                continue;
            }

            if (!spineIds.contains(target.id())) {
                issues.add(new ValidationIssue(IssueKind.TOC_TARGET_NOT_IN_SPINE,
                        "目录条目「" + displayTitle(node) + "」指向的 " + target.fileName() + " 不在阅读顺序中",
                        target.href()));
            }

            String fragmentId = node.fragmentId();
            if (fragmentId != null && !fragmentId.isBlank()) {
                Set<String> ids = fragmentIds(target, fragmentCache);
                if (!ids.isEmpty() && !ids.contains(fragmentId)) {
                    issues.add(new ValidationIssue(IssueKind.TOC_FRAGMENT_MISSING,
                            "目录条目「" + displayTitle(node) + "」指向的锚点 #" + fragmentId + " 在 " + target.fileName() + " 中不存在",
                            target.href(),
                            "fragment=" + fragmentId));
                }
            }
        }
    }

    // ----- Nav（EPUB 3 导航文档）-----

    private static void checkNav(Book book, boolean containerMode, List<ValidationIssue> issues) {
        List<Resource> navList = navResources(book);

        if (navList.isEmpty()) {
            if (book.version() == EpubVersion.EPUB_3) {
                issues.add(new ValidationIssue(IssueKind.NAV_MISSING,
                        "EPUB 3 要求提供一个带 nav 属性的导航文档，当前书籍没有"));
            }
            return;
        }

        if (navList.size() > 1) {
            String names = navList.stream()
                    .map(Resource::fileName)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            issues.add(new ValidationIssue(IssueKind.NAV_MULTIPLE,
                    "有 " + navList.size() + " 个资源被标记为 nav：" + names,
                    navList.get(0).href(),
                    "count=" + navList.size()));
        }

        Resource nav = navList.get(0);

        if (!MediaTypes.XHTML.equals(nav.mediaType())) {
            issues.add(new ValidationIssue(IssueKind.NAV_NOT_XHTML,
                    "导航文档的媒体类型应为 " + MediaTypes.XHTML + "，当前为 " + nav.mediaType(),
                    nav.href(),
                    "mediaType=" + nav.mediaType()));
            return;
        }

        Document document;
        try {
            document = Xmls.parse(nav.data());
        } catch (RuntimeException e) {
            issues.add(new ValidationIssue(IssueKind.NAV_PARSE_FAILED,
                    "导航文档 " + nav.fileName() + " 无法解析：" + e.getMessage(),
                    nav.href(),
                    "error=" + e.getMessage()));
            return;
        }

        Element navElement = pickTocNav(document.getDocumentElement());
        Element ol = navElement == null ? null : Xmls.child(navElement, "ol");

        if (navElement == null || ol == null || Xmls.children(ol, "li").isEmpty()) {
            issues.add(new ValidationIssue(IssueKind.NAV_EMPTY,
                    "导航文档 " + nav.fileName() + " 中没有目录条目（缺少 nav[epub:type=toc] 或其中没有 li）",
                    nav.href()));
            return;
        }

        String navDir = Hrefs.parentDirectory(nav.href());
        List<String> navTargets = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        collectNavTargets(ol, navDir, book, navTargets, missing);

        for (String target : missing) {
            issues.add(new ValidationIssue(IssueKind.NAV_TARGET_MISSING,
                    "导航文档中的链接 " + target + " 指向的资源不存在",
                    nav.href(),
                    "target=" + target));
        }

        if (containerMode && !book.toc().isEmpty()) {
            List<String> tocTargets = new ArrayList<>();
            for (TOCReference node : book.toc().flatten()) {
                tocTargets.add(Hrefs.resolve(book.contentDirectory(), node.resourceHref()));
            }
            List<String> navDeduped = dedupe(navTargets);
            List<String> tocDeduped = dedupe(tocTargets);
            if (!navDeduped.isEmpty() && !navDeduped.equals(tocDeduped)) {
                issues.add(new ValidationIssue(IssueKind.NAV_VS_TOC_INCONSISTENT,
                        "导航文档有 " + navDeduped.size() + " 个条目，书籍目录有 " + tocDeduped.size() + " 个，两者顺序或内容不一致",
                        nav.href(),
                        "nav=" + navDeduped + ", toc=" + tocDeduped));
            }
        }
    }

    private static void collectNavTargets(Element ol, String navDir, Book book,
                                          List<String> targets, Set<String> missing) {
        for (Element li : Xmls.children(ol, "li")) {
            Element anchor = Xmls.child(li, "a");
            if (anchor != null) {
                String rawHref = anchor.getAttribute("href");
                if (rawHref != null && !rawHref.isBlank() && !ResourceReferences.isExternal(rawHref)) {
                    String resolved = Hrefs.resolve(navDir, rawHref);
                    targets.add(resolved);
                    if (ResourceReferences.findResource(book.resources(), stripFragment(resolved)).resource() == null) {
                        missing.add(rawHref);
                    }
                }
            }
            Element subList = Xmls.child(li, "ol");
            if (subList != null) {
                collectNavTargets(subList, navDir, book, targets, missing);
            }
        }
    }

    // ----- NCX（EPUB 2 兼容目录）-----

    private static void checkNcx(Book book, boolean containerMode, List<ValidationIssue> issues) {
        List<Resource> ncxResources = new ArrayList<>();
        for (Resource resource : book.resources().allList()) {
            if (MediaTypes.NCX.equals(resource.mediaType())) {
                ncxResources.add(resource);
            }
        }

        if (ncxResources.isEmpty()) {
            issues.add(new ValidationIssue(IssueKind.NCX_MISSING,
                    "缺少 NCX 目录，EPUB 2 阅读器将无法显示目录"));
            return;
        }

        Resource ncx = ncxResources.get(0);

        String tocId = book.spine().tocResourceId();
        Resource referenced = tocId == null || tocId.isBlank() ? null : book.resources().getById(tocId);
        if (referenced == null || !MediaTypes.NCX.equals(referenced.mediaType())) {
            issues.add(new ValidationIssue(IssueKind.NCX_NOT_REFERENCED,
                    "存在 NCX 目录 " + ncx.fileName() + "，但 spine 的 toc 属性没有指向它",
                    ncx.href(),
                    "toc=" + nullSafe(tocId)));
        }

        Document document;
        try {
            document = Xmls.parse(ncx.data());
        } catch (RuntimeException e) {
            issues.add(new ValidationIssue(IssueKind.NCX_PARSE_FAILED,
                    "NCX 目录 " + ncx.fileName() + " 无法解析：" + e.getMessage(),
                    ncx.href(),
                    "error=" + e.getMessage()));
            return;
        }

        Element navMap = Xmls.child(document.getDocumentElement(), "navMap");
        if (navMap == null) {
            issues.add(new ValidationIssue(IssueKind.NCX_PARSE_FAILED,
                    "NCX 目录 " + ncx.fileName() + " 中找不到 navMap",
                    ncx.href()));
            return;
        }

        String ncxDir = Hrefs.parentDirectory(ncx.href());
        List<String> srcs = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        collectNcxSrcs(navMap, ncxDir, book, srcs, missing);

        for (String src : missing) {
            issues.add(new ValidationIssue(IssueKind.NCX_TARGET_MISSING,
                    "NCX 中的链接 " + src + " 指向的资源不存在",
                    ncx.href(),
                    "src=" + src));
        }

        if (containerMode && !book.toc().isEmpty()) {
            List<String> tocTargets = new ArrayList<>();
            for (TOCReference node : book.toc().flatten()) {
                tocTargets.add(Hrefs.resolve(book.contentDirectory(), node.resourceHref()));
            }
            List<String> ncxDeduped = dedupe(srcs);
            List<String> tocDeduped = dedupe(tocTargets);
            if (!ncxDeduped.isEmpty() && !ncxDeduped.equals(tocDeduped)) {
                issues.add(new ValidationIssue(IssueKind.NCX_VS_TOC_INCONSISTENT,
                        "NCX 有 " + ncxDeduped.size() + " 个条目，书籍目录有 " + tocDeduped.size() + " 个，两者顺序或内容不一致",
                        ncx.href(),
                        "ncx=" + ncxDeduped + ", toc=" + tocDeduped));
            }
        }
    }

    private static void collectNcxSrcs(Element parent, String ncxDir, Book book,
                                       List<String> srcs, Set<String> missing) {
        for (Element navPoint : Xmls.children(parent, "navPoint")) {
            Element content = Xmls.child(navPoint, "content");
            if (content != null) {
                String src = content.getAttribute("src");
                if (src != null && !src.isBlank() && !ResourceReferences.isExternal(src)) {
                    String resolved = Hrefs.resolve(ncxDir, src);
                    srcs.add(resolved);
                    if (ResourceReferences.findResource(book.resources(), stripFragment(resolved)).resource() == null) {
                        missing.add(src);
                    }
                }
            }
            collectNcxSrcs(navPoint, ncxDir, book, srcs, missing);
        }
    }

    // ----- Cover -----

    private static void checkCover(Book book, List<ValidationIssue> issues) {
        String coverId = book.coverResourceId();
        if (coverId == null || coverId.isBlank()) {
            return;
        }
        Resource cover = book.resources().getById(coverId);
        if (cover == null) {
            issues.add(new ValidationIssue(IssueKind.COVER_ID_UNRESOLVED,
                    "封面资源 id '" + coverId + "' 在 manifest 中不存在",
                    null,
                    "coverId=" + coverId));
            return;
        }
        String mediaType = cover.mediaType();
        if (mediaType == null || !mediaType.startsWith("image/")) {
            issues.add(new ValidationIssue(IssueKind.COVER_NOT_IMAGE,
                    "封面资源 " + cover.fileName() + " 不是图片（" + nullSafe(mediaType) + "）",
                    cover.href(),
                    "mediaType=" + nullSafe(mediaType)));
        }
    }
}
