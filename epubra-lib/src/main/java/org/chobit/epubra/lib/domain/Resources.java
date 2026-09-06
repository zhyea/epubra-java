package org.chobit.epubra.lib.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书籍的全部资源集合，按 href 与 id 双索引，保持 manifest 中的出现顺序。
 */
public class Resources {

    private final Map<String, Resource> byHref = new LinkedHashMap<>();
    private final Map<String, Resource> byId = new LinkedHashMap<>();

    public void add(Resource resource) {
        byHref.put(normalize(resource.href()), resource);
        if (resource.id() != null) {
            byId.put(resource.id(), resource);
        }
    }

    public Resource getByHref(String href) {
        return byHref.get(normalize(href));
    }

    public Resource getById(String id) {
        return byId.get(id);
    }

    public boolean containsHref(String href) {
        return byHref.containsKey(normalize(href));
    }

    public Resource removeByHref(String href) {
        Resource removed = byHref.remove(normalize(href));
        if (removed != null && removed.id() != null) {
            byId.remove(removed.id());
        }
        return removed;
    }

    public Collection<Resource> all() {
        return byHref.values();
    }

    public List<Resource> allList() {
        return List.copyOf(byHref.values());
    }

    public int size() {
        return byHref.size();
    }

    /** 返回容器中不重复的 id，冲突时在基名后追加序号。 */
    public String uniqueId(String base) {
        String candidate = base;
        int index = 1;
        while (byId.containsKey(candidate)) {
            candidate = base + "-" + index++;
        }
        return candidate;
    }

    /** 返回容器中不重复的 href，冲突时在扩展名前追加序号。 */
    public String uniqueHref(String href) {
        if (!containsHref(href)) {
            return href;
        }
        int dot = href.lastIndexOf('.');
        String prefix = dot < 0 ? href : href.substring(0, dot);
        String suffix = dot < 0 ? "" : href.substring(dot);
        int index = 1;
        String candidate;
        do {
            candidate = prefix + "-" + index++ + suffix;
        } while (containsHref(candidate));
        return candidate;
    }

    private static String normalize(String href) {
        if (href == null) {
            return "";
        }
        return href.replace('\\', '/').trim();
    }
}
