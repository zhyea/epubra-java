package com.epubra.epublib.validation;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.Resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 组（OPF / manifest 自身）、C 组（spine ↔ manifest）、D 组（Nav / NCX / TOC / 封面一致性）
 * 与 F 组（元数据）的门面：实际判定已下沉到 {@link OpfSpineRules} / {@link NavigationRules} /
 * {@link MetadataRules}，本类只负责调度与共享片段 ID 缓存。
 *
 * <p>全部为只读判定，不修改任何资源内容。
 */
public final class StructureRules {

    private StructureRules() {
    }

    /**
     * 纯内存校验入口：B07（资源内容为空）生效，B08（清单条目不在 ZIP）不生效。
     *
     * @see #check(Book, boolean)
     */
    public static List<ValidationIssue> check(Book book) {
        return check(book, false);
    }

    /**
     * @param containerMode {@code true} 表示同时能读到磁盘文件：此时 B08 由容器规则负责，B07 让位避免重复报告
     */
    public static List<ValidationIssue> check(Book book, boolean containerMode) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (book == null) {
            return issues;
        }
        Map<String, Set<String>> fragmentCache = new HashMap<>();
        OpfSpineRules.check(book, containerMode, issues);
        NavigationRules.check(book, containerMode, issues, fragmentCache);
        MetadataRules.check(book, issues);
        return issues;
    }

    /** 按精确 nav 属性判定收集的导航文档；不同于 {@link Book#navResource()} 的子串匹配。 */
    public static List<Resource> navResources(Book book) {
        return StructureSupport.navResources(book);
    }
}
