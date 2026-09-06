package org.chobit.epubra.app.support;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 工作空间里的一份「处理中文档」——一个 {@code *.draft} 文件的轻量视图。
 *
 * <h2>为什么是轻量 record</h2>
 * <p>宫格可能要渲染几十上百张卡片。若扫描时就把每个 `.draft` 当 EPUB zip 解开读
 * metadata 与封面，扫一次就是 O(N × 解包) —— 用户切换工作空间时会明显卡顿。
 * 所以本 record 只保存<b>扫目录就能拿到</b>的三样东西：路径、展示标题、修改时间；
 * 封面缩略图由 UI 层按需异步加载（复用 {@link AsyncTasks}）。
 *
 * <h2>标题来源</h2>
 * <p>{@link #displayTitle()} 由<b>文件名</b>推导（去 {@link Autosave#DRAFT_SUFFIX}
 * 后缀），不读 EPUB 包内的 metadata 标题。理由同上——避免扫描期解包。
 * 文件名与 metadata 标题不一致的情况（用户改名但没改 metadata）在 P1 阶段可接受；
 * 若将来要显示真实标题，加一个异步的 {@code resolveRealTitle()} 即可。
 *
 * <h2>排序</h2>
 * <p>见 {@link WorkspaceScanner}——按 {@link #modifiedAt()} 降序（最近改过的在前），
 * mtime 相同时用文件名升序兜底保证确定性。
 *
 * @param path         `.draft` 文件的完整路径
 * @param displayTitle 宫格卡片上显示的标题（由文件名推导）
 * @param modifiedAt   最后修改时间；读不到时为 {@link Instant#EPOCH}（排到最末）
 */
public record DraftDocument(Path path, String displayTitle, Instant modifiedAt) {

    /** 文件名去掉 {@code .draft} 后缀后的部分——即展示标题。 */
    public String stem() {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return Autosave.stripDraftSuffix(name);
    }

    /**
     * 卡片副标题：人类可读的相对时间。
     *
     * <p>放这里是纯展示逻辑，但不依赖任何 JavaFX 控件——方便单元测试直接断言文案。
     *
     * @param now 当前时间（注入以便测试固定时钟）
     */
    public String relativeTimeText(Instant now) {
        return RelativeTime.format(modifiedAt, now);
    }

    /** 是否有可读的修改时间（{@link Instant#EPOCH} 视为"未知"）。 */
    public boolean hasKnownModifiedTime() {
        return modifiedAt != null && !Instant.EPOCH.equals(modifiedAt);
    }
}