package org.chobit.epubra.lib.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录树：保存顶层节点，实际层级由 {@link TOCReference#children()} 递归构成。
 */
public class TableOfContents {

    private final List<TOCReference> roots = new ArrayList<>();

    public List<TOCReference> roots() {
        return roots;
    }

    public void add(TOCReference reference) {
        roots.add(reference);
    }

    public void add(String title, String href) {
        roots.add(new TOCReference(title, href));
    }

    /** 深度优先展开为扁平列表，便于界面直接展示。 */
    public List<TOCReference> flatten() {
        List<TOCReference> all = new ArrayList<>();
        collect(roots, all);
        return all;
    }

    private static void collect(List<TOCReference> nodes, List<TOCReference> sink) {
        for (TOCReference node : nodes) {
            sink.add(node);
            collect(node.children(), sink);
        }
    }

    public int size() {
        return flatten().size();
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    public void clear() {
        roots.clear();
    }
}
