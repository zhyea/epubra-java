package org.chobit.epubra.app.support;

import org.chobit.epubra.lib.domain.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectLayout} 工具类契约测试。
 *
 * <p>覆盖路径推导、目录创建、项目标记读写、推断项目目录等纯 IO 行为。
 */
class ProjectLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void pathDerivation_allFour() {
        Path ws = Path.of("D:/Books");
        String name = "MyBook";
        assertEquals(Path.of("D:/Books/MyBook"), ProjectLayout.projectDir(ws, name));
        assertEquals(Path.of("D:/Books/MyBook/MyBook.epub"), ProjectLayout.epubFile(ws, name));
        assertEquals(Path.of("D:/Books/MyBook/.epubra"), ProjectLayout.metadataDir(ws, name));
        assertEquals(Path.of("D:/Books/MyBook/.epubra/project.json"),
                ProjectLayout.projectMarker(ws, name));
    }

    @Test
    void pathDerivation_rejectsNullOrBlank() {
        Path ws = tempDir;
        assertThrows(IllegalArgumentException.class,
                () -> ProjectLayout.projectDir(null, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectLayout.projectDir(ws, null));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectLayout.projectDir(ws, "  "));
    }

    @Test
    void createScaffolding_producesDirMetadataDirAndMarker() throws IOException {
        ProjectLayout.createProjectScaffolding(tempDir, "Alpha");

        Path dir = tempDir.resolve("Alpha");
        assertTrue(Files.isDirectory(dir));
        assertTrue(Files.isDirectory(dir.resolve(".epubra")));
        Path marker = dir.resolve(".epubra/project.json");
        assertTrue(Files.exists(marker));
        String json = Files.readString(marker);
        assertTrue(json.contains("\"formatVersion\": 1"));
        assertTrue(json.contains("\"name\": \"Alpha\""));
        assertTrue(json.contains("\"bookFile\": \"Alpha.epub\""));
    }

    @Test
    void createInitialEpub_writesValidFileAndBindsSource() throws IOException {
        Book book = ProjectLayout.createInitialEpub(tempDir, "Beta", "测试标题");

        Path target = tempDir.resolve("Beta/Beta.epub");
        assertTrue(Files.exists(target));
        assertEquals(target, book.source());
        assertEquals("测试标题", book.metadata().firstTitle());
        // EPUB 3 标准属性：项目名
        assertEquals("Beta", book.metadata().property("epubra:project-name"));
    }

    @Test
    void isProjectDir_recognisesScaffoldedDir() throws IOException {
        ProjectLayout.createProjectScaffolding(tempDir, "Gamma");
        Path dir = tempDir.resolve("Gamma");
        assertTrue(ProjectLayout.isProjectDir(dir));
    }

    @Test
    void isProjectDir_rejectsForeignDir() {
        // 普通目录无 marker
        assertFalse(ProjectLayout.isProjectDir(tempDir.resolve("plain-dir")));
        // null / 不存在
        assertFalse(ProjectLayout.isProjectDir(null));
        assertFalse(ProjectLayout.isProjectDir(tempDir.resolve("does-not-exist")));
    }

    @Test
    void inferProjectDir_findsProjectFromAnyFileInside() throws IOException {
        // 先创建完整项目结构（项目目录 + .epubra/ + marker）
        ProjectLayout.createProjectScaffolding(tempDir, "Delta");
        // 再写 EPUB 主文件（createInitialEpub 不建 marker）
        ProjectLayout.createInitialEpub(tempDir, "Delta", "Delta");

        Path epub = tempDir.resolve("Delta/Delta.epub");
        Path meta = tempDir.resolve("Delta/.epubra/project.json");
        Path dir = tempDir.resolve("Delta");

        assertEquals(dir, ProjectLayout.inferProjectDir(epub));
        assertEquals(dir, ProjectLayout.inferProjectDir(meta));   // marker 文件本身也应识别
        assertEquals(dir, ProjectLayout.inferProjectDir(dir));    // 目录本身
    }

    @Test
    void inferProjectDir_returnsNullForForeign() {
        assertNull(ProjectLayout.inferProjectDir(tempDir.resolve("standalone.epub")));
        assertNull(ProjectLayout.inferProjectDir(null));
    }

    @Test
    void readLastOpenedAt_returnsParsedInstant() throws IOException {
        ProjectLayout.createProjectScaffolding(tempDir, "Epsilon");
        Instant parsed = ProjectLayout.readLastOpenedAt(tempDir.resolve("Epsilon"));
        assertNotNull(parsed);
        // 与当前时间差不超过 60s
        long deltaSeconds = Math.abs(parsed.getEpochSecond() - Instant.now().getEpochSecond());
        assertTrue(deltaSeconds < 60, "lastOpenedAt should be near-now");
    }

    @Test
    void readLastOpenedAt_returnsNullForForeignOrNull() {
        assertNull(ProjectLayout.readLastOpenedAt(null));
        assertNull(ProjectLayout.readLastOpenedAt(tempDir.resolve("nope")));
    }

    @Test
    void touchLastOpened_updatesTimestamp() throws IOException, InterruptedException {
        ProjectLayout.createProjectScaffolding(tempDir, "Zeta");
        Path dir = tempDir.resolve("Zeta");
        Instant first = ProjectLayout.readLastOpenedAt(dir);
        assertNotNull(first);

        // sleep 50ms 让时间戳肯定能分辨
        Thread.sleep(50);
        ProjectLayout.touchLastOpened(dir);
        Instant second = ProjectLayout.readLastOpenedAt(dir);
        assertNotNull(second);
        assertTrue(second.isAfter(first),
                "touchLastOpened should advance timestamp: first=" + first + " second=" + second);
    }
}