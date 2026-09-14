package org.chobit.epubra.app;

import org.chobit.epubra.app.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Epubra 应用入口。
 *
 * <p>由 {@link EpubraLauncher} 间接调用，避免以 {@code Application} 子类直接做 main-class
 * 触发非模块化构建下的运行时组件校验失败。详见 {@code .workbuddy/memory/MEMORY.md} 硬约束段。
 */
public class EpubraApp extends Application {

    public static final String APP_NAME = "Epubra";

    /**
     * 参与打包的图标尺寸。
     *
     * <p>与 {@code epubra-app/tools/make-icon.py} 的 {@code SIZES} 一一对应——新增尺寸要两边一起改，
     * {@code AppIconTest} 会盯住两边是否同步。
     */
    private static final List<Integer> ICON_SIZES = List.of(16, 32, 48, 64, 128, 256, 512);

    private static final String ICON_DIR = "/org/chobit/epubra/app/icon/";

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(EpubraApp.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
        Parent root = loader.load();

        MainController controller = loader.getController();
        controller.setStage(stage);

        Scene scene = new Scene(root, 1240, 780);
        stage.setTitle(APP_NAME + " - EPUB 编辑器");
        stage.getIcons().addAll(loadIcons());
        stage.setScene(scene);
        // 标题栏 X / Alt+F4 关闭请求：先把「已排定但还没到点」的自动暂存补写一次，
        // 再释放总线订阅等资源，然后放行默认关闭。菜单「退出」（MainController.onExit）
        // 走 stage.close()，不一定经过这里，那一路由 onExit 自行处理（它已经问过用户
        // 「是否丢弃修改」，因此刻意不做补写）。
        stage.setOnCloseRequest(e -> controller.onWindowCloseRequest());
        stage.show();
    }

    /**
     * 读取全部尺寸的应用图标。
     *
     * <p>一次把各尺寸都交出去、不挑「最大的那张」：{@code getIcons()} 是候选列表，窗口系统会按
     * 用途（16 任务栏 / 32 Alt+Tab / 256 大图标视图）与当前 DPI 自己挑最接近的。只塞一张 256 的话，
     * 任务栏拿到的就是硬缩下来的糊图。
     *
     * <p>包内可见是刻意的：让 {@code AppIconTest} 能直接校验。图标漏打包属于「构建产物不完整」，
     * 必须在门禁里挡住。单个文件读不到时跳过而不抛——图标有问题不该拦住应用启动。
     */
    static List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            String path = ICON_DIR + "epubra-" + size + ".png";
            try (InputStream in = EpubraApp.class.getResourceAsStream(path)) {
                if (in != null) {
                    icons.add(new Image(in));
                }
            } catch (IOException e) {
                System.getLogger(EpubraApp.class.getName())
                        .log(System.Logger.Level.WARNING, "读取应用图标失败：" + path, e);
            }
        }
        return icons;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
