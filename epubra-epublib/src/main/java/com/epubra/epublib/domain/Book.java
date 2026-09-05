package com.epubra.epublib.domain;

import com.epubra.epublib.util.Hrefs;
import com.epubra.epublib.util.ResourceReferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 一本 EPUB 书籍的完整内存模型。
 */
public class Book {

    private final Metadata metadata = new Metadata();
    private final Resources resources = new Resources();
    private final Spine spine = new Spine();
    private final TableOfContents toc = new TableOfContents();

    private EpubVersion version = EpubVersion.EPUB_3;
    /** OPF 在容器内的路径，例如 {@code "OEBPS/content.opf"}。 */
    private String opfPath = "OEBPS/content.opf";
    /** 封面资源的 manifest id，可能为 null。 */
    private String coverResourceId;
    /** 最近一次读取或保存的文件位置，新建书籍为 null。 */
    private Path source;

    public Metadata metadata() {
        return metadata;
    }

    public Resources resources() {
        return resources;
    }

    public Spine spine() {
        return spine;
    }

    public TableOfContents toc() {
        return toc;
    }

    public EpubVersion version() {
        return version;
    }

    public void setVersion(EpubVersion version) {
        this.version = version;
    }

    public String opfPath() {
        return opfPath;
    }

    public void setOpfPath(String opfPath) {
        this.opfPath = opfPath;
    }

    /** 资源所在的基准目录（OPF 所在目录），根目录下为空串。 */
    public String contentDirectory() {
        int slash = opfPath.lastIndexOf('/');
        return slash < 0 ? "" : opfPath.substring(0, slash + 1);
    }

    public String coverResourceId() {
        return coverResourceId;
    }

    public void setCoverResourceId(String coverResourceId) {
        this.coverResourceId = coverResourceId;
    }

    public Path source() {
        return source;
    }

    public void setSource(Path source) {
        this.source = source;
    }

    /** 按阅读顺序返回正文资源；spine 中指向缺失资源的条目会被跳过。 */
    public List<Resource> spineResources() {
        List<Resource> list = new ArrayList<>();
        for (SpineReference ref : spine.references()) {
            Resource resource = resources.getById(ref.resourceId());
            if (resource != null) {
                list.add(resource);
            }
        }
        return list;
    }

    public Optional<Resource> coverResource() {
        Optional<Resource> byProperty = resources.all().stream()
                .filter(r -> r.properties() != null && r.properties().contains("cover-image"))
                .findFirst();
        if (byProperty.isPresent()) {
            return byProperty;
        }
        return Optional.ofNullable(coverResourceId == null ? null : resources.getById(coverResourceId));
    }

    /** 追加一个正文章节，同时登记 manifest、spine 与目录。 */
    public Resource addChapter(String title, String xhtml) {
        int index = spine.size() + 1;
        String id = resources.uniqueId("chapter-" + index);
        String href = resources.uniqueHref(contentDirectory() + "chapter-" + index + ".xhtml");
        Resource chapter = new Resource(id, href, MediaTypes.XHTML);
        chapter.setString(xhtml != null ? xhtml : ChapterTemplates.empty(title));
        resources.add(chapter);
        spine.addResourceId(id);
        toc.add(title == null || title.isBlank() ? id : title, relativeToContentDirectory(href));
        return chapter;
    }

    /** 把容器内绝对路径转换为相对 OPF 目录的路径。 */
    public String relativeToContentDirectory(String href) {
        String dir = contentDirectory();
        if (dir.isEmpty() || !href.startsWith(dir)) {
            return href;
        }
        return href.substring(dir.length());
    }

