package org.chobit.epubra.app.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具条图标表的渲染自检。
 *
 * <h2>为什么需要这条守卫</h2>
 * <p>「在 {@code PATHS} 里登记过」不等于「画得出来」：路径串写错时 JavaFX 只是静默画一个
 * <b>空图形</b>——不抛异常、不进日志，界面上就是一枚空白按钮，门禁全绿也发现不了。
 * 所以这里把每枚图标真的造一遍、量一遍它的 {@code layoutBounds}：空图形的 bounds 是 0×0，
 * 坐标写飞的会超出一整个 24×24 视口。
 *
 * <p><b>必须挂着真实样式表量</b>：这套图标是描边风格（{@code -fx-fill} 由代码置为 null，
 * 颜色走 {@code .toolbar-icon} 的 {@code -fx-stroke}）。不挂 app.css 时既没有填充也没有描边，
 * 「分隔线」这种只剩一根线的路径量出来是空的——那不是路径写错，是量错了环境。
 *
 * <p>顺带把整张图标表按 3 倍缩放快照成 {@code target/toolbar-icons.png}
 * （工程内的快照探针口径，见 {@code EditorScrollbarWidthTest}）：改图标形状时打开它肉眼比对，
 * 比在 17px 的界面上盯着看容易得多（<b>不要</b>引 {@code javafx-swing} 的 SwingFXUtils，
 * 逐像素搬到 AWT 的 BufferedImage 即可）。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code StatusProgressUiTest} 的说明——
 * 这里同样<b>不调</b> {@code Platform.exit()}。
 */
class ToolbarIconsRenderTest {

