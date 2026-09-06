package org.chobit.epubra.lib.validation;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.EpubVersion;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.SpineReference;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验引擎的端到端验证：既覆盖「造病态 Book」的内存规则，也覆盖「造真实 .epub」的容器规则。
 */
class EpubValidatorTest {

    @TempDir
    Path tempDir;

    private final EpubWriter writer = new EpubWriter();
    private final EpubReader reader = new EpubReader();
    private final EpubValidator validator = new EpubValidator();

    // ------------------------------------------------------------------ 基线

    @Test
    void 正常书籍保存再读回应零问题() throws IOException {
        Book book = BookFactory.createEmpty("校验基线");
        book.addChapter("第二章", ChapterTemplates.empty("第二章"));
        Path file = tempDir.resolve("baseline.epub");
        writer.write(book, file);

        Book reloaded = reader.read(file);
        ValidationReport report = validator.validate(reloaded, file);

        assertTrue(report.containerChecked(), "传入真实文件时应跑容器级规则");
        assertTrue(report.isEmpty(), "正常书籍不应检出问题，实际为：" + describe(report));
        assertEquals("未发现问题", report.summary());
    }

    @Test
    void 未保存的书籍应只跑内存规则() {
        Book book = BookFactory.createEmpty("内存校验");
        ValidationReport report = validator.validate(book, tempDir.resolve("不存在的文件.epub"));
        assertFalse(report.containerChecked(), "文件不存在时应降级为内存校验");
    }

    @Test
    void 非ZIP文件应报容器不可读并继续跑内存规则() throws IOException {
        Book book = BookFactory.createEmpty("坏容器");
        Path notAZip = tempDir.resolve("not-a-zip.epub");
        Files.writeString(notAZip, "这不是一个 ZIP 文件");

        ValidationReport report = validator.validate(book, notAZip);
        assertTrue(hasCode(report, "A09"), "非 ZIP 应报 A09");
        assertFalse(report.containerChecked());
    }

    // ------------------------------------------------------------------ C 组

