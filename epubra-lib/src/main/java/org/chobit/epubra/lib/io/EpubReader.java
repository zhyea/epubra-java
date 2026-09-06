package org.chobit.epubra.lib.io;

import org.chobit.epubra.lib.EpubException;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.EpubVersion;
import org.chobit.epubra.lib.domain.Metadata;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.util.Xmls;
import org.chobit.epubra.lib.domain.SpineReference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读取 EPUB 容器（ZIP），还原为 {@link Book} 内存模型。
 *
 * <p>同时支持 EPUB 3 的 nav.xhtml 与 EPUB 2 的 toc.ncx；两者皆缺失时按 spine 顺序生成目录。
 */
public class EpubReader {

    private static final System.Logger LOG = System.getLogger(EpubReader.class.getName());
    private static final String CONTAINER_PATH = "META-INF/container.xml";

    public Book read(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            Book book = new Book();
            book.setSource(file.toAbsolutePath());

            String opfPath = readOpfPath(zip);
            book.setOpfPath(opfPath);
            String baseDir = Hrefs.parentDirectory(opfPath);

            byte[] opfData = readEntry(zip, opfPath);
            Document opf = Xmls.parse(opfData);
            Element packageElement = opf.getDocumentElement();
            book.setVersion(EpubVersion.fromSpecVersion(packageElement.getAttribute("version")));

            readMetadata(packageElement, book);
            readManifest(packageElement, zip, baseDir, book);
            readSpine(packageElement, book);
            readCover(packageElement, book);
            readToc(book, zip, baseDir);

            return book;
        }
    }

    /** 从输入流读取；ZIP 需要随机访问，内部借助临时文件中转。 */
    public Book read(InputStream input) throws IOException {
        Path temp = Files.createTempFile("epubra-", ".epub");
        try {
            try (input) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return read(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ------------------------------------------------------------------ 结构

    private String readOpfPath(ZipFile zip) {
        ZipEntry containerEntry = zip.getEntry(CONTAINER_PATH);
        if (containerEntry != null) {
            try {
                byte[] data = readEntry(zip, CONTAINER_PATH);
                Document doc = Xmls.parse(data);
                List<Element> rootFiles = Xmls.descendants(doc.getDocumentElement(), "rootfile");
                for (Element rootFile : rootFiles) {
                    String fullPath = rootFile.getAttribute("full-path");
                    if (!fullPath.isBlank()) {
                        return fullPath.replace('\\', '/');
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "container.xml 解析失败，回退扫描 .opf：" + e.getMessage());
            }
        }
        // 容错：容器文件缺失时扫描根目录下的 .opf
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.toLowerCase().endsWith(".opf")) {
                return name;
            }
        }
        throw new EpubException("不是有效的 EPUB 文件：找不到 OPF 包文档");
    }

    private void readMetadata(Element packageElement, Book book) {
        Element metadataElement = Xmls.child(packageElement, "metadata");
        if (metadataElement == null) {
            return;
        }
        Metadata metadata = book.metadata();
        String uniqueIdentifierId = packageElement.getAttribute("unique-identifier");

        for (Element element : Xmls.children(metadataElement)) {
            String name = Xmls.localName(element);
            String text = Xmls.textOf(element);
            switch (name) {
                case "title" -> metadata.addTitle(text);
                case "creator" -> metadata.addCreator(text);
                case "subject" -> metadata.addSubject(text);
                case "language" -> metadata.setLanguage(text);
                case "publisher" -> metadata.setPublisher(text);
                case "description" -> metadata.setDescription(text);
                case "date" -> metadata.setDate(text);
                case "rights" -> metadata.setRights(text);
                case "identifier" -> metadata.addIdentifier(
                        Metadata.Identifier.parse(element.getAttribute("id"), text,
                                !uniqueIdentifierId.isBlank() && uniqueIdentifierId.equals(element.getAttribute("id"))));
                case "meta" -> {
                    String property = element.getAttribute("property");
                    if (!property.isBlank()) {
                        metadata.setProperty(property, text);
                    } else {
                        // EPUB 2 风格的 <meta name="cover" content="..."/>
                        String metaName = element.getAttribute("name");
                        if (!metaName.isBlank()) {
                            metadata.setProperty(metaName, element.getAttribute("content"));
                        }
                    }
                }
                default -> {
                    // 其余扩展元数据忽略
                }
            }
        }
    }

    private void readManifest(Element packageElement, ZipFile zip, String baseDir, Book book) {
        Element manifestElement = Xmls.child(packageElement, "manifest");
        if (manifestElement == null) {
            return;
        }
        for (Element item : Xmls.children(manifestElement, "item")) {
            String id = item.getAttribute("id");
            String rawHref = item.getAttribute("href");
            if (id.isBlank() || rawHref.isBlank()) {
                continue;
            }
            String mediaType = item.getAttribute("media-type");
            String containerPath = Hrefs.resolve(baseDir, rawHref);
            byte[] data = readEntryOrNull(zip, containerPath);
            if (data == null) {
                LOG.log(System.Logger.Level.WARNING, () -> "清单条目在容器内缺失：" + containerPath);
            }
            Resource resource = new Resource(id, containerPath, mediaType, data == null ? new byte[0] : data);
            String properties = item.getAttribute("properties");
            if (!properties.isBlank()) {
                resource.setProperties(properties);
            }
            book.resources().add(resource);
        }
    }

    private void readSpine(Element packageElement, Book book) {
        Element spineElement = Xmls.child(packageElement, "spine");
        if (spineElement == null) {
            // 容错：没有 spine 时把所有文本资源按清单顺序作为阅读顺序
            book.resources().all().stream()
                    .filter(Resource::isText)
                    .filter(r -> !r.isNavDocument())
                    .forEach(r -> book.spine().addResourceId(r.id()));
            return;
        }
        String tocId = spineElement.getAttribute("toc");
        if (!tocId.isBlank()) {
            book.spine().setTocResourceId(tocId);
        }
        for (Element itemRef : Xmls.children(spineElement, "itemref")) {
            String idref = itemRef.getAttribute("idref");
            if (idref.isBlank()) {
                continue;
            }
            book.spine().add(new SpineReference(idref,
                    !"no".equalsIgnoreCase(itemRef.getAttribute("linear"))));
        }
    }

    private void readCover(Element packageElement, Book book) {
        String coverId = book.metadata().property("cover");
        if (coverId != null && !coverId.isBlank()) {
            book.setCoverResourceId(coverId);
        }
    }

    // ------------------------------------------------------------------ 目录

    private void readToc(Book book, ZipFile zip, String baseDir) {
        Resource nav = book.navResource();
        if (nav != null && nav.data().length > 0) {
            try {
                parseNavDocument(book, nav, baseDir);
                return;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "nav 文档解析失败，回退到 NCX：" + e.getMessage());
            }
        }
        String tocId = book.spine().tocResourceId();
        Resource ncx = tocId == null ? null : book.resources().getById(tocId);
        if (ncx != null && ncx.data().length > 0) {
            try {
                parseNcx(book, ncx, baseDir);
                return;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "NCX 解析失败，按 spine 生成目录：" + e.getMessage());
            }
        }
        generateTocFromSpine(book);
    }

    private void parseNavDocument(Book book, Resource nav, String baseDir) {
        Document doc = Xmls.parse(nav.data());
        Element navElement = pickTocNav(doc.getDocumentElement());
        if (navElement == null) {
            throw new EpubException("nav 文档中找不到目录节点");
        }
        Element ol = Xmls.child(navElement, "ol");
        if (ol == null) {
            throw new EpubException("nav 文档中找不到 ol 列表");
        }
        String navDir = Hrefs.parentDirectory(nav.href());
        parseNavList(ol, book.toc().roots(), navDir, baseDir);
    }

    private Element pickTocNav(Element root) {
        List<Element> navs = Xmls.descendants(root, "nav");
        for (Element nav : navs) {
            if (Xmls.hasEpubType(nav, "toc")) {
                return nav;
            }
        }
        return navs.isEmpty() ? null : navs.get(0);
    }

    private void parseNavList(Element ol, List<TOCReference> sink, String navDir, String baseDir) {
        for (Element li : Xmls.children(ol, "li")) {
            Element anchor = Xmls.child(li, "a");
            String title;
            String rawHref;
            if (anchor != null) {
                title = Xmls.textOf(anchor);
                rawHref = anchor.getAttribute("href");
            } else {
                Element span = Xmls.child(li, "span");
                title = span != null ? Xmls.textOf(span) : Xmls.textOf(li);
                rawHref = "";
            }
            String resolved = Hrefs.resolve(navDir, rawHref);
            String relative = Hrefs.relativize(baseDir, resolved);
            TOCReference reference = new TOCReference(title, relative);
            Element subList = Xmls.child(li, "ol");
            if (subList != null) {
                parseNavList(subList, reference.children(), navDir, baseDir);
            }
            sink.add(reference);
        }
    }

    private void parseNcx(Book book, Resource ncx, String baseDir) {
        Document doc = Xmls.parse(ncx.data());
        Element navMap = Xmls.child(doc.getDocumentElement(), "navMap");
        if (navMap == null) {
            throw new EpubException("NCX 中找不到 navMap");
        }
        String ncxDir = Hrefs.parentDirectory(ncx.href());
        parseNavPoints(navMap, book.toc().roots(), ncxDir, baseDir);
    }

    private void parseNavPoints(Element parent, List<TOCReference> sink, String ncxDir, String baseDir) {
        for (Element navPoint : Xmls.children(parent, "navPoint")) {
            Element label = Xmls.child(navPoint, "navLabel");
            String title = label == null ? "" : Xmls.textOf(Xmls.child(label, "text"));
            Element content = Xmls.child(navPoint, "content");
            String src = content == null ? "" : content.getAttribute("src");
            String relative = Hrefs.relativize(baseDir, Hrefs.resolve(ncxDir, src));
            TOCReference reference = new TOCReference(title, relative);
            parseNavPoints(navPoint, reference.children(), ncxDir, baseDir);
            sink.add(reference);
        }
    }

    private void generateTocFromSpine(Book book) {
        for (Resource chapter : book.spineResources()) {
            String title = ChapterTemplates.extractTitle(chapter.asString());
            book.toc().add(title, book.relativeToContentDirectory(chapter.href()));
        }
    }

    // ------------------------------------------------------------------ 工具

    private byte[] readEntry(ZipFile zip, String path) {
        byte[] data = readEntryOrNull(zip, path);
        if (data == null) {
            throw new EpubException("EPUB 文件缺少条目：" + path);
        }
        return data;
    }

    private byte[] readEntryOrNull(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new EpubException("读取条目失败：" + path, e);
        }
    }
}
