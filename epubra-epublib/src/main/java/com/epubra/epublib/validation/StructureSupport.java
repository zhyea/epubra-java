package com.epubra.epublib.validation;

import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.TOCReference;
import com.epubra.epublib.util.Hrefs;
import com.epubra.epublib.util.ResourceReferences;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * StructureRules 各分块共用的内部工具：null 展示、精确 nav 属性判定、链接片段裁剪等。
 *
 * <p>不对外暴露，仅在 {@code StructureRules} 与各分块（OpfSpineRules / NavigationRules /
 * MetadataRules）之间共享。
 */
final class StructureSupport {

    /** BCP 47 语言代码粗校验。 */
    static final Pattern LANGUAGE = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
    static final String MODIFIED_PROPERTY = "dcterms:modified";

    private StructureSupport() {
    }

    static String nullSafe(String value) {
        return value == null ? "（空）" : value;
    }

    static String displayTitle(TOCReference node) {
        String title = node.title();
        return title == null || title.isBlank() ? "（无标题）" : title.trim();
    }

    static String stripFragment(String path) {
        if (path == null) {
            return "";
        }
        int hash = path.indexOf('#');
        return hash < 0 ? path : path.substring(0, hash);
    }

    static List<String> dedupe(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    static Resource resolveTocResource(com.epubra.epublib.domain.Book book, TOCReference node) {
        String resolved = Hrefs.resolve(book.contentDirectory(), node.resourceHref());
        return ResourceReferences.findResource(book.resources(), resolved).resource();
    }

    /** 按 href 缓存文档内 id，一本书只解析一次。 */
    static Set<String> fragmentIds(Resource resource, Map<String, Set<String>> cache) {
        String href = resource.href();
        if (href != null && cache.containsKey(href)) {
            return cache.get(href);
        }
        Set<String> ids = ResourceReferences.fragmentIds(resource);
        if (href != null) {
            cache.put(href, ids);
        }
        return ids;
    }

    /**
     * nav 属性判定：按空白拆分后全等比较。
     *
     * <p>不复用 {@link Resource#isNavDocument()}——它是子串匹配，会把
     * {@code properties="navigation"} 误判为 nav。
     */
    static boolean isNavDocument(Resource resource) {
        return hasNavProperty(resource.properties());
    }

    static boolean hasNavProperty(String properties) {
        if (properties == null || properties.isBlank()) {
            return false;
        }
        return Arrays.asList(properties.trim().split("\\s+")).contains("nav");
    }

    /** 按精确 nav 属性判定收集的导航文档。 */
    static List<Resource> navResources(com.epubra.epublib.domain.Book book) {
        List<Resource> navs = new ArrayList<>();
        if (book == null) {
            return navs;
        }
        for (Resource resource : book.resources().allList()) {
            if (hasNavProperty(resource.properties())) {
                navs.add(resource);
            }
        }
        return navs;
    }

    /** 从 nav 文档根元素里挑出 {@code <nav epub:type="toc">}，无则回退首个 nav。 */
    static Element pickTocNav(Element root) {
        List<Element> navs = com.epubra.epublib.util.Xmls.descendants(root, "nav");
        for (Element nav : navs) {
            if (com.epubra.epublib.util.Xmls.hasEpubType(nav, "toc")) {
                return nav;
            }
        }
        return navs.isEmpty() ? null : navs.get(0);
    }
}