    /** EPUB 3 导航文档（nav.xhtml）资源，可能为 null。 */
    public Resource navResource() {
        return resources.all().stream()
                .filter(Resource::isNavDocument)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------ 资源管理

    /** 资源在容器内的存放子目录，按媒体类型归类。 */
    private static String subDirectoryFor(String mediaType) {
        if (mediaType == null) {
            return "misc/";
        }
        return switch (mediaType) {
            case MediaTypes.PNG, MediaTypes.JPEG, MediaTypes.GIF, MediaTypes.WEBP, MediaTypes.SVG -> "images/";
            case MediaTypes.CSS -> "styles/";
            case MediaTypes.TTF, MediaTypes.OTF, MediaTypes.WOFF, MediaTypes.WOFF2 -> "fonts/";
            default -> "misc/";
        };
    }

    /** 由文件名生成合法的 manifest id：非法字符替换为下划线，且保证以字母或下划线开头。 */
    private static String toId(String fileName) {
        String base = fileName == null ? "res" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String id = base.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (id.isEmpty()) {
            id = "res";
        }
        if (!Character.isLetter(id.charAt(0)) && id.charAt(0) != '_') {
            id = "r_" + id;
        }
        return id;
    }

    /** 从磁盘文件导入一份资源（图片、字体、样式等），按媒体类型归入相应子目录。 */
    public Resource addResource(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        String fileName = file.getFileName().toString();
        String mediaType = MediaTypes.guessByExtension(fileName);
        String href = resources.uniqueHref(contentDirectory() + subDirectoryFor(mediaType) + fileName);
        Resource resource = new Resource(resources.uniqueId(toId(fileName)), href, mediaType, data);
        resources.add(resource);
        return resource;
    }

    /** 移除资源，同时清理 spine 与目录中指向它的引用。 */
    public void removeResource(Resource resource) {
        if (resource == null) {
            return;
        }
        spine.removeResourceId(resource.id());
        removeTocNodesByHref(toc.roots(), resource.href());
        resources.removeByHref(resource.href());
        if (resource.id().equals(coverResourceId)) {
            setCover(null);
        }
    }

    private void removeTocNodesByHref(List<TOCReference> nodes, String href) {
        List<TOCReference> matched = new ArrayList<>();
        for (TOCReference node : nodes) {
            String full = Hrefs.resolve(contentDirectory(), node.resourceHref());
            if (href.equals(full)) {
                matched.add(node);
            } else {
                removeTocNodesByHref(node.children(), href);
            }
        }
        nodes.removeAll(matched);
    }

    /**
     * 未被任何地方引用的资源：既不在 spine 与目录中，也不是 nav/ncx/封面，
     * 且没有被任何 XHTML / SVG / CSS 真实引用。
     *
     * <p>引用判定走 {@link ResourceReferences} 的精确提取（img src、link href、CSS url()、
     * @font-face 等），而不是「把全文拼起来再找文件名」——后者会把正文里恰好提到某个
     * 文件名的情况误判为引用，也会漏掉 CSS 里 {@code url()} 引用的字体。
     *
     * <p>校验器的「孤儿资源」规则直接复用本方法，因此「校验报 N 个孤儿」与
     * 「点清理删 N 个」永远一致。
     */
    public List<Resource> unreferencedResources() {
        Set<String> spineIds = new HashSet<>();
        spine.references().forEach(ref -> spineIds.add(ref.resourceId()));

        Set<String> referencedHrefs = collectReferencedHrefs();

        Resource nav = navResource();
        String tocId = spine.tocResourceId();

        List<Resource> orphans = new ArrayList<>();
        for (Resource resource : resources.all()) {
            if (spineIds.contains(resource.id())
                    || resource == nav
                    || resource.isNavDocument()
                    || resource.id().equals(tocId)
                    || MediaTypes.NCX.equals(resource.mediaType())
                    || resource.id().equals(coverResourceId)
                    || (resource.properties() != null && resource.properties().contains("cover-image"))
                    || isReferenced(referencedHrefs, resource)) {
                continue;
            }
            orphans.add(resource);
        }
        return orphans;
    }

    /** 收集全书 XHTML / SVG / CSS 中出现的全部真实引用目标（容器内的绝对路径，忽略大小写）。 */
    private Set<String> collectReferencedHrefs() {
        Set<String> referenced = new HashSet<>();
        for (Resource source : resources.all()) {
            if (!isScannable(source)) {
                continue;
            }
            String baseDir = Hrefs.parentDirectory(source.href());
            for (ResourceReferences.Reference reference : ResourceReferences.extract(source).references()) {
                String resolved = ResourceReferences.resolveTarget(baseDir, reference.rawTarget());
                if (resolved == null || resolved.isEmpty()) {
                    continue;
                }
                int hash = resolved.indexOf('#');
                String path = hash < 0 ? resolved : resolved.substring(0, hash);
                if (!path.isEmpty()) {
                    referenced.add(path.toLowerCase(Locale.ROOT));
                }
            }
        }
        return referenced;
    }

    /** XHTML / SVG / CSS 才可能携带 URI 引用。 */
    private static boolean isScannable(Resource resource) {
        String mediaType = resource.mediaType();
        return MediaTypes.XHTML.equals(mediaType)
                || MediaTypes.SVG.equals(mediaType)
                || MediaTypes.CSS.equals(mediaType);
    }

    private static boolean isReferenced(Set<String> referencedHrefs, Resource resource) {
        String href = resource.href();
        if (href == null || href.isEmpty() || referencedHrefs.isEmpty()) {
            return false;
        }
        return referencedHrefs.contains(href.toLowerCase(Locale.ROOT));
    }

    /**
     * 设置封面图片：为资源追加 cover-image 属性并写入 {@code <meta name="cover">}；
     * 传入 null 表示清除封面。
     */
    public void setCover(Resource cover) {
        for (Resource resource : resources.all()) {
            String properties = resource.properties();
            if (properties != null && properties.contains("cover-image")) {
                String cleaned = properties.replace("cover-image", "").replaceAll("\\s+", " ").trim();
                resource.setProperties(cleaned.isEmpty() ? null : cleaned);
            }
        }
        if (cover == null) {
            coverResourceId = null;
            metadata.properties().remove("cover");
            return;
        }
        String properties = cover.properties();
        cover.setProperties(properties == null || properties.isBlank()
                ? "cover-image"
                : properties + " cover-image");
        coverResourceId = cover.id();
        metadata.setProperty("cover", cover.id());
    }
}
