package com.epubra.epublib.validation;

import com.epubra.epublib.domain.Book;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 校验门面：装配三组规则 → 合并 → 排序 → 出报告。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #validate(Book)}：纯内存校验，无需磁盘文件。</li>
 *   <li>{@link #validate(Book, Path)}：追加容器级规则（mimetype / container.xml / 原始 OPF）。
 *       容器读取失败时记 A09 并降级为纯内存校验。</li>
 * </ul>
 *
 * <p>容器级规则一律读磁盘上的真实文件——若先把内存 {@code Book} 序列化到临时文件再校验，
 * mimetype 永远是我们自己写的、必然通过，就失去了「发现外来 EPUB 不合规」的价值。
 *
 * <p>校验是纯只读操作，不修改任何资源内容，也不产生撤销 / 重做记录。
 */
public class EpubValidator {

    private static final System.Logger LOG = System.getLogger(EpubValidator.class.getName());

    /** 纯内存校验：跳过全部容器级规则。 */
    public ValidationReport validate(Book book) {
        return validate(book, null);
    }

    /**
     * @param book          待校验的书籍
     * @param containerFile 磁盘上的 EPUB 文件；为 {@code null} 或不存在时自动降级为内存校验
     */
    public ValidationReport validate(Book book, Path containerFile) {
        if (book == null) {
            return ValidationReport.EMPTY;
        }
        List<ValidationIssue> issues = new ArrayList<>();
        boolean containerChecked = false;

        if (containerFile != null && Files.isRegularFile(containerFile)) {
            try {
                ContainerFacts facts = ContainerFacts.of(containerFile);
                issues.addAll(ContainerRules.check(facts, book));
                containerChecked = true;
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "容器级校验无法进行：" + containerFile + "，" + e.getMessage());
                issues.add(new ValidationIssue(IssueKind.CONTAINER_UNREADABLE,
                        "无法读取 " + containerFile.getFileName() + "，容器级规则已跳过：" + e.getMessage(),
                        null,
                        "file=" + containerFile));
            }
        }

        issues.addAll(StructureRules.check(book, containerChecked));
        issues.addAll(ReferenceRules.check(book));
        return new ValidationReport(issues, containerChecked);
    }
}
