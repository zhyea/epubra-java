package org.chobit.epubra.app.ui.model;

import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;

/**
 * 目录树上的一个节点：关联目录条目与它指向的资源。
 */
public record ChapterNode(String title, Resource resource, TOCReference reference) {

    /** 界面展示标题：目录标题为空时回退为文件名。 */
    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return resource == null ? "无标题" : resource.fileName();
    }
}
