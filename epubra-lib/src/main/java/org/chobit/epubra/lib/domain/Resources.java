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

    /**
     * 加入一个资源，并<b>保持 href / id 两张索引一致</b>。
     *
     * <p>{@code put} 是静默覆盖：旧实现只覆盖命中的那一张表，另一张表里会留下一个
     * 指向「已经不在集合中」的对象的僵尸条目——
     * <ul>
     *   <li>同 href 换人（{@code A→r1} 再 {@code A→r2}）：{@code byId} 里旧 id 仍指向 r1；</li>
     *   <li>同 id 换人（{@code idX→r1} 再 {@code idX→r2}，href 不同）：{@code byHref} 里
     *       旧 href 仍指向 r1。</li>
     * </ul>
     * 之后 {@code getById(id)} 拿到的资源不在 {@link #all()} 里，遍历 {@code all()} 再按 id
     * 反查就会错位（资源面板 / 校验 / 清理都走这条路径）。
     *
     * <p>修法：覆盖时把对方索引里的旧条目一并摘掉。用两参 {@code remove(key, value)}，
     * 只在「该键确实映射到这个旧对象」时才删，不会误伤同一键上的后来者。
     * 重复加入同一个对象是幂等的（两条替换路径都会因 {@code replaced == resource} 跳过）。
     */
    public void add(Resource resource) {
        Resource replacedByHref = byHref.put(normalize(resource.href()), resource);
        if (replacedByHref != null && replacedByHref != resource && replacedByHref.id() != null) {
            byId.remove(replacedByHref.id(), replacedByHref);
        }
        if (resource.id() != null) {
            Resource replacedById = byId.put(resource.id(), resource);
            if (replacedById != null && replacedById != resource) {
                byHref.remove(normalize(replacedById.href()), replacedById);
            }
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
