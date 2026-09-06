package org.chobit.epubra.lib.validation;

import org.chobit.epubra.lib.util.Xmls;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 EPUB 容器（ZIP）采集到的事实，不含任何判定。
 *
 * <p>这样 {@link ContainerRules} 只负责判定，单测里可以手搓 facts 而不必造真实 ZIP。
 *
 * @param file              被采集的文件
 * @param entryNames        ZIP 中全部条目名（含目录条目）
 * @param firstEntryName    物理第一个条目的名字（读本地文件头得出，非中央目录顺序）
 * @param mimetypePresent   {@code mimetype} 条目是否存在
 * @param mimetypeStored    压缩方式是否为 {@link ZipEntry#STORED}
 * @param mimetypeContent   去 BOM 后的原文；条目不存在时为 {@code null}
 * @param containerPresent  {@code META-INF/container.xml} 是否存在
 * @param rootfileFullPath  container.xml 中第一个非空 {@code rootfile/@full-path}；解析失败为 {@code null}
 * @param opf               原始 OPF 的解析结果
 */
public record ContainerFacts(
        Path file,
        Set<String> entryNames,
        String firstEntryName,
        boolean mimetypePresent,
        boolean mimetypeStored,
        String mimetypeContent,
        boolean containerPresent,
        String rootfileFullPath,
        RawOpf opf
) {

    private static final String MIMETYPE_ENTRY = "mimetype";
    private static final String CONTAINER_ENTRY = "META-INF/container.xml";

    /** 原始 OPF manifest 中的一个条目。 */
    public record ManifestItem(String id, String href, String mediaType) {
    }

    /**
     * 原始 OPF 的解析结果。
     *
     * @param present             OPF 条目是否存在于容器中
     * @param parsed              OPF 是否成功解析为 DOM
     * @param version             {@code <package>@version} 的字面值
     * @param uniqueIdentifierId  {@code <package>@unique-identifier} 的字面值
     * @param items               manifest 明细
     */
    public record RawOpf(boolean present, boolean parsed, String version,
                         String uniqueIdentifierId, List<ManifestItem> items) {

        public static final RawOpf ABSENT = new RawOpf(false, false, null, null, List.of());
    }

    /** 条目是否存在于容器中。 */
    public boolean hasEntry(String name) {
        return name != null && entryNames.contains(name);
    }

    /**
     * 采集容器事实。
     *
     * @throws IOException 文件不存在、不是合法 ZIP，或读取失败
     */
    public static ContainerFacts of(Path file) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        boolean mimetypePresent = false;
        boolean mimetypeStored = false;
        String mimetypeContent = null;
        boolean containerPresent = false;
        String rootfileFullPath = null;
        byte[] containerBytes = null;
        byte[] opfBytes = null;

        try (ZipFile zip = new ZipFile(file.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                names.add(entry.getName());
            }

            ZipEntry mimetypeEntry = zip.getEntry(MIMETYPE_ENTRY);
            mimetypePresent = mimetypeEntry != null && !mimetypeEntry.isDirectory();
            if (mimetypePresent) {
                mimetypeStored = mimetypeEntry.getMethod() == ZipEntry.STORED;
                byte[] raw = read(zip, mimetypeEntry);
                mimetypeContent = stripBom(raw);
            }

            ZipEntry containerEntry = zip.getEntry(CONTAINER_ENTRY);
            containerPresent = containerEntry != null && !containerEntry.isDirectory();
            if (containerPresent) {
                containerBytes = read(zip, containerEntry);
            }

            rootfileFullPath = containerBytes == null ? null : parseRootfile(containerBytes);
            if (rootfileFullPath != null && names.contains(rootfileFullPath)) {
                ZipEntry opfEntry = zip.getEntry(rootfileFullPath);
                if (opfEntry != null && !opfEntry.isDirectory()) {
                    opfBytes = read(zip, opfEntry);
                }
            }
        }

        RawOpf opf = parseOpf(rootfileFullPath, rootfileFullPath != null && names.contains(rootfileFullPath), opfBytes);
        return new ContainerFacts(file, Set.copyOf(names), readFirstEntryName(file),
                mimetypePresent, mimetypeStored, mimetypeContent,
                containerPresent, rootfileFullPath, opf);
    }

    /**
     * 读文件前 30 字节的本地文件头，取出第一个条目名。
     *
     * <p>{@link ZipFile#entries()} 返回的是中央目录顺序，可能与物理顺序不同，
     * 因此判「mimetype 是否在首位」必须读本地文件头。
     *
     * @return 第一个条目的名字；非 ZIP 或读取不到时返回 {@code null}
     */
    public static String readFirstEntryName(Path file) throws IOException {
        byte[] header = new byte[30];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(header, 0, header.length) < header.length) {
                return null;
            }
        }
        if (!(header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4)) {
            return null;
        }
        int nameLength = (header[26] & 0xFF) | ((header[27] & 0xFF) << 8);
        if (nameLength <= 0) {
            return null;
        }
        byte[] nameBytes = new byte[nameLength];
        try (InputStream in = Files.newInputStream(file)) {
            // skipNBytes 在跳不够时会抛 EOFException，直接视为读不到首个条目
            in.skipNBytes(30);
            if (in.readNBytes(nameBytes, 0, nameLength) != nameLength) {
                return null;
            }
        } catch (java.io.EOFException e) {
            return null;
        }
        return new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 内部

    private static String parseRootfile(byte[] containerBytes) {
        try {
            Document document = Xmls.parse(containerBytes);
            for (Element rootfile : Xmls.descendants(document.getDocumentElement(), "rootfile")) {
                String fullPath = rootfile.getAttribute("full-path");
                if (fullPath != null && !fullPath.isBlank()) {
                    return fullPath.replace('\\', '/').trim();
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static RawOpf parseOpf(String opfPath, boolean present, byte[] opfBytes) {
        if (opfPath == null || !present || opfBytes == null || opfBytes.length == 0) {
            return new RawOpf(present, false, null, null, List.of());
        }
        try {
            Document document = Xmls.parse(opfBytes);
            Element packageElement = document.getDocumentElement();
            String version = packageElement.getAttribute("version");
            String uniqueIdentifierId = packageElement.getAttribute("unique-identifier");

            List<ManifestItem> items = new ArrayList<>();
            Element manifestElement = Xmls.child(packageElement, "manifest");
            if (manifestElement != null) {
                for (Element item : Xmls.children(manifestElement, "item")) {
                    items.add(new ManifestItem(item.getAttribute("id"),
                            item.getAttribute("href"),
                            item.getAttribute("media-type")));
                }
            }
            return new RawOpf(true, true,
                    version == null || version.isBlank() ? null : version.trim(),
                    uniqueIdentifierId == null || uniqueIdentifierId.isBlank() ? null : uniqueIdentifierId.trim(),
                    List.copyOf(items));
        } catch (RuntimeException e) {
            return new RawOpf(present, false, null, null, List.of());
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String stripBom(byte[] raw) {
        if (raw == null) {
            return null;
        }
        int offset = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(raw, offset, raw.length - offset, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
