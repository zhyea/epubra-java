package org.chobit.epubra.lib.validation;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.ResourceReferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * E 组：正文 / CSS / SVG 的良构性、引用解析、断链、越界、锚点缺失与孤儿资源。
 *
 * <p>良构性检查与引用抽取共用一次 {@link ResourceReferences#extract(Resource)}，避免每个文档解析两遍。
 * 孤儿判定直接复用 {@link Book#unreferencedResources()}，保证「校验报 N 个孤儿」与
 * 「点清理删 N 个」永远一致。
 */
public final class ReferenceRules {

    private ReferenceRules() {
    }

    /** 执行全部资源引用规则。 */
    public static List<ValidationIssue> check(Book book) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (book == null) {
            return issues;
        }

        Map<String, ResourceReferences.Extraction> extractions = new LinkedHashMap<>();
        for (Resource resource : book.resources().allList()) {
            if (!isScannable(resource)) {
                continue;
            }
            extractions.put(resource.href(), ResourceReferences.extract(resource));
        }

        checkWellFormed(book, extractions, issues);
        checkReferences(book, extractions, issues);
        checkOrphans(book, issues);
        return issues;
    }

    /** E01：XHTML / SVG 必须是良构 XML。 */
    private static void checkWellFormed(Book book,
                                        Map<String, ResourceReferences.Extraction> extractions,
                                        List<ValidationIssue> issues) {
        for (Resource resource : book.resources().allList()) {
            String mediaType = resource.mediaType();
            if (!MediaTypes.XHTML.equals(mediaType) && !MediaTypes.SVG.equals(mediaType)) {
                continue;
            }
            ResourceReferences.Extraction extraction = extractions.get(resource.href());
            if (extraction != null && !extraction.wellFormed()) {
                issues.add(new ValidationIssue(IssueKind.XHTML_NOT_WELL_FORMED,
                        resource.fileName() + " 不是良构的 XML，严格阅读器会拒绝加载",
                        resource.href()));
            }
        }
    }

    /** E02 / E03 / E04：引用目标是否存在、是否越界、锚点是否存在。 */
    private static void checkReferences(Book book,
                                        Map<String, ResourceReferences.Extraction> extractions,
                                        List<ValidationIssue> issues) {
        Map<String, Set<String>> fragmentCache = new HashMap<>();

        for (Map.Entry<String, ResourceReferences.Extraction> entry : extractions.entrySet()) {
            Resource source = book.resources().getByHref(entry.getKey());
            if (source == null) {
                continue;
            }
            String baseDir = Hrefs.parentDirectory(source.href());

            for (ResourceReferences.Reference reference : entry.getValue().references()) {
                String rawTarget = reference.rawTarget();
                if (rawTarget == null || rawTarget.isBlank() || ResourceReferences.isExternal(rawTarget)) {
                    continue;
                }
                String trimmed = rawTarget.trim();

                // E03：越出容器根目录
                if (ResourceReferences.escapesContainer(baseDir, trimmed)) {
                    issues.add(new ValidationIssue(IssueKind.REFERENCE_TARGET_OUTSIDE,
                            source.fileName() + " 中的引用 " + trimmed + " 越出了容器根目录",
                            source.href(),
                            "context=" + reference.context() + ", target=" + trimmed));
                    continue;
                }

                Resource target;
                String fragment;
                if (trimmed.startsWith("#")) {
                    // 同文档锚点
                    target = source;
                    fragment = trimmed.substring(1);
                } else {
                    String resolved = ResourceReferences.resolveTarget(baseDir, trimmed);
                    if (resolved == null || resolved.isBlank()) {
                        continue;
                    }
                    int hash = resolved.indexOf('#');
                    String path = hash < 0 ? resolved : resolved.substring(0, hash);
                    fragment = hash < 0 ? "" : resolved.substring(hash + 1);

                    ResourceReferences.Lookup lookup = ResourceReferences.findResource(book.resources(), path);
                    target = lookup.resource();
                    if (target == null) {
                        // E02
                        issues.add(new ValidationIssue(IssueKind.REFERENCE_TARGET_MISSING,
                                source.fileName() + " 中的引用 " + trimmed + " 指向的资源不存在",
                                source.href(),
                                "context=" + reference.context() + ", resolved=" + resolved));
                        continue;
                    }
                }

                // E04
                if (fragment != null && !fragment.isBlank() && isMarkup(target)) {
                    String key = target.href() == null ? "" : target.href();
                    Set<String> ids = fragmentCache.computeIfAbsent(key,
                            ignored -> ResourceReferences.fragmentIds(target));
                    if (!ids.isEmpty() && !ids.contains(fragment)) {
                        issues.add(new ValidationIssue(IssueKind.FRAGMENT_MISSING,
                                source.fileName() + " 中的引用 " + trimmed + " 指向的锚点 #" + fragment + " 不存在",
                                source.href(),
                                "context=" + reference.context() + ", fragment=" + fragment));
                    }
                }
            }
        }
    }

    /** E05：复用 Book 的孤儿判定，保证与「清理未引用资源」口径一致。 */
    private static void checkOrphans(Book book, List<ValidationIssue> issues) {
        for (Resource orphan : book.unreferencedResources()) {
            issues.add(new ValidationIssue(IssueKind.RESOURCE_ORPHAN,
                    orphan.fileName() + " 没有被任何地方引用，可以安全清理",
                    orphan.href(),
                    "mediaType=" + orphan.mediaType()));
        }
    }

    /** XHTML / SVG / CSS 中可能携带 URI 引用，其余类型（图片、字体等）无需扫描。 */
    private static boolean isScannable(Resource resource) {
        String mediaType = resource.mediaType();
        return MediaTypes.XHTML.equals(mediaType)
                || MediaTypes.SVG.equals(mediaType)
                || MediaTypes.CSS.equals(mediaType);
    }

    private static boolean isMarkup(Resource resource) {
        String mediaType = resource.mediaType();
        return MediaTypes.XHTML.equals(mediaType) || MediaTypes.SVG.equals(mediaType);
    }
}
