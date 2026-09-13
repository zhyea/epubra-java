package org.chobit.epubra.app.editor;

import org.chobit.epubra.app.platform.AppPaths;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.ResourceReferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 把书内资源按容器结构镜像到磁盘，供预览 / 可视化编辑器的 {@code <base href>} 指向。
 *
 * <h2>为什么需要它</h2>
 * <p>预览与可视化编辑器都用 {@code WebEngine.loadContent(...)} 加载，页面源是
 * {@code about:blank}，<b>没有解析基准</b>：正文里 {@code <img src="../images/a.png"/>}
 * 这类相对引用一个都加载不出来（实测 {@code naturalWidth} 恒为 0）。
 *
 * <p>解决办法是把书内资源写到磁盘上的镜像目录，保持容器内相对结构不变，再给文档注入
 * {@code <base href="file:…/<章节目录>/">}，相对引用就能解析到真实文件。
 *
 * <h2>为什么不是「把 src 换成 data:」</h2>
 * <p>可视化编辑器的正文会被 {@code XMLSerializer} 序列化后<b>回写</b>到书里。改写
 * {@code src} 属性意味着回写时必须再把原始值换回去，任何一处漏掉都会把正文写成几百 KB 的
 * base64。{@code <base>} 只影响<b>解析</b>、不改 {@code src} 的属性值（属性值与 computed
 * src 是两回事），回写天然零影响。
 *
 * <h2>增量与生命周期</h2>
 * <ul>
 *   <li>只镜像<b>当前章节可达</b>的资源：以章节为起点，沿 XHTML / SVG / CSS 的引用做广度
 *       遍历；图片、字体等叶子资源只写不解析。</li>
 *   <li>按内容指纹跳过未变的文件，因此换章节、切主题的重复调用几乎不产生写盘。</li>
 *   <li>书籍实例一变（打开别的书 / 新建）就清空整个镜像目录。</li>
 *   <li>镜像内容全部是可再生的派生数据，随 {@code ~/.Epubra/preview/} 一起可随时丢弃；
 *       进程退出时由 shutdown hook 清掉。</li>
 * </ul>
 *
 * <p>不依赖 JavaFX，可无头单测。
 */
public final class PreviewMirror {

    /** 超过这个长度只用字节数做指纹，避免每次预览都对大图做全量散列。 */
    private static final long HASH_LIMIT = 128 * 1024;

    private static final AtomicBoolean CLEANUP_HOOK_REGISTERED = new AtomicBoolean(false);

    private static final System.Logger LOG = System.getLogger(PreviewMirror.class.getName());

    private final Path root;

    /** href → 内容指纹（尺寸 + 小文件散列），用于跳过未变的文件。 */
    private final Map<String, Long> fingerprints = new HashMap<>();

    /** 当前镜像对应的书籍实例；换书即清空重建。 */
    private Book session;

    /** 一旦镜像失败就整体降级（返回 null，预览退回无 base 的老行为），并只告警一次。 */
    private boolean degraded;

    /**
     * 用指定目录作为镜像根；构造过程不产生任何磁盘副作用（测试友好）。
     */
    public PreviewMirror(Path root) {
        this.root = root == null ? null : root.toAbsolutePath().normalize();
    }

    /**
     * 生产入口：镜像到 {@link AppPaths#previewDir()}，并注册退出清理。
     */
    public static PreviewMirror forUserData() {
        PreviewMirror mirror = new PreviewMirror(AppPaths.previewDir());
        registerCleanupHook();
        return mirror;
    }