    @Test
    void spine的idref悬空应报C02() throws IOException {
        Book book = roundTrip("悬空 idref");
        book.spine().add(new SpineReference("ghost-chapter"));

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "C02"));
        assertFalse(report.isEmpty());
    }

    @Test
    void 正文文档不入spine应报C07() throws IOException {
        Book book = roundTrip("未入 spine");
        book.resources().add(new Resource("appendix", "OEBPS/appendix.xhtml", MediaTypes.XHTML,
                ChapterTemplates.empty("附录").getBytes(StandardCharsets.UTF_8)));

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "C07"));
    }

    @Test
    void spine的toc指向的NCX被删除后应报C04() throws IOException {
        Book book = roundTrip("删除 NCX");
        Resource ncx = book.resources().getById(book.spine().tocResourceId());
        assertNotNull(ncx, "基线书籍应带 NCX");
        book.resources().removeByHref(ncx.href());

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "C04"));
    }

    // ------------------------------------------------------------------ D 组

    @Test
    void 目录指向不存在的href应报D02() throws IOException {
        Book book = roundTrip("断链目录");
        book.toc().add("幽灵章节", "ghost-chapter.xhtml");

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "D02"));
    }

    @Test
    void EPUB3缺少nav文档应报D06而EPUB2不报() {
        Book book = BookFactory.createEmpty("没有导航");
        assertEquals(EpubVersion.EPUB_3, book.version());
        assertTrue(hasCode(validator.validate(book), "D06"), "EPUB 3 缺 nav 应报错");

        book.setVersion(EpubVersion.EPUB_2);
        assertFalse(hasCode(validator.validate(book), "D06"), "EPUB 2 不要求 nav，不应报错");
    }

    // ------------------------------------------------------------------ E 组

    @Test
    void 正文引用不存在的图片应报E02() throws IOException {
        Book book = roundTrip("断链图片");
        Resource chapter = book.spineResources().get(0);
        chapter.setString(chapter.asString().replace("</body>",
                "<img src=\"../images/missing.png\" alt=\"缺失\"/></body>"));

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "E02"));
        // 该引用从 OEBPS/ 上跳一级仍在容器内，不应被判为越界
        assertFalse(hasCode(report, "E03"));
    }

    @Test
    void CSS里的字体引用应被识别为已引用而不报E05() throws IOException {
        Book book = BookFactory.createEmpty("字体引用");

        Path cssFile = tempDir.resolve("main.css");
        Files.writeString(cssFile,
                "@font-face { font-family: Demo; src: url(fonts/demo.woff2) format('woff2'); }");
        Resource css = book.addResource(cssFile);

        // 手工放到 CSS 所引用的相对位置，使 url(fonts/demo.woff2) 能解析到它
        Resource font = new Resource("demo", "OEBPS/styles/fonts/demo.woff2", MediaTypes.WOFF2,
                new byte[]{0x77, 0x4F, 0x46, 0x32});
        book.resources().add(font);

        Resource chapter = book.spineResources().get(0);
        chapter.setString(chapter.asString().replace("</head>",
                "<link rel=\"stylesheet\" type=\"text/css\" href=\""
                        + book.relativeToContentDirectory(css.href()) + "\"/></head>"));

        Path file = tempDir.resolve("font.epub");
        writer.write(book, file);
        Book reloaded = reader.read(file);

        ValidationReport report = validator.validate(reloaded, file);
        assertEquals(0, countCode(report, "E05"),
                "CSS url() 引用的字体与被引用的 CSS 都不应算孤儿，实际为：" + describe(report));
        assertFalse(hasCode(report, "E02"), "CSS 与字体都应能解析到，实际为：" + describe(report));
    }

    @Test
    void 孤儿图片应报E05插入正文后不再报() throws IOException {
        Book book = roundTrip("孤儿资源");
        Path imageFile = tempDir.resolve("orphan.png");
        Files.write(imageFile, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        Resource image = book.addResource(imageFile);

        ValidationReport before = validator.validate(book);
        assertEquals(1, countCode(before, "E05"), "刚导入、尚未引用的图片应被判为孤儿");
        assertTrue(before.issues().stream().anyMatch(i -> image.href().equals(i.resourceHref())));

        Resource chapter = book.spineResources().get(0);
        chapter.setString(chapter.asString().replace("</body>",
                "<img src=\"" + book.relativeToContentDirectory(image.href()) + "\" alt=\"\"/></body>"));

        ValidationReport after = validator.validate(book);
        assertEquals(0, countCode(after, "E05"), "插入正文后不应再算孤儿");
        assertFalse(hasCode(after, "E02"), "引用应能解析到，实际为：" + describe(after));
    }

    @Test
    void 非良构的正文应报E01且仍能用正则回退抽引用() {
        Book book = BookFactory.createEmpty("非良构");
        Resource chapter = book.spineResources().get(0);
        chapter.setString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>坏</title></head>\n"
                + "<body><p>a & b</p><img src=\"OEBPS/images/missing.png\"/></body></html>");

        ValidationReport report = validator.validate(book);
        assertTrue(hasCode(report, "E01"), "未转义的 & 应被判为非良构");
        assertTrue(hasCode(report, "E02"), "回退抽取仍应发现断链引用");
    }

    // ------------------------------------------------------------------ A / B 组（容器级）

    @Test
    void mimetype被压缩存储应报A03() throws IOException {
        Book book = BookFactory.createEmpty("压缩 mimetype");
        Path base = tempDir.resolve("base.epub");
        writer.write(book, base);

        Path broken = rebuild(base, tempDir.resolve("deflated.epub"),
                Function.identity(), false, Map.of());
        Book reloaded = reader.read(broken);

        ValidationReport report = validator.validate(reloaded, broken);
        assertTrue(hasCode(report, "A03"), "DEFLATED 的 mimetype 应报错");
        assertFalse(hasCode(report, "A02"), "mimetype 仍是第一个条目，不应报 A02");
        assertFalse(hasCode(report, "A01"));
    }

    @Test
    void mimetype内容带尾随换行应报A04() throws IOException {
        Book book = BookFactory.createEmpty("多余换行");
        Path base = tempDir.resolve("base-nl.epub");
        writer.write(book, base);

        Path broken = rebuild(base, tempDir.resolve("newline.epub"),
                ignored -> "application/epub+zip\n".getBytes(StandardCharsets.US_ASCII), true, Map.of());
        Book reloaded = reader.read(broken);

        ValidationReport report = validator.validate(reloaded, broken);
        assertTrue(hasCode(report, "A04"), "带尾随换行的 mimetype 应报错");
        assertFalse(hasCode(report, "A03"), "压缩方式是对的，不应报 A03");
    }

    @Test
    void OPF中两个item同id应报B09() throws IOException {
        Book book = BookFactory.createEmpty("重复 id");
        Path base = tempDir.resolve("base-dup.epub");
        writer.write(book, base);

        String opf = readEntry(base, "OEBPS/content.opf");
        assertTrue(opf.contains("<item id=\"chapter-1\" href=\"chapter-1.xhtml\" media-type=\"application/xhtml+xml\"/>"),
                "基线 OPF 结构发生变化，需同步调整本用例：" + opf);
        String duplicated = opf.replace(
                "<item id=\"chapter-1\" href=\"chapter-1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<item id=\"chapter-1\" href=\"chapter-1.xhtml\" media-type=\"application/xhtml+xml\"/>\n"
                        + "    <item id=\"chapter-1\" href=\"ghost.xhtml\" media-type=\"application/xhtml+xml\"/>");

        Path broken = rebuild(base, tempDir.resolve("duplicate-id.epub"),
                Function.identity(), true, Map.of("OEBPS/content.opf", duplicated.getBytes(StandardCharsets.UTF_8)));

        ValidationReport report = validator.validate(reader.read(broken), broken);
        assertTrue(hasCode(report, "B09"), "原始 OPF 中的重复 id 应报错");
        assertFalse(hasCode(report, "A09"), "容器本身是合法的，不应报 A09");
    }

    @Test
    void 清单条目在容器中缺失应报B08() throws IOException {
        Book book = BookFactory.createEmpty("清单缺文件");
        Path base = tempDir.resolve("base-missing.epub");
        writer.write(book, base);

        // 从容器里删掉正文文件，但 OPF 清单仍然声明它
        Path broken = tempDir.resolve("missing-entry.epub");
        copyWithout(base, broken, "OEBPS/chapter-1.xhtml");

        ValidationReport report = validator.validate(reader.read(broken), broken);
        assertTrue(hasCode(report, "B08"));
        // B07（内存空数据）与 B08（容器内缺失）互斥：容器模式下只报 B08
        assertFalse(hasCode(report, "B07"));
    }

    // ------------------------------------------------------------------ 报告模型

    @Test
    void 报告应错误优先并统计数量() {
        List<ValidationIssue> issues = new ArrayList<>();
        issues.add(new ValidationIssue(IssueKind.TOC_EMPTY, "警告一"));
        issues.add(new ValidationIssue(IssueKind.SPINE_EMPTY, "错误一"));
        issues.add(new ValidationIssue(IssueKind.METADATA_TITLE_MISSING, "错误二"));

        ValidationReport report = new ValidationReport(issues, false);
        assertEquals(2, report.errorCount());
        assertEquals(1, report.warningCount());
        assertEquals(Severity.ERROR, report.issues().get(0).severity(), "错误应排在警告之前");
        assertEquals(Severity.ERROR, report.issues().get(1).severity());
        assertEquals(Severity.WARNING, report.issues().get(2).severity());
        assertEquals("2 个错误 · 1 个警告", report.summary());
        assertEquals("整书", report.issues().get(0).location(), "整书级问题的位置应显示「整书」");
    }

    @Test
    void 五十七条规则应各有唯一编号() {
        assertEquals(57, IssueKind.values().length);
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (IssueKind kind : IssueKind.values()) {
            assertTrue(codes.add(kind.code()), "规则编号重复：" + kind.code());
            assertNotNull(kind.group());
            assertNotNull(kind.severity());
            assertFalse(kind.template().isBlank(), kind.name() + " 缺少说明模板");
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 写出再读回，得到一本结构完整（带 nav / NCX）的书籍。 */
    private Book roundTrip(String title) throws IOException {
        Book book = BookFactory.createEmpty(title);
        Path file = tempDir.resolve(title + ".epub");
        writer.write(book, file);
        return reader.read(file);
    }

    private static boolean hasCode(ValidationReport report, String code) {
        return countCode(report, code) > 0;
    }

    private static long countCode(ValidationReport report, String code) {
        return report.issues().stream().filter(issue -> code.equals(issue.kind().code())).count();
    }

    private static String describe(ValidationReport report) {
        StringBuilder sb = new StringBuilder();
        for (ValidationIssue issue : report.issues()) {
            sb.append("\n  [").append(issue.kind().code()).append("] ")
                    .append(issue.location()).append(" → ").append(issue.message());
        }
        return sb.toString();
    }

    private static String readEntry(Path epub, String name) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, "容器中缺少条目 " + name);
            try (var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** 按原顺序重写整个 ZIP，可替换 mimetype 内容与压缩方式，以及任意条目的字节。 */
    private static Path rebuild(Path source, Path target,
                                Function<byte[], byte[]> mimetypeTransform,
                                boolean mimetypeStored,
                                Map<String, byte[]> replacements) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                names.add(entry.getName());
                try (var in = zip.getInputStream(entry)) {
                    data.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        data.putAll(replacements);

        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String name : names) {
                byte[] bytes = data.get(name);
                if (bytes == null) {
                    continue;
                }
                if ("mimetype".equals(name)) {
                    byte[] content = mimetypeTransform.apply(bytes);
                    ZipEntry entry = new ZipEntry(name);
                    if (mimetypeStored) {
                        entry.setMethod(ZipEntry.STORED);
                        entry.setSize(content.length);
                        entry.setCompressedSize(content.length);
                        CRC32 crc = new CRC32();
                        crc.update(content);
                        entry.setCrc(crc.getValue());
                    } else {
                        entry.setMethod(ZipEntry.DEFLATED);
                    }
                    zip.putNextEntry(entry);
                    zip.write(content);
                } else {
                    ZipEntry entry = new ZipEntry(name);
                    entry.setMethod(ZipEntry.DEFLATED);
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                }
                zip.closeEntry();
            }
        }
        return target;
    }

    /** 复制整个 ZIP 但跳过指定条目，用于制造「清单里有、容器里没有」的场景。 */
    private static void copyWithout(Path source, Path target, String excluded) throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, byte[]> data = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || excluded.equals(entry.getName())) {
                    continue;
                }
                names.add(entry.getName());
                try (var in = zip.getInputStream(entry)) {
                    data.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String name : names) {
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(entry);
                zip.write(data.get(name));
                zip.closeEntry();
            }
        }
    }
}
