package org.chobit.epubra.app.support.document;

import org.chobit.epubra.app.ui.support.context.BookContext;
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
 *   <li><b>主文件已保存</b> → 调 {@link #discardFor(BookContext)} 删除草稿。</li>
 *   <li><b>未保存的新书</b> → 草稿放 {@link BookContext#autosaveDir()}，文件名
 *       用 {@code untitled.draft}（避免与已存在的主文件草稿冲突）。</li>
 *   <li><b>IO 失败静默</b>：草稿是"尽力而为"保护，不应让主流程崩；写盘/扫描失败仅记
 *       {@link System.Logger}。</li>
 * </ul>
 *
 * <h2>典型调用链</h2>
 * <pre>
 *   MainController.initialize()                  → 启动恢复扫描 findRecoverable
 *   ContentAreaListener (textProperty)            → scheduleDebounce(5s)
 *   DocumentActivity.onSave / onSaveAs 成功      → discardFor + unmarkDraft
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
     * 解析草稿文件路径：主文件存在 → 与主文件同目录、文件名加 {@value #DRAFT_SUFFIX}；
     * 主文件为 null → 放 {@link BookContext#autosaveDir()} 的 {@value #UNTITLED_DRAFT_NAME}。
     *
     * <p>主文件名若以 {@code .epub} 结尾，会替换后缀；否则直接追加。
     */
    public static Path draftPathFor(BookContext ctx) {
        Path mainFile = ctx.currentFile();
        if (mainFile != null) {
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

    /** 主文件保存成功后调用：删除对应草稿，并清除当前 Book 上的草稿 metadata。 */
    public static void discardFor(BookContext ctx) {
        if (ctx.currentFile() == null) {
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
     * 启动恢复扫描：若有可恢复的草稿返回路径，否则空。
     *
     * <p>优先级：
     * <ol>
     *   <li>主文件存在 → 查同目录同名 {@value #DRAFT_SUFFIX}（精确匹配，效率最高）。</li>
     *   <li>主文件为 null → 扫 {@link BookContext#autosaveDir()} 下所有 .draft，
     *       按最后修改时间返回最新的一个。</li>
     * </ol>
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
                    .filter(p -> p.getFileName().toString().endsWith(DRAFT_SUFFIX))
                    .max(Comparator.comparing(Autosave::lastModifiedOrEpoch));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Autosave dir scan failed: " + e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static Instant lastModifiedOrEpoch(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
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
