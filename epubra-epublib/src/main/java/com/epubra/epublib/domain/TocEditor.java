package com.epubra.epublib.domain;

import com.epubra.epublib.util.Hrefs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目录树的结构编辑：移动、升降级，以及把阅读顺序（spine）与目录保持同步。
 *
 * <p>所有操作都是纯粹的树结构变更，不触碰章节内容；
 * 每次成功变更后都会重排 spine，使「目录顺序即阅读顺序」。
 */
public final class TocEditor {

    /** 节点在树中的位置：父节点（顶层为 null）、所在兄弟列表、在列表中的下标。 */
    public record Location(TOCReference parent, List<TOCReference> siblings, int index) {

        public boolean isRootLevel() {
            return parent == null;
        }
    }

    private TocEditor() {
    }

    /** 定位节点所在位置，节点不在树中时返回 null。 */
    public static Location locate(Book book, TOCReference node) {
        if (book == null || node == null) {
            return null;
        }
        List<TOCReference> roots = book.toc().roots();
        int index = roots.indexOf(node);
        if (index >= 0) {
            return new Location(null, roots, index);
        }
        return locateIn(roots, node);
    }

    private static Location locateIn(List<TOCReference> nodes, TOCReference target) {
        for (TOCReference node : nodes) {
            int index = node.children().indexOf(target);
            if (index >= 0) {
                return new Location(node, node.children(), index);
            }
            Location deeper = locateIn(node.children(), target);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /** ancestor 是否为 node 的祖先（或就是 node 本身）。 */
    public static boolean isAncestorOrSelf(TOCReference ancestor, TOCReference node) {
        if (ancestor == null || node == null) {
            return false;
        }
        if (ancestor == node) {
            return true;
        }
        for (TOCReference child : ancestor.children()) {
            if (isAncestorOrSelf(child, node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 node 移动到 targetParent 的指定下标处；targetParent 为 null 表示顶层。
     *
     * @return 是否发生了移动；目标非法（移入自身子孙或自身）时返回 false
     */
    public static boolean moveTo(Book book, TOCReference node, TOCReference targetParent, int index) {
        if (node == null || node == targetParent) {
            return false;
        }
        if (targetParent != null && isAncestorOrSelf(node, targetParent)) {
            return false;
        }
        Location source = locate(book, node);
        if (source == null) {
            return false;
        }
        List<TOCReference> targetSiblings = targetParent == null
                ? book.toc().roots()
                : targetParent.children();

        source.siblings().remove(node);
        int insertAt = index;
        // 同层向后移动时，移除自身会让目标下标前移一位
        if (source.siblings() == targetSiblings && source.index() < index) {
            insertAt--;
        }
        targetSiblings.add(Math.max(0, Math.min(insertAt, targetSiblings.size())), node);
        syncSpineFromToc(book);
        return true;
    }

    /** 移动到 target 之前（同层）。 */
    public static boolean moveBefore(Book book, TOCReference node, TOCReference target) {
        Location targetLocation = locate(book, target);
        if (targetLocation == null) {
            return false;
        }
        return moveTo(book, node, targetLocation.parent(), targetLocation.index());
    }

    /** 移动到 target 之后（同层）。 */
    public static boolean moveAfter(Book book, TOCReference node, TOCReference target) {
        Location targetLocation = locate(book, target);
        if (targetLocation == null) {
            return false;
        }
        return moveTo(book, node, targetLocation.parent(), targetLocation.index() + 1);
    }

    /** 降级：成为前一个兄弟节点的最后一个子节点。 */
    public static boolean indent(Book book, TOCReference node) {
        Location location = locate(book, node);
        if (location == null || location.index() == 0) {
            return false;
        }
        TOCReference previous = location.siblings().get(location.index() - 1);
        return moveTo(book, node, previous, previous.children().size());
    }

    /** 升级：成为父节点的下一个兄弟；已处于顶层时返回 false。 */
    public static boolean outdent(Book book, TOCReference node) {
        Location location = locate(book, node);
        if (location == null || location.parent() == null) {
            return false;
        }
        TOCReference parent = location.parent();
        Location parentLocation = locate(book, parent);
        if (parentLocation == null) {
            return false;
        }
        location.siblings().remove(node);
        int insertAt = Math.min(parentLocation.index() + 1, parentLocation.siblings().size());
        parentLocation.siblings().add(insertAt, node);
        syncSpineFromToc(book);
        return true;
    }

    /** 提升为顶层节点的最后一个。 */
    public static boolean moveToRoot(Book book, TOCReference node) {
        return moveTo(book, node, null, book.toc().roots().size());
    }

    /**
     * 按目录的深度优先顺序重建阅读顺序。
     * spine 中存在但目录未覆盖的资源会保留在末尾，避免章节丢失。
     */
    public static void syncSpineFromToc(Book book) {
        Set<String> ordered = new LinkedHashSet<>();
        collectResourceIds(book, book.toc().roots(), ordered);
        for (SpineReference reference : book.spine().references()) {
            ordered.add(reference.resourceId());
        }
        String tocId = book.spine().tocResourceId();
        book.spine().clear();
        ordered.forEach(book.spine()::addResourceId);
        book.spine().setTocResourceId(tocId);
    }

    private static void collectResourceIds(Book book, List<TOCReference> nodes, Set<String> ids) {
        for (TOCReference node : nodes) {
            String href = Hrefs.resolve(book.contentDirectory(), node.resourceHref());
            Resource resource = book.resources().getByHref(href);
            if (resource != null) {
                ids.add(resource.id());
            }
            collectResourceIds(book, node.children(), ids);
        }
    }
}
