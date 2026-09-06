package com.epubra.app.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 扫描工作空间目录里的 {@code *.draft} 文档。
 *
 * <h2>工作空间模型（2026-09-06 重构）</h2>
 * <pre>
 *   &lt;workspace&gt;/
 *     三体.draft
 *     球状闪电.draft
 *     朝闻道.draft
 * </pre>
 * <p>扁平结构——<b>没有子目录</b>，也不再需要 {@code .epubra/project.json} 之类的标记文件。
 * 任何目录都可以当工作空间：识别依据就是"里面有没有 {@code *.draft}"。
 *
 * <h2>排序：mtime 降序 + 文件名升序兜底</h2>
 * <p>主序按最后修改时间<b>降序</b>（最近改过的排最前）。
 *
 * <p><b>为什么必须有兜底比较器</b>：Windows NTFS 的 mtime 精度虽是 100ns，但批量导入 /
 * 文件复制产生的多个文件常落在同一时间戳。只按 mtime 排时，相等元素的先后取决于
 * {@code Files.list} 的返回次序（文件系统相关、不保证稳定），表现为<b>每次刷新宫格
 * 卡片位置随机跳动</b>。加文件名升序作二级排序后顺序完全确定。
 *
 * <h2>性能</h2>
 * <p>只做 {@code Files.list} + {@code getLastModifiedTime}——<b>不解析 EPUB zip</b>。
 * 封面与真实标题由 UI 层按需异步加载，保证切换工作空间时宫格秒开。
 *
 * <h2>容错</h2>
 * <p>目录不存在 / 不是目录 / IO 失败 → 一律返回空列表（静默）。扫描是"尽力而为"的
 * 展示行为，不应因一个坏目录让整个工作空间打不开。
 */
public final class WorkspaceScanner {

    private WorkspaceScanner() {
    }

    /**
     * 排序器：mtime 降序，同值时按文件名升序。
     *
     * <p>对外暴露是为了让测试直接验证比较器语义，不必造真实文件系统场景。
     */
    public static final Comparator<DraftDocument> BY_RECENTLY_MODIFIED =
            Comparator.comparing(DraftDocument::modifiedAt, Comparator.reverseOrder())
                    .thenComparing(doc -> fileNameOf(doc.path()));

    /**
     * 扫描工作空间目录下的所有 {@code *.draft} 文档，按最近修改时间降序返回。
     *
     * @param workspaceDir 工作空间目录；为 null / 不存在 / 非目录时返回空列表
     * @return 不可变列表；始终非 null
     */
    public static List<DraftDocument> scan(Path workspaceDir) {
        if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
            return List.of();
        }
        List<DraftDocument> docs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(workspaceDir)) {
            stream.filter(p -> p.getFileName() != null)
                    .filter(p -> p.getFileName().toString().endsWith(Autosave.DRAFT_SUFFIX))
                    .filter(Files::isRegularFile)
                    .map(WorkspaceScanner::toDocument)
                    .forEach(docs::add);
        } catch (IOException e) {
            // 目录读不了就当作空工作空间——不向上抛，避免整个启动流程被一个坏目录打断
            System.getLogger(WorkspaceScanner.class.getName())
                    .log(System.Logger.Level.WARNING,
                            () -> "Workspace scan failed: " + workspaceDir + "，" + e.getMessage());
            return List.of();
        }
        docs.sort(BY_RECENTLY_MODIFIED);
        return List.copyOf(docs);
    }

    /**
     * 扫描并按「最近修改时间降序」限制条数——给「文件 → 最近的工作空间」预览之类
     * 只需要前 N 条的场景用，避免全量扫描后再截断。
     */
    public static List<DraftDocument> scanRecent(Path workspaceDir, int limit) {
        List<DraftDocument> all = scan(workspaceDir);
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return List.copyOf(all.subList(0, limit));
    }

    /** 工作空间里是否存在任何 {@code *.draft}——用于判断"这个目录像不像工作空间"。 */
    public static boolean hasAnyDraft(Path workspaceDir) {
        return !scan(workspaceDir).isEmpty();
    }

    /**
     * 把单个 {@code *.draft} 路径转成 {@link DraftDocument}。
     *
     * <p>读不到 mtime（权限 / 文件刚好被删）时用 {@link Instant#EPOCH}——排序器会把它
     * 排到最末，且 {@link DraftDocument#hasKnownModifiedTime()} 返回 false 供 UI 显示「未知」。
     */
    public static DraftDocument toDocument(Path draftFile) {
        String name = draftFile.getFileName() == null ? "" : draftFile.getFileName().toString();
        return new DraftDocument(draftFile, Autosave.stripDraftSuffix(name), lastModifiedOrEpoch(draftFile));
    }

    private static Instant lastModifiedOrEpoch(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** 文件名小写串——排序兜底用，统一小写避免 Windows 大小写不敏感导致的抖动。 */
    private static String fileNameOf(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        return n.toLowerCase();
    }
}