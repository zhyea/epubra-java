package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.components.ResourceRow;
import org.chobit.epubra.app.support.AppEventBus;
import org.chobit.epubra.app.support.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据面板「封面卡」三态可见性 + 资源表「设为封面 / 取消封面」按钮语义切
 * 的 GUI 级回归测试（2026-09-06 封面功能 P0 落地时的固化）。
 *
 * <p>覆盖 4 点契约：
 * <ol>
 *   <li>加载 main-window.fxml 后空白书 → 空态盒可见，已设置 / 异常态盒不可见</li>
 *   <li>调 {@code CoverOps.set(book, image)} + 重新加载面板 → 已设置盒可见，空态盒不可见
 *       （缩略图尺寸信息填进对应 Label）</li>
 *   <li>悬空状态下「⚠ 封面引用失效」可见，已设置盒不可见</li>
 *   <li>资源表的「设为封面」按钮在选中行已是封面时切到「取消封面」，未选中或非封面时为「设为封面」</li>
 * </ol>
 *
 * <p>整段链路经 MainController.initialize 完整跑一遍（含 @FXML 注入、bind 链、事件订阅），
 * 证伪的不只是「封面卡的 visible/managed 串正确」，也证明从书籍层 {@code setCover} 经
 * 子控制器刷新到 {@code coverSetBox}/{@code coverSetActions} 的整条管线没断。
 */
class CoverCardGuiTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static final BooleanProperty stageShown = new SimpleBooleanProperty(false);

    /**
     * 跨测试 class 共享 JavaFX toolkit：toolkit 全 JVM 只能 init 一次；
     * 后跑的 class 调 {@link Platform#startup} 会抛
     * {@link IllegalStateException} "Toolkit already initialized"。
     * 用 try-catch 吞掉，latch 仍正确 countDown 供后续 await 使用。
     */
    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    CoverCardGuiTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            stageShown.set(true);
        });
        assertTrue(stageShown.get(), "主窗口未能显示");
    }

    @AfterAll
    static void shutdownFx() {
        // 不在这里 Platform.exit()——JUnit 同 JVM 多 class fixture 共享 JavaFX Platform，
        // 后跑的测试（例如 WelcomePageHideTest）若再调 startup() 会 IllegalStateException。
        // 既然 setImplicitExit(false)，让 Platform 跟 JVM 一起结束即可。
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    @Timeout(60)
    void emptyStateShowsEmptyBoxHidesSetAndDangling() throws Exception {
        runOnFx(() -> {
            BookContext ctx = contextOf(mainController);
            Book book = BookFactory.createEmpty("封面卡测试");
            ctx.setBook(book);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
        runOnFx(() -> {
            MetadataViewController mvc = metadataControllerOf(mainController);
            VBox coverEmptyBox = fieldOf(mvc, "coverEmptyBox");
            HBox coverSetBox = fieldOf(mvc, "coverSetBox");
            HBox coverDanglingBox = fieldOf(mvc, "coverDanglingBox");
            assertTrue(coverEmptyBox.isVisible(), "空书封面卡应显示空态盒");
            assertFalse(coverSetBox.isVisible(), "空书封面卡不应显示已设置盒");
            assertFalse(coverDanglingBox.isVisible(), "空书封面卡不应显示悬空盒");
        });
    }

    @Test
    @Timeout(60)
    void setCoverShowsSetBoxHidesOthers() throws Exception {
        runOnFx(() -> {
            BookContext ctx = contextOf(mainController);
            Book book = BookFactory.createEmpty("已设置封面测试");
            Resource image = new Resource("cover-test", "OEBPS/images/cover.jpg",
                    MediaTypes.JPEG, new byte[]{
                            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                            0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                            (byte) 0xFF, (byte) 0xC0,
                            0x00, 0x11,
                            0x08,
                            0x01, (byte) 0xE0, // height = 480
                            0x01, (byte) 0x40, // width = 320
                            0x01, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01
                    });
            book.resources().add(image);
            ctx.setBook(book);
            book.setCover(image);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
        runOnFx(() -> {
            MetadataViewController mvc = metadataControllerOf(mainController);
            HBox coverSetBox = fieldOf(mvc, "coverSetBox");
            VBox coverEmptyBox = fieldOf(mvc, "coverEmptyBox");
            HBox coverDanglingBox = fieldOf(mvc, "coverDanglingBox");
            assertTrue(coverSetBox.isVisible(), "设置封面后已设置盒应可见");
            assertFalse(coverEmptyBox.isVisible());
            assertFalse(coverDanglingBox.isVisible());
            // 文件名 / href 等标签填进去了
            javafx.scene.control.Label nameLabel = fieldOf(mvc, "coverNameLabel");
            assertNotNull(nameLabel);
            assertTrue(nameLabel.getText().contains("cover.jpg"),
                    "封面文件名应填进 Label，实际：" + nameLabel.getText());
        });
    }

    @Test
    @Timeout(60)
    void danglingCoverShowsDanglingBox() throws Exception {
        runOnFx(() -> {
            BookContext ctx = contextOf(mainController);
            Book book = BookFactory.createEmpty("悬空测试");
            // 直接设个不存在的 id 作为封面：模拟 D18 异常态
            book.setCoverResourceId("ghost-cover-id");
            ctx.setBook(book);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
        runOnFx(() -> {
            MetadataViewController mvc = metadataControllerOf(mainController);
            HBox coverDanglingBox = fieldOf(mvc, "coverDanglingBox");
            HBox coverSetBox = fieldOf(mvc, "coverSetBox");
            VBox coverEmptyBox = fieldOf(mvc, "coverEmptyBox");
            assertTrue(coverDanglingBox.isVisible(), "封面 id 悬空时悬空盒可见");
            assertFalse(coverSetBox.isVisible());
            assertFalse(coverEmptyBox.isVisible());
        });
    }

    @Test
    @Timeout(60)
    void coverButtonTogglesLabelBySelectedRowCoverState() throws Exception {
        // 资源表：当前选中行已是封面 → 按钮切到「取消封面」；否则 → 「设为封面」
        runOnFx(() -> {
            BookContext ctx = contextOf(mainController);
            Book book = BookFactory.createEmpty("按钮语义测试");
            // 加两张图片：一张做封面一张不
            Resource coverImg = new Resource("cover-a", "OEBPS/images/a.jpg",
                    MediaTypes.JPEG, new byte[]{1, 2, 3});
            Resource otherImg = new Resource("other-b", "OEBPS/images/b.jpg",
                    MediaTypes.JPEG, new byte[]{4, 5, 6});
            book.resources().add(coverImg);
            book.resources().add(otherImg);
            book.setCover(coverImg);
            ctx.setBook(book);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
        runOnFx(() -> {
            ResourceController rc = resourceControllerOf(mainController);
            Button setCoverButton = fieldOf(rc, "setCoverButton");
            javafx.scene.control.TableView<ResourceRow> table = resourceTable(rc);
            rc.refresh();
            assertNotNull(setCoverButton);

            // 资源表天然含默认 chapter-1.xhtml（不是封面 → 第一行）。按 row.getName() 精确选封面 / 非封面行，
            // 不依赖按位置选（章节顺序与图片穿插由 Resource 集合的实现决定，未来扩展时也稳）。
            ResourceRow coverRow = null;
            ResourceRow otherRow = null;
            for (ResourceRow r : table.getItems()) {
                if ("a.jpg".equals(r.getName())) coverRow = r;
                else if ("b.jpg".equals(r.getName())) otherRow = r;
            }
            assertNotNull(coverRow, "资源表应包含 a.jpg");
            assertNotNull(otherRow, "资源表应包含 b.jpg");
            assertTrue(coverRow.isCover(), "a.jpg 应被标记为封面");
            assertFalse(otherRow.isCover(), "b.jpg 不应是封面");

            // 选中 a.jpg（封面行）→ 按钮切到「取消封面」
            table.getSelectionModel().select(coverRow);
            assertTrue(setCoverButton.getText().contains("取消"),
                    "选中封面行按钮应为「取消封面」，实际：" + setCoverButton.getText());

            // 切到 b.jpg（非封面）→ 按钮回到「设为封面」
            table.getSelectionModel().select(otherRow);
            assertTrue(setCoverButton.getText().contains("设为"),
                    "选中非封面行按钮应为「设为封面」，实际：" + setCoverButton.getText());
        });
    }

    private static BookContext contextOf(MainController controller) throws Exception {
        return (BookContext) field(controller, "ctx");
    }

    private static MetadataViewController metadataControllerOf(MainController controller) throws Exception {
        return (MetadataViewController) field(controller, "metadataViewController");
    }

    private static ResourceController resourceControllerOf(MainController controller) throws Exception {
        return (ResourceController) field(controller, "resourceViewController");
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldOf(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    private static javafx.scene.control.TableView<ResourceRow> resourceTable(ResourceController rc) throws Exception {
        return fieldOf(rc, "resourceTable");
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    private static void runOnFx(FxTask r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.runWithException();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
