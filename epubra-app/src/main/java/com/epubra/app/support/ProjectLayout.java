package com.epubra.app.support;

import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.BookFactory;
import com.epubra.epublib.domain.Metadata;
import com.epubra.epublib.io.EpubWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

/**
 * EPUB 项目目录布局工具类。
 *
 * <h2>目录约定</h2>
 * <pre>
 *   {workspace}/
 *     {projectName}/
 *       {projectName}.epub        ← 主文件
 *       .epubra/
 *         project.json            ← 项目标记（formatVersion / createdAt / lastOpenedAt / bookFile）
 *         drafts/                 ← （预留）项目级 autosave，目前暂未启用
 * </pre>
 *
 * <p>{@code .epubra/} 目录 + {@code project.json} 是「本目录是 Epubra 项目」的标记。
 * 欢迎页据此区分「项目目录」与「任意目录里的散落 .epub 文件」。
 *
 * <p>所有方法为纯静态，方便单元测试与跨控制器复用。涉及磁盘 IO 的失败一律抛
 * {@link IOException}——创建项目是显式动作，不应静默失败。
 */
public final class ProjectLayout {

    /** 项目元数据目录名。 */
    public static final String METADATA_DIR = ".epubra";

    /** 项目标记文件名。 */
    public static final String PROJECT_MARKER = "project.json";

    /** 项目标记的格式版本。 */
    public static final int FORMAT_VERSION = 1;

    private ProjectLayout() {
    }

    // ---- 路径推导 ----

