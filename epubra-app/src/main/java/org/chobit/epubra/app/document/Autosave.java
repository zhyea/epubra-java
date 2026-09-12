package org.chobit.epubra.app.document;

import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.platform.RelativeTime;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Metadata;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 自动暂存（草稿）工具类。
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li><b>双标识</b>：文件后缀 {@value #DRAFT_SUFFIX} 与 EPUB 包内
 *       {@code <meta property="dcterms:status">draft</meta>}（{@link EpubWriter}
 *       第 193–199 行已自动序列化 {@link Metadata#properties()}，内核 0 改动）。</li>
 *   <li><b>文件内容</b>：仍是合法 EPUB zip 字节流——用户把 {@code .draft} 重命名为
 *       {@code .epub} 即可作为正式版发布，这是有意的"草稿即 EPUB 快照"语义。</li>
 *   <li><b>⚠ 工作空间里的 {@code .draft} 是主文档本体</b>：它由
 *       {@code DocumentActivity.newDraft / openDraft} 创建并作为 {@code ctx.currentFile()}
 *       长期持有，<b>不是</b>可随手删除的临时副本。因此
 *       {@link #draftPathFor(BookContext)} 对它<b>原样返回</b>（绝不叠加第二个后缀，
 *       否则会写出 {@code 三体.draft.draft} 影子文件），{@link #discardFor(BookContext)}
 *       对它<b>只清标记、不删文件</b>——切换文档 / 工作空间时删掉它等于删掉用户的整本书。</li>
 *   <li><b>未归属工作空间的孤儿草稿</b> → 放 {@link BookContext#autosaveDir()}，文件名
 *       用 {@code untitled.draft}。这是<b>异常态</b>（"有图书草稿就该有工作空间"），
 *       启动时由 {@link #findRecoverable(BookContext)} 扫出来，经
 *       {@link #writeIntoWorkspace(Book, Path, String)} 收编进工作空间。</li>
 *   <li><b>IO 失败静默</b>：草稿是"尽力而为"保护，不应让主流程崩；写盘/扫描失败仅记
 *       {@link System.Logger}。</li>
 * </ul>
 *
 * <h2>典型调用链</h2>
 * <pre>
 *   MainController.initialize()                  → 启动恢复扫描 findRecoverable
 *   ContentAreaListener (textProperty)            → scheduleDebounce(5s) → flushNow
 *   MainController.promptRecoveryIfAny()          → writeIntoWorkspace（孤儿收编）
 * </pre>
 */
public final class Autosave {

    /** 草稿文件后缀。命名集中在一处，便于将来统一改名。 */
    public static final String DRAFT_SUFFIX = ".draft";

    /** EPUB 3 标准属性：dcterms:status=draft。 */
    public static final String STATUS_PROPERTY = "dcterms:status";

    /** 草稿状态的标准值。 */
    public static final String STATUS_DRAFT = "draft";

    /** 项目私有属性：自动暂存时间戳（ISO 8601）。 */
    public static final String AUTOSAVED_AT_PROPERTY = "epubra:autosaved-at";

    /** 未保存新书的草稿文件名——避免与已存在的主文件草稿冲突。 */
    public static final String UNTITLED_DRAFT_NAME = "untitled" + DRAFT_SUFFIX;

    private static final System.Logger LOG = System.getLogger(Autosave.class.getName());
    private static final EpubWriter WRITER = new EpubWriter();
    private static final EpubReader READER = new EpubReader();

    private Autosave() {
    }

    // ---- metadata 标记 ----

    /** 给 Book 写入草稿 metadata 标记（status=draft + autosaved-at=ISO8601）。 */
    public static void markDraft(Book book) {
        if (book == null) {
            return;
        }
        Metadata md = book.metadata();
        md.setProperty(STATUS_PROPERTY, STATUS_DRAFT);
        md.setProperty(AUTOSAVED_AT_PROPERTY, Instant.now().toString());
    }

    /** 清除草稿 metadata 标记。主文件保存成功后调用，让 .epub 不带 draft 痕迹。 */
    public static void unmarkDraft(Book book) {
        if (book == null) {
            return;
        }
        Metadata md = book.metadata();
        md.properties().remove(STATUS_PROPERTY);
        md.properties().remove(AUTOSAVED_AT_PROPERTY);
    }

    /** Book 是否被标记为草稿。用于恢复时的二次校验（防误把普通 EPUB 当草稿恢复）。 */
    public static boolean isMarkedDraft(Book book) {
        if (book == null) {
            return false;
        }
        return STATUS_DRAFT.equals(book.metadata().property(STATUS_PROPERTY));
    }

    // ---- 路径解析 ----

    /**
     * 去掉文件名的 {@value #DRAFT_SUFFIX} 后缀；不带该后缀时原样返回。
     *
     * <p>后缀处理集中在这一处——{@link DraftDocument#stem()}、宫格标题、导入时推导
     * 新文档名都要用，散落各处硬编码 {@code ".draft"} 会在改后缀时漏改。
     *
     * @param fileName 文件名（不含目录）；为 null 时返回空串
     */
    public static String stripDraftSuffix(String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.endsWith(DRAFT_SUFFIX)
                ? fileName.substring(0, fileName.length() - DRAFT_SUFFIX.length())
                : fileName;
    }

    /**
     * 该路径是否<b>本身就是一个草稿文件</b>——即工作空间里的处理中文档。
     *
     * <p>用于区分两种 {@code currentFile}：
     * <ul>
     *   <li>工作空间主文档 {@code <ws>/三体.draft} → 它就是草稿，写盘/清理都作用于本体；</li>
     *   <li>外部文件 {@code <dir>/三体.epub} → 草稿是派生的副本 {@code <dir>/三体.draft}。</li>
     * </ul>
     */
    public static boolean isDraftFile(Path file) {
        return file != null
                && file.getFileName() != null
                && file.getFileName().toString().endsWith(DRAFT_SUFFIX);
    }

    /**
     * 解析草稿文件路径。
     *
     * <p>三种情形：
     * <ol>
     *   <li>{@code currentFile} 为空 → {@link BookContext#autosaveDir()} 下的
     *       {@value #UNTITLED_DRAFT_NAME}（孤儿草稿）；</li>
     *   <li>{@code currentFile} 已是 {@value #DRAFT_SUFFIX} → <b>原样返回</b>，
     *       它本身就是主文档（见 {@link #isDraftFile(Path)}）；</li>
     *   <li>其它 → 同目录派生：{@code .epub} 换后缀，其余追加后缀。</li>
     * </ol>
     *
     * <p><b>情形 2 是硬约束</b>：对 {@code 三体.draft} 再追加后缀会得到
     * {@code 三体.draft.draft}，而 {@code WorkspaceScanner} 用 {@code endsWith(".draft")}
     * 过滤 → 影子文件同样命中宫格，表现为一本图书出现两张卡片（且自动暂存的内容写进了
     * 那张没人认领的卡片里）。
     */
    public static Path draftPathFor(BookContext ctx) {
        Path mainFile = ctx.currentFile();
        if (mainFile != null) {
            if (isDraftFile(mainFile)) {
                return mainFile;
            }
            String fileName = mainFile.getFileName().toString();
            String draftName = fileName.endsWith(".epub")
                    ? fileName.substring(0, fileName.length() - ".epub".length()) + DRAFT_SUFFIX
                    : fileName + DRAFT_SUFFIX;
            return mainFile.getParent().resolve(draftName);
        }
        return ctx.autosaveDir().resolve(UNTITLED_DRAFT_NAME);
    }

    // ---- 写盘 ----

    /**
     * 立即把当前 Book 写到草稿文件。
     *
     * <p>写盘前会先调 {@link #markDraft(Book)} 把 metadata 写好——单次写盘兼顾文件层与
     * 包层双标识。失败仅记日志，不抛。
     *
     * <p>目标路径由 {@link #draftPathFor(BookContext)} 决定：工作空间里编辑的
     * {@code .draft} 会被<b>原地覆盖</b>（"编辑即写它"），这正是自动暂存能真正保住
     * 用户改动的原因——旧实现对 {@code 三体.draft} 又追加了一层后缀，把内容写进了
     * 一个谁也读不到的影子文件。{@link EpubWriter#write(Book, Path)} 内部走
     * "临时文件 + 原子移动"，原地覆盖不会写出半截 zip。
     */
    public static void flushNow(BookContext ctx) {
        Book book = ctx.book();
        if (book == null) {
            return;
        }
        Path target;
        try {
            Files.createDirectories(ctx.autosaveDir());
            target = draftPathFor(ctx);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Autosave path setup failed: " + e.getMessage(), e);
            return;
        }
        try {
            markDraft(book);
            WRITER.write(book, target);
            LOG.log(System.Logger.Level.DEBUG, "Autosaved to " + target);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Autosave write failed: " + e.getMessage(), e);
        }
    }

    /**
     * 主文件保存成功后调用：删除对应草稿，并清除当前 Book 上的草稿 metadata。
     *
     * <p><b>⚠ {@code currentFile} 本身是 {@code .draft} 时本方法是 no-op</b>：工作空间里
     * 的 {@code .draft} 是主文档本体，调用方（新建 / 打开其它文档 / 切换工作空间）的语义
     * 是"放弃当前这本书"，而不是"从磁盘上抹掉这本书"。删掉它没有任何副本可恢复。
     */
    public static void discardFor(BookContext ctx) {
        Path mainFile = ctx.currentFile();
        if (mainFile == null) {
            return;
        }
        if (isDraftFile(mainFile)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "discardFor skipped：主文档即草稿，不能删除 " + mainFile);
            return;
        }
        Path target;
        try {
            target = draftPathFor(ctx);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "discardFor path resolve skipped: " + e.getMessage());
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Discard autosave failed: " + e.getMessage(), e);
        }
        unmarkDraft(ctx.book());
    }

    // ---- 扫描 ----

    /**
     * 启动恢复扫描：若有可恢复的<b>孤儿草稿</b>返回路径，否则空。
     *
     * <p>优先级：
     * <ol>
     *   <li>主文件存在（含"主文件即 {@code .draft}"）→ 草稿就是它自己 / 它同目录的派生副本，
     *       存在即返回。</li>
     *   <li>主文件为 null（启动瞬间的常态）→ 扫 {@link BookContext#autosaveDir()} 下所有
     *       {@code .draft}，返回最后修改时间最新的一个；mtime 相同时按文件名升序兜底，
     *       避免 {@link Files#list} 次序不稳导致"每次启动提示的草稿不一样"。</li>
     * </ol>
     *
     * <p>情形 2 命中说明存在<b>未归属任何工作空间</b>的草稿（正常流程里 {@code onNew}
     * 必须先选定工作空间并把内容落成 {@code <ws>/<书名>.draft}，所以这是异常态）。
     * 调用方应把结果收编进工作空间（{@link #writeIntoWorkspace(Book, Path, String)}），
     * 恢复提示里也要写明工作空间——"有图书草稿就该有工作空间"。
     */
    public static Optional<Path> findRecoverable(BookContext ctx) {
        Path mainFile = ctx.currentFile();
        if (mainFile != null) {
            try {
                Path candidate = draftPathFor(ctx);
                if (Files.exists(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "Recoverable lookup skipped: " + e.getMessage());
            }
            return Optional.empty();
        }
        Path dir = ctx.autosaveDir();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName() != null)
                    .filter(p -> p.getFileName().toString().endsWith(DRAFT_SUFFIX))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparing(Autosave::lastModifiedOrEpoch)
                            .thenComparing(p -> p.getFileName().toString()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Autosave dir scan failed: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static Instant lastModifiedOrEpoch(Path p) {
        if (p == null) {
            return Instant.EPOCH;
        }
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    // ---- 收编孤儿草稿到工作空间 ----

    /**
     * 在工作空间里挑一个不冲突的草稿文件名：{@code <name>.draft}，
     * 已被占用时依次尝试 {@code <name> 2.draft}、{@code <name> 3.draft}……
     *
     * <p>文件名里的非法字符（{@code \ / : * ? " < > |}）会替换为下划线——
     * 书名直接来自 EPUB metadata，用户能在书名里输入任意字符。
     */
    public static Path uniqueDraftPath(Path workspace, String baseName) {
        String safe = sanitizeFileName(baseName);
        Path candidate = workspace.resolve(safe + DRAFT_SUFFIX);
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = workspace.resolve(safe + " " + index + DRAFT_SUFFIX);
            index++;
        }
        return candidate;
    }

    /**
     * 把一本书写进工作空间，作为该工作空间里的图书草稿——孤儿草稿收编、以及将来自
     * "从其它位置另存为草稿"都走这里。
     *
     * <p>写盘前 {@link #markDraft(Book)}，与 {@link #flushNow(BookContext)} 保持同一套
     * 双标识语义；目标名由 {@link #uniqueDraftPath(Path, String)} 保证不与既有图书冲突。
     *
     * @param book      要写入的书（会被就地标记为草稿）
     * @param workspace 目标工作空间目录
     * @param baseName  文件名主干（通常取书名；空则退化为 {@value #UNTITLED_DRAFT_NAME} 的主干）
     * @return 实际写入的草稿路径
     * @throws IOException 目录不可写 / 序列化失败
     */
    public static Path writeIntoWorkspace(Book book, Path workspace, String baseName) throws IOException {
        if (book == null || workspace == null) {
            throw new IllegalArgumentException("book / workspace is null");
        }
        Files.createDirectories(workspace);
        String stem = baseName == null || baseName.isBlank()
                ? stripDraftSuffix(UNTITLED_DRAFT_NAME)
                : baseName.trim();
        Path target = uniqueDraftPath(workspace, stem);
        markDraft(book);
        WRITER.write(book, target);
        LOG.log(System.Logger.Level.DEBUG, "Adopted draft into workspace: " + target);
        return target;
    }

    /** 文件名净化：替换文件系统保留字符并去掉首尾空白/点号，空串时给个兜底名。 */
    private static String sanitizeFileName(String name) {
        String safe = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        safe = safe.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        return safe.isEmpty() ? "未命名图书" : safe;
    }

    // ---- 恢复提示文案（集中在一处，便于测试与统一措辞）----

    /**
     * 恢复提示的标题：必须让用户一眼看出草稿<b>归属哪个工作空间</b>，或明确指出
     * 它还没有归属——"有图书草稿就该有工作空间"。无工作空间时提示用户先选一个。
     */
    public static String recoveryPromptHeader(Path workspace) {
        if (workspace == null) {
            return "发现未归属工作空间的草稿";
        }
        return "恢复工作空间「" + displayName(workspace) + "」中的草稿";
    }

    /**
     * 恢复提示的正文：草稿名 + 最后修改时间 + 草稿位置 + 目标工作空间（名称与完整路径）。
     *
     * @param draftFile 孤儿草稿文件
     * @param workspace 将收编进的工作空间；为 null 时给出"先选工作空间"的引导
     * @param now       当前时间（注入以便测试固定时钟）
     */
    public static String recoveryPromptText(Path draftFile, Path workspace, Instant now) {
        String name = draftFile == null || draftFile.getFileName() == null
                ? ""
                : stripDraftSuffix(draftFile.getFileName().toString());
        if (name.isBlank()) {
            name = draftFile == null ? "" : String.valueOf(draftFile.getFileName());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("草稿：").append(name)
                .append("（最后修改：").append(RelativeTime.format(lastModifiedOrEpoch(draftFile), now))
                .append("）\n");
        sb.append("草稿位置：").append(draftFile == null ? "" : draftFile.toAbsolutePath()).append('\n');
        if (workspace == null) {
            sb.append("尚未选择工作空间，草稿无法归属。\n")
                    .append("请选择一个工作空间，草稿会作为该工作空间中的图书恢复。");
        } else {
            sb.append("将恢复到工作空间：").append(displayName(workspace)).append('\n');
            sb.append("完整路径：").append(workspace.toAbsolutePath());
        }
        return sb.toString();
    }

    private static String displayName(Path path) {
        if (path == null) {
            return "";
        }
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    // ---- 读草稿 ----

    /**
     * 读草稿文件 → Book。恢复后会自动 {@link #unmarkDraft(Book)}（内存里清掉标记）。
     *
     * <p>调用方负责把读回的 Book 替换到 {@code ctx} 并把 dirty 设为 true。
     *
     * @throws IOException 文件不存在 / 不是合法 EPUB / IO 错误
     */
    public static Book readDraft(Path draftFile) throws IOException {
        Book book = READER.read(draftFile);
        unmarkDraft(book);
        return book;
    }
}