    /** 快照落盘位置（相对模块目录，surefire 的工作目录就是模块根）。 */
    private static final Path SHEET = Path.of("target", "toolbar-icons.png");

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    /** 24×24 视口按 0.72 缩放 ≈ 17.3px；留一点余量给描边与取整。 */
    private static final double MAX_ICON_EXTENT = 19.0;

    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);
    }

    @Test
    @Timeout(60)
    @DisplayName("每枚登记过的图标都真的画得出东西（空图形 = 界面上的一枚空白按钮）")
    void everyRegisteredIconHasRealGeometry() throws Exception {
        AtomicReference<Bounds[]> measured = new AtomicReference<>();
        AtomicReference<java.util.List<String>> ids = new AtomicReference<>();

        runOnFx(() -> {
            FlowPane sheet = buildSheet();
            Stage stage = show(sheet);
            try {
                java.util.List<String> collected = new java.util.ArrayList<>();
                java.util.List<Bounds> boxes = new java.util.ArrayList<>();
                for (Node cell : sheet.getChildren()) {
                    collected.add(caption(cell));
                    boxes.add(iconOf(cell).getLayoutBounds());
                }
                ids.set(collected);
                measured.set(boxes.toArray(new Bounds[0]));
            } finally {
                stage.close();
            }
        });

        assertFalse(ids.get().isEmpty(), "图标表不该是空的");
        for (int i = 0; i < ids.get().size(); i++) {
            String id = ids.get().get(i);
            Bounds box = measured.get()[i];
            // 「分隔线」是一根水平线（高 0），所以宽高只要有一个成立即可
            assertTrue(box.getWidth() >= 3 || box.getHeight() >= 3,
                    "图标 " + id + " 画出来是空的（路径串写错时 JavaFX 不报错、只画空）——实际 "
                            + box.getWidth() + "×" + box.getHeight());
            assertTrue(box.getWidth() <= MAX_ICON_EXTENT && box.getHeight() <= MAX_ICON_EXTENT,
                    "图标 " + id + " 超出一整个 24×24 视口（坐标写飞了）——实际 "
                            + box.getWidth() + "×" + box.getHeight());
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("每个图形 id 要么有中文提示文案，要么是某个已登记文案的图形变体")
    void labeledIconsCarryHumanReadableText() {
        // 不能写成两张表互含：PATHS 里有 align-center / align-right（「对齐」按钮按当前
        // 生效值换用的图形变体，提示文案取基名 align），LABELS 里有 color（ColorPicker
        // 的提示，本来就没有图形路径）。真正要守的是**每个图形 id 都得有文案可挂**。
        for (String id : ToolbarIcons.registeredIds()) {
            if (ToolbarIcons.labeledIds().contains(id)) {
                continue;
            }
            assertTrue(isGraphicVariantOf(id),
                    "图形 id " + id + " 没有中文提示文案——install() 会给它挂一个文案等于 id 的"
                            + "Tooltip（用户看到「" + ToolbarIcons.tooltip(id).getText() + "」）");
        }
        for (String id : ToolbarIcons.labeledIds()) {
            assertNotEquals(id, ToolbarIcons.tooltip(id).getText(),
                    "id " + id + " 缺中文提示文案——图标按钮只能靠 Tooltip 表意");
        }
    }

    /** {@code align-center} 是 {@code align} 的图形变体：图形另有几张，文案沿用基名那张。 */
    private static boolean isGraphicVariantOf(String id) {
        for (String base : ToolbarIcons.labeledIds()) {
            if (id.startsWith(base + "-") && ToolbarIcons.registeredIds().contains(base)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Timeout(120)
    @DisplayName("整张图标表按 3 倍缩放落成 target/toolbar-icons.png，供肉眼比对形状")
    void iconSheetIsDumpedForVisualReview() throws Exception {
        AtomicReference<WritableImage> shot = new AtomicReference<>();

        runOnFx(() -> {
            FlowPane sheet = buildSheet();
            Stage stage = show(sheet);
            try {
                SnapshotParameters params = new SnapshotParameters();
                params.setTransform(new Scale(3, 3));
                shot.set(sheet.snapshot(params, null));
            } finally {
                stage.close();
            }
        });

        WritableImage image = shot.get();
        assertNotNull(image, "快照没有产出图像");
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "快照尺寸异常");

        // WritableImage 不能直接交给 ImageIO（那要 SwingFXUtils，工程里没引 javafx-swing），
        // 逐像素搬到 AWT 的 BufferedImage 上；快照是透明底，先合成到白底再写文件
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                int argb = reader.getArgb(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int keep = 255 - alpha;
                out.setRGB(x, y, (((argb >> 16) & 0xFF) * alpha + 255 * keep) / 255 << 16
                        | (((argb >> 8) & 0xFF) * alpha + 255 * keep) / 255 << 8
                        | ((argb & 0xFF) * alpha + 255 * keep) / 255);
            }
        }
        Files.createDirectories(SHEET.getParent());
        assertTrue(ImageIO.write(out, "png", SHEET.toFile()), "PNG 落盘失败：" + SHEET.toAbsolutePath());
    }

    // ---- 脚手架（以下都在 FX 线程上调用） ----------------------------

    /** 每个 id 一格的图标总览：带 id 说明文字，便于肉眼比对时对号入座。 */
    private static FlowPane buildSheet() {
        FlowPane sheet = new FlowPane(12, 8);
        sheet.setStyle("-fx-background-color: white; -fx-padding: 12;");
        for (String id : ToolbarIcons.registeredIds()) {
            VBox cell = new VBox(4, ToolbarIcons.graphicFor(id), new Label(id));
            cell.setAlignment(Pos.CENTER);
            sheet.getChildren().add(cell);
        }
        return sheet;
    }

    /** 把总览挂进真实 Scene（含 app.css）并跑一次 CSS + 布局脉冲，描边才算得出来。 */
    private static Stage show(FlowPane sheet) {
        Scene scene = new Scene(sheet);
        scene.getStylesheets().add(
                ToolbarIconsRenderTest.class.getResource("/org/chobit/epubra/app/css/app.css")
                        .toExternalForm());
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        sheet.applyCss();
        sheet.layout();
        return stage;
    }

    private static Node iconOf(Node cell) {
        return ((VBox) cell).getChildren().get(0);
    }

    private static String caption(Node cell) {
        return ((Label) ((VBox) cell).getChildren().get(1)).getText();
    }

    private static void runOnFx(FxTask task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void run();
    }
}