    /**
     * 项目目录路径：{@code <workspace>/<name>}。
     *
     * @throws IllegalArgumentException workspace / name 为 null 或空
     */
    public static Path projectDir(Path workspace, String name) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name is empty");
        }
        return workspace.resolve(name);
    }

    /** EPUB 主文件路径：{@code <workspace>/<name>/<name>.epub}。 */
    public static Path epubFile(Path workspace, String name) {
        return projectDir(workspace, name).resolve(name + ".epub");
    }

    /** 项目元数据目录路径：{@code <workspace>/<name>/.epubra}。 */
    public static Path metadataDir(Path workspace, String name) {
        return projectDir(workspace, name).resolve(METADATA_DIR);
    }

    /** 项目标记文件路径：{@code <workspace>/<name>/.epubra/project.json}。 */
    public static Path projectMarker(Path workspace, String name) {
        return metadataDir(workspace, name).resolve(PROJECT_MARKER);
    }

    /**
     * 判断路径是否指向一个 Epubra 项目目录（同时具备项目目录 + 主文件 + 项目标记）。
     */
    public static boolean isProjectDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (!Files.exists(projectMarkerIn(dir))) {
            return false;
        }
        // 主文件存在与否不是硬性要求——空项目可能在等待用户首次保存。但惯例上我们
        // 在创建时就同时落地 epub 与 marker，所以两个都应存在；这里为容错放宽。
        return true;
    }

    private static Path projectMarkerIn(Path projectDir) {
        return projectDir.resolve(METADATA_DIR).resolve(PROJECT_MARKER);
    }

    /**
     * 推断给定路径所属的项目目录：若路径是 .epub 文件且其父目录含 {@code .epubra/project.json}，
     * 返回该父目录；否则返回 null。
     *
     * <p>用于「打开一个 .epub 单文件时判断是否同时打开其所在项目目录」。
     */
    public static Path inferProjectDir(Path anyPathInsideProject) {
        if (anyPathInsideProject == null) {
            return null;
        }
        // 文件 → 父目录
        Path candidate = Files.isRegularFile(anyPathInsideProject)
                ? anyPathInsideProject.getParent()
                : anyPathInsideProject;
        // 向上逐级查找首个含 .epubra/project.json 的祖先。
        // 必要性：输入可能是 marker 文件本身（位于 .epubra/），仅看一级父目录会把
        // .epubra/ 误判为项目目录；继续上溯一级会找到真正的项目目录。
        while (candidate != null) {
            if (Files.exists(candidate.resolve(METADATA_DIR).resolve(PROJECT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    // ---- 创建项目 ----

    /**
     * 创建完整项目目录结构：项目目录 + 元数据目录 + 项目标记。
     *
     * <p>主 EPUB 文件由 {@link #createInitialEpub(Path, Path, String)} 创建——本方法不写 .epub。
     * 拆成两步是为了让调用方在中间可以先校验或修改 Metadata。
     *
     * @throws IOException 目录创建或标记写入失败；项目目录已存在时不抛，由调用方决定
     */
    public static void createProjectScaffolding(Path workspace, String name) throws IOException {
        Path dir = projectDir(workspace, name);
        Files.createDirectories(dir);
        Files.createDirectories(metadataDir(workspace, name));
        writeProjectMarker(workspace, name);
    }

    /**
     * 创建初始 EPUB 文件：调用 {@link BookFactory#createEmpty(String)}，绑定 source 路径，
     * 写入 {@code <workspace>/<name>/<name>.epub}。
     *
     * @param title 项目标题（同时作为 metadata 的标题与默认首章名）
     * @return 创建后已写入磁盘的 Book 实例（source 已绑定，可直接编辑）
     */
    public static Book createInitialEpub(Path workspace, String name, String title) throws IOException {
        Book book = BookFactory.createEmpty(title == null || title.isBlank() ? name : title);
        book.metadata().setProperty("epubra:project-name", name);
        Path target = epubFile(workspace, name);
        new EpubWriter().write(book, target);
        book.setSource(target);
        return book;
    }

    /** 写入项目标记 JSON。结构简单，仅持久化基本元数据。 */
    public static void writeProjectMarker(Path workspace, String name) throws IOException {
        Path marker = projectMarker(workspace, name);
        String json = """
                {
                  "formatVersion": %d,
                  "name": %s,
                  "createdAt": %s,
                  "lastOpenedAt": %s,
                  "bookFile": %s
                }
                """.formatted(
                FORMAT_VERSION,
                jsonString(name),
                jsonString(Instant.now().toString()),
                jsonString(Instant.now().toString()),
                jsonString(name + ".epub"));
        Files.writeString(marker, json);
    }

    /**
     * 读取项目标记上次打开时间。若标记缺失或解析失败返回 null。
     *
     * <p>当前只关心 {@code lastOpenedAt}，其他字段留给将来 UI 显示「创建时间」等用。
     */
    public static Instant readLastOpenedAt(Path projectDirectory) {
        if (projectDirectory == null) {
            return null;
        }
        Path marker = projectMarkerIn(projectDirectory);
        if (!Files.exists(marker)) {
            return null;
        }
        try {
            String json = Files.readString(marker);
            return parseIsoField(json, "lastOpenedAt");
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 更新 {@code lastOpenedAt} 字段为当前时间。IO 失败静默——标记写不进去不影响打开主文件。
     */
    public static void touchLastOpened(Path projectDirectory) {
        if (projectDirectory == null) {
            return;
        }
        Path marker = projectMarkerIn(projectDirectory);
        if (!Files.exists(marker)) {
            return;
        }
        try {
            String json = Files.readString(marker);
            String updated = json.replaceAll(
                    "\"lastOpenedAt\"\\s*:\\s*\"[^\"]*\"",
                    "\"lastOpenedAt\": \"" + Instant.now().toString() + "\"");
            Files.writeString(marker, updated);
        } catch (IOException ignored) {
        }
    }

    // ---- 内部 ----

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static Instant parseIsoField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return Instant.parse(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 未使用占位——保留以备将来支持读取 ProjectMarker 全字段。 */
    @SuppressWarnings("unused")
    private static FileTime epoch() {
        return FileTime.from(Instant.EPOCH);
    }
}