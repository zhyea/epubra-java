package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @FXML} 注入完整性契约：<b>真加载一次主窗口，凡是标了 {@code @FXML} 的字段都必须被注入。</b>
 *
 * <p><b>为什么需要这个类</b>：2026-09-15 的菜单审计暴露过一个「静默到没人发现」的缺陷——
 * {@code find-bar.fxml} 的根 {@code <HBox>} 漏写 {@code fx:id}，于是子控制器
 * {@code FindController} 自己的 {@code @FXML Pane findBar} <b>恒为 null</b>：
 * {@code showBar()} / {@code closeBar()} 每次都在首行 {@code if (findBar == null) return;} 静默出去，
 * <b>Ctrl+F 与菜单「查找替换…」完全没反应、Esc 也关不掉</b>。
 *
 * <p>根因是 {@code fx:include} 的 {@code fx:id} <b>双份注入、各管各的</b>：
 * <ul>
 *   <li>父 FXML 里 {@code <fx:include fx:id="x">} 的 {@code fx:id} 只把<b>根节点</b>注入
 *       <b>父控制器</b>的 {@code x} 字段（并派生父控制器的 {@code xController}）；</li>
 *   <li><b>子控制器自己</b>的同名字段仍由<b>被引入 FXML 的根节点 {@code fx:id}</b> 注入——
 *       被引入文件漏写，那边就恒为 null。</li>
 * </ul>
 * 这个坑无法被「DOM 解析类」测试拦住（{@code InsertImageEntryWiringTest} 就是这么漏掉的），
 * 只有<b>真 FXMLLoader 加载</b>才能显形——所以本类沿用真加载 {@code main-window.fxml} 的做法。
 *
 * <p><b>覆盖范围</b>：从 {@link MainController} 出发，递归走进每一个「控制器类型」字段
 * （即 {@code org.chobit.epubra.app.controller} 包及其子包下的类），对整棵控制器树逐字段核验。
 * 所以新增一个 {@code fx:include} 面板却漏了任一端的 {@code fx:id}，本类会精确报出
 * {@code MainController.findBarController.findBar} 这样的绝对路径，而不是让人去猜。
 *
 * <p><b>为什么断言「非空」而不是「抛异常」</b>：把它做成运行期硬失败会让「某个字段本来就允许为空」
 * 的假设无处安放；放在测试里既能早失败，又不影响用户手上那份构建。这是刻意的边界，不是遗漏。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code HomeMenuVisibilityTest} 的说明。
 */
class FxmlInjectionIntegrityTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    /** 兜底下限：反射若因重构而「扫了个寂寞」（比如包名改了），下面两条会先红，而不是假绿。 */
    private static final int MIN_CONTROLLERS_SCANNED = 6;
    private static final int MIN_FXML_FIELDS_SCANNED = 80;

    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        // toolkit 全 JVM 只 init 一次；后跑的 class 调 Platform.startup 会 IllegalStateException，
        // 吞掉即可（latch 仍要 countDown 供后续 await）。
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    FxmlInjectionIntegrityTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @AfterAll
    static void hideStage() {
        // 不 Platform.exit()：多 class 共享 Platform，setImplicitExit(false) 下随 JVM 结束即可。
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("整棵控制器树上每个 @FXML 字段都被真注入（含 fx:include 子控制器自己的字段）")
    void everyFxmlFieldIsInjectedAcrossControllerTree() throws Exception {
        Scan scan = onFxGet(FxmlInjectionIntegrityTest::scan);

        assertTrue(scan.controllers() >= MIN_CONTROLLERS_SCANNED,
                "反射只扫到 " + scan.controllers() + " 个控制器（应至少 " + MIN_CONTROLLERS_SCANNED
                        + " 个）——控制器递归或包名判定可能已被重构破坏，这条是防「假绿」的兜底");
        assertTrue(scan.fxmlFields() >= MIN_FXML_FIELDS_SCANNED,
                "反射只扫到 " + scan.fxmlFields() + " 个 @FXML 字段（应至少 " + MIN_FXML_FIELDS_SCANNED
                        + " 个）——同上，防止检查空转");

        assertEquals(List.of(), scan.nulls(),
                "以下 @FXML 字段没有被注入（null）。最常见的原因是 fx:include 的「双份 fx:id」只写了"
                        + "一端——父 FXML 的 include 标签有 fx:id，被引入 FXML 的根节点也要有，否则"
                        + "子控制器自己的字段恒为 null，相关命令会静默失效（F1：Ctrl+F / 查找条）：\n  "
                        + String.join("\n  ", scan.nulls()));
    }

    // ------------------------------------------------------------------ 扫描实现

    /** 一次扫描的结果：走过的控制器数、核过的 @FXML 字段数、为空的字段绝对路径。 */
    private record Scan(int controllers, int fxmlFields, List<String> nulls) {
    }

    private static Scan scan() {
        List<String> nulls = new ArrayList<>();
        int[] counters = new int[2]; // [0]=controllers, [1]=fxmlFields
        // 恒等集合判重：控制器树理论上无环，但防御性挡住，避免重构出环时无限递归。
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(mainController, "MainController", nulls, counters, visited);
        return new Scan(counters[0], counters[1], nulls);
    }

    private static void walk(Object controller, String path, List<String> nulls,
                             int[] counters, Set<Object> visited) {
        if (controller == null || !visited.add(controller)) {
            return;
        }
        counters[0]++;
        for (Field field : controller.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(controller);
            } catch (IllegalAccessException e) {
                // setAccessible(true) 之后不该发生，真发生说明环境异常，别静默吞。
                throw new IllegalStateException("无法读取字段 " + path + "." + field.getName(), e);
            }
            if (field.getAnnotation(FXML.class) != null) {
                counters[1]++;
                if (value == null) {
                    nulls.add(path + "." + field.getName());
                }
            }
            // 是控制器就继续下钻（含 @FXML 注入的子控制器与手动持有的控制器引用）。
            if (value != null && isControllerType(field.getType())) {
                walk(value, path + "." + field.getName(), nulls, counters, visited);
            }
        }
    }

    /** 控制器 = 本应用 {@code controller} 包（含 {@code controller.view} 子包）下的类型。 */
    private static boolean isControllerType(Class<?> type) {
        return type.getPackageName().startsWith("org.chobit.epubra.app.controller");
    }

    private static void onFx(FxTask r) throws Exception {
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

    private static <T> T onFxGet(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        onFx(() -> out.set(c.call()));
        return out.get();
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