    /**
     * 同步（必要时）当前章节可达的资源，返回该章节在镜像里的目录 URI。
     *
     * @param book        当前书籍
     * @param chapterHref 当前章节的容器内路径
     * @return 形如 {@code file:///…/preview/OEBPS/text/} 的基准地址；
     *         书籍 / 章节缺失或镜像不可用时返回 {@code null}（调用方应退回无 base 的加载）
     */
    public String baseHrefFor(Book book, String chapterHref) {
        if (degraded || root == null || book == null || chapterHref == null || chapterHref.isBlank()) {
            return null;
        }
        try {
            ensureSession(book);
            sync(book, chapterHref);
            Path dir = safeResolve(Hrefs.parentDirectory(chapterHref));
            if (dir == null) {
                return null;
            }
            Files.createDirectories(dir);
            String uri = dir.toUri().toString();
            return uri.endsWith("/") ? uri : uri + "/";
        } catch (IOException | RuntimeException e) {
            degraded = true;
            LOG.log(System.Logger.Level.WARNING,
                    "预览资源镜像不可用，图片预览将退化为不注入 base：" + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 把单个资源补写进镜像（插图后立即显示用）。
     *
     * <p>{@link #sync} 只镜像「章节可达」的资源——刚插入正文的图在书的章节文本尚未回写时
     * 不可达，若不补写，可视化编辑器里新插的 {@code <img>} 解析不到文件就是裂图。
     * 本方法在<b>插入之前</b>调用，把目标图片先写到磁盘，img 一进 DOM 就能加载。
     *
     * <p>书实例与当前镜像会话不一致时（换书窗口期）不补写——下次章节加载会整体重建，
     * 此时贸然写入会把新书资源混进旧书镜像。镜像不可用（degraded）时静默返回 false，
     * 与 {@link #baseHrefFor} 的降级行为一致。
     *
     * @return 是否成功写盘（未写不报错，预览退化由 baseHrefFor 一侧兜底）
     */
    public boolean mirrorResource(Book book, Resource resource) {
        if (degraded || root == null || book == null || resource == null
                || resource.data() == null || resource.data().length == 0) {
            return false;
        }
        if (book != session) {
            return false;
        }
        try {
            mirror(resource);
            return true;
        } catch (IOException | RuntimeException e) {
            // 单张补写失败不禁用整个镜像：下次章节加载的 sync 还有机会补上
            LOG.log(System.Logger.Level.WARNING,
                    "补写预览镜像失败：" + resource.href() + "：" + e.getMessage(), e);
            return false;
        }
    }

    /** 丢弃全部镜像内容。进程退出前由 shutdown hook 调用。 */
    public void discard() {
        session = null;
        fingerprints.clear();
        deleteContents(root);
    }

    // ------------------------------------------------------------------ 内部

    private void ensureSession(Book book) {
        if (book == session) {
            return;
        }
        deleteContents(root);
        fingerprints.clear();
        session = book;
    }

    /**
     * 以 {@code chapterHref} 为起点做广度遍历，把可达资源全部镜像出去。
     *
     * <p>只对 XHTML / SVG / CSS 继续解析它们的引用；图片、字体这类叶子资源写盘即止。
     * 用 visited 去重，循环引用不会打转。
     */
    private void sync(Book book, String chapterHref) throws IOException {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(chapterHref);
        visited.add(chapterHref);
        while (!queue.isEmpty()) {
            String href = queue.poll();
            Resource resource = ResourceReferences.findResource(book.resources(), href).resource();
            if (resource == null) {
                continue;
            }
            mirror(resource);
            if (!extractable(resource)) {
                continue;
            }
            String baseDir = Hrefs.parentDirectory(resource.href());
            for (ResourceReferences.Reference reference : ResourceReferences.extract(resource).references()) {
                String target = ResourceReferences.resolveTarget(baseDir, reference.rawTarget());
                if (target != null && visited.add(target)) {
                    queue.add(target);
                }
            }
        }
    }

    /** 写单个资源；内容未变则跳过。 */
    private void mirror(Resource resource) throws IOException {
        byte[] data = resource.data();
        if (data == null || data.length == 0) {
            return;
        }
        Path target = safeResolve(resource.href());
        if (target == null) {
            return;
        }
        long fingerprint = fingerprint(data);
        Long known = fingerprints.get(resource.href());
        if (known != null && known == fingerprint && Files.exists(target)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, data);
        fingerprints.put(resource.href(), fingerprint);
    }

    private static boolean extractable(Resource resource) {
        String mediaType = resource.mediaType();
        return MediaTypes.XHTML.equals(mediaType)
                || MediaTypes.SVG.equals(mediaType)
                || MediaTypes.CSS.equals(mediaType);
    }

    /** 大文件只用长度（不再逐字节散列）；小文件用「长度 + 散列」，正文改写能被精确识别。 */
    private static long fingerprint(byte[] data) {
        long length = data.length;
        if (length > HASH_LIMIT) {
            return length;
        }
        return (length << 32) ^ (Arrays.hashCode(data) & 0xFFFFFFFFL);
    }

    /**
     * 把容器内路径映射到镜像目录，并挡掉逃出根目录的路径（{@code ../…} 一类）。
     *
     * @return 根目录内的绝对路径；越界或为空时返回 {@code null}
     */
    private Path safeResolve(String containerPath) {
        if (containerPath == null || containerPath.isBlank()) {
            return null;
        }
        String clean = containerPath.replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.isEmpty()) {
            return null;
        }
        Path resolved = root.resolve(clean).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static void registerCleanupHook() {
        if (!CLEANUP_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> deleteContents(AppPaths.previewDir()), "epubra-preview-cleanup"));
        } catch (IllegalStateException alreadyShuttingDown) {
            // JVM 已在关闭流程里，注册不进去也无所谓：目录本来就是可丢弃的派生数据
        }
    }

    private static void deleteContents(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "清理预览镜像目录失败：" + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.list(path)) {
                    for (Path child : stream.toList()) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删不掉就留着，下次启动 / 换书会再试一遍
        }
    }
}
