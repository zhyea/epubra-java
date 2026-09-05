package com.epubra.epublib.io;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.ChapterTemplates;
import com.epubra.epublib.domain.MediaTypes;
import com.epubra.epublib.domain.Metadata;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.SpineReference;
import com.epubra.epublib.domain.TOCReference;
import com.epubra.epublib.util.Hrefs;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把 {@link Book} 写出为标准 EPUB 3 容器。
 *
 * <p>始终同时生成 EPUB 3 的 nav.xhtml 与 EPUB 2 兼容的 toc.ncx，
 * mimetype 按规范以 STORED 方式作为第一个条目写入。
 */
public class EpubWriter {

    private static final String MIMETYPE = "application/epub+zip";
    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    public void write(Book book, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeTo(book, zip);
        }
    }

    /**
     * 写出到输出流。
     *
     * <p>ZIP 的中央目录在 {@link ZipOutputStream#close()} 时才写入，仅 flush 得到的是不完整归档。
     * 因此：调用方传入 {@link ZipOutputStream} 时由调用方自行关闭；
     * 传入普通流时，本方法负责关闭它内部创建的 ZIP 流，保证返回的字节是完整 EPUB。
     */
    public void write(Book book, OutputStream out) throws IOException {
        if (out instanceof ZipOutputStream zip) {
            writeTo(book, zip);
            zip.flush();
            return;
        }
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeTo(book, zip);
        }
    }

    private void writeTo(Book book, ZipOutputStream zip) throws IOException {
        normalize(book);

        Resource nav = ensureNav(book);
        Resource ncx = ensureNcx(book);
        // nav / NCX 是由目录派生的生成物：内容必须同步回资源，否则内存模型里的导航文档会停留在
        // 上一次生成（或干脆是空的），与写出结果分叉，校验时表现为「导航文档无法解析」这类误报
        nav.setString(generateNav(book));
        ncx.setString(generateNcx(book));

        writeMimetype(zip);
        writeEntry(zip, "META-INF/container.xml", containerXml(book).getBytes(StandardCharsets.UTF_8));

        writeEntry(zip, book.opfPath(), generateOpf(book, nav, ncx).getBytes(StandardCharsets.UTF_8));
        writeEntry(zip, nav.href(), nav.data());
        writeEntry(zip, ncx.href(), ncx.data());

        for (Resource resource : book.resources().all()) {
            if (resource == nav || resource == ncx) {
                continue;
            }
            if (resource.href() == null || resource.href().isBlank() || resource.href().endsWith("/")) {
                continue;
            }
            writeEntry(zip, resource.href(), resource.data());
        }
    }

    /** 补齐写出前必须的元数据、spine 与目录。 */
    private void normalize(Book book) {
        Metadata metadata = book.metadata();
        if (metadata.primaryIdentifier() == null) {
            metadata.addIdentifier(new Metadata.Identifier("pub-id", "uuid", UUID.randomUUID().toString(), true));
        }
        metadata.setProperty("dcterms:modified", MODIFIED.format(Instant.now()));
        if (metadata.firstTitle().isBlank()) {
            metadata.setFirstTitle("未命名书籍");
        }
        if (metadata.language() == null || metadata.language().isBlank()) {
            metadata.setLanguage("zh-CN");
        }
        if (book.spine().size() == 0) {
            book.resources().all().stream()
                    .filter(Resource::isText)
                    .filter(r -> !r.isNavDocument())
                    .forEach(r -> book.spine().addResourceId(r.id()));
        }
        if (book.toc().isEmpty()) {
            for (Resource chapter : book.spineResources()) {
                book.toc().add(ChapterTemplates.extractTitle(chapter.asString()),
                        book.relativeToContentDirectory(chapter.href()));
            }
        }
    }

    private Resource ensureNav(Book book) {
        Resource existing = book.navResource();
        if (existing != null) {
            return existing;
        }
        String href = book.resources().uniqueHref(book.contentDirectory() + "nav.xhtml");
        Resource nav = new Resource(book.resources().uniqueId("nav"), href, MediaTypes.XHTML);
        nav.setProperties("nav");
        book.resources().add(nav);
        return nav;
    }

    private Resource ensureNcx(Book book) {
        String tocId = book.spine().tocResourceId();
        Resource existing = tocId == null ? null : book.resources().getById(tocId);
        if (existing != null) {
            return existing;
        }
        Resource ncx = new Resource(book.resources().uniqueId("ncx"),
                book.resources().uniqueHref(book.contentDirectory() + "toc.ncx"),
                MediaTypes.NCX);
        book.resources().add(ncx);
        book.spine().setTocResourceId(ncx.id());
        return ncx;
    }

    // ------------------------------------------------------------------ 生成

    private String containerXml(Book book) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles>
                    <rootfile full-path="%s" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.formatted(escapeAttr(book.opfPath()));
    }

    private String generateOpf(Book book, Resource nav, Resource ncx) {
        Metadata metadata = book.metadata();
        Metadata.Identifier primary = metadata.primaryIdentifier();
        String primaryId = primary != null && primary.id() != null && !primary.id().isBlank()
                ? primary.id() : "pub-id";

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="%s">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                """.formatted(escapeAttr(primaryId)));

        for (Metadata.Identifier identifier : metadata.identifiers()) {
            String id = identifier.id() == null || identifier.id().isBlank() ? "" : " id=\"" + escapeAttr(identifier.id()) + "\"";
            sb.append("    <dc:identifier%s>%s</dc:identifier>\n".formatted(id, escape(identifier.raw())));
        }
        for (String title : metadata.titles()) {
            sb.append("    <dc:title>%s</dc:title>\n".formatted(escape(title)));
        }
        for (String creator : metadata.creators()) {
            sb.append("    <dc:creator>%s</dc:creator>\n".formatted(escape(creator)));
        }
        for (String subject : metadata.subjects()) {
            sb.append("    <dc:subject>%s</dc:subject>\n".formatted(escape(subject)));
        }
        appendIfPresent(sb, "dc:language", metadata.language());
        appendIfPresent(sb, "dc:publisher", metadata.publisher());
        appendIfPresent(sb, "dc:description", metadata.description());
        appendIfPresent(sb, "dc:date", metadata.date());
        appendIfPresent(sb, "dc:rights", metadata.rights());

        metadata.properties().forEach((property, value) -> {
            if ("cover".equals(property) && value != null && !value.isBlank()) {
                sb.append("    <meta name=\"cover\" content=\"%s\"/>\n".formatted(escapeAttr(value)));
            } else if (value != null && !value.isBlank()) {
                sb.append("    <meta property=\"%s\">%s</meta>\n".formatted(escapeAttr(property), escape(value)));
            }
        });
        sb.append("  </metadata>\n");

        sb.append("  <manifest>\n");
        String baseDir = book.contentDirectory();
        for (Resource resource : book.resources().all()) {
            String properties = resource.properties() == null || resource.properties().isBlank()
                    ? "" : " properties=\"" + escapeAttr(resource.properties()) + "\"";
            sb.append("    <item id=\"%s\" href=\"%s\" media-type=\"%s\"%s/>\n".formatted(
                    escapeAttr(resource.id()),
                    escapeAttr(Hrefs.relativize(baseDir, resource.href())),
                    escapeAttr(resource.mediaType()),
                    properties));
        }
        sb.append("  </manifest>\n");

        sb.append("  <spine toc=\"%s\">\n".formatted(escapeAttr(ncx.id())));
        for (SpineReference reference : book.spine().references()) {
            String linear = reference.linear() ? "" : " linear=\"no\"";
            sb.append("    <itemref idref=\"%s\"%s/>\n".formatted(escapeAttr(reference.resourceId()), linear));
        }
        sb.append("  </spine>\n");
        sb.append("</package>\n");
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String tag, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("    <%s>%s</%s>\n".formatted(tag, escape(value), tag));
        }
    }

    private String generateNav(Book book) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head>
                  <title>%s</title>
                </head>
                <body>
                <nav epub:type="toc" id="toc">
                  <h1>目录</h1>
                """.formatted(escape(book.metadata().firstTitle())));
        if (book.toc().isEmpty()) {
            sb.append("  <ol></ol>\n");
        } else {
            writeNavList(sb, book.toc().roots(), 1);
        }
        sb.append("""
                </nav>
                </body>
                </html>
                """);
        return sb.toString();
    }

    private void writeNavList(StringBuilder sb, List<TOCReference> nodes, int level) {
        String indent = "  ".repeat(level + 1);
        sb.append(indent).append("<ol>\n");
        for (TOCReference node : nodes) {
            sb.append(indent).append("  <li><a href=\"")
                    .append(escapeAttr(node.href()))
                    .append("\">")
                    .append(escape(node.title()))
                    .append("</a>");
            if (node.children().isEmpty()) {
                sb.append("</li>\n");
            } else {
                sb.append("\n");
                writeNavList(sb, node.children(), level + 1);
                sb.append(indent).append("  </li>\n");
            }
        }
        sb.append(indent).append("</ol>\n");
    }

    private String generateNcx(Book book) {
        Metadata.Identifier primary = book.metadata().primaryIdentifier();
        String uid = primary == null ? UUID.randomUUID().toString() : primary.raw();
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head>
                    <meta name="dtb:uid" content="%s"/>
                  </head>
                  <docTitle>
                    <text>%s</text>
                  </docTitle>
                  <navMap>
                """.formatted(escapeAttr(uid), escape(book.metadata().firstTitle())));
        int[] playOrder = {1};
        writeNavPoints(sb, book.toc().roots(), playOrder);
        sb.append("""
                  </navMap>
                </ncx>
                """);
        return sb.toString();
    }

    private void writeNavPoints(StringBuilder sb, List<TOCReference> nodes, int[] playOrder) {
        for (TOCReference node : nodes) {
            String id = "navPoint-" + playOrder[0];
            sb.append("    <navPoint id=\"%s\" playOrder=\"%d\">\n".formatted(id, playOrder[0]++));
            sb.append("      <navLabel>\n        <text>%s</text>\n      </navLabel>\n".formatted(escape(node.title())));
            sb.append("      <content src=\"%s\"/>\n".formatted(escapeAttr(node.href())));
            if (!node.children().isEmpty()) {
                writeNavPoints(sb, node.children(), playOrder);
            }
            sb.append("    </navPoint>\n");
        }
    }

    // ------------------------------------------------------------------ ZIP

    private void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] bytes = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void writeEntry(ZipOutputStream zip, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path.replace('\\', '/'));
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static String escape(String text) {
        return ChapterTemplates.escape(text);
    }

    private static String escapeAttr(String text) {
        return text == null ? "" : ChapterTemplates.escape(text).replace("\"", "&quot;");
    }
}
