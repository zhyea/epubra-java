package org.chobit.epubra.app;

import org.chobit.epubra.app.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Epubra 应用入口。
 *
 * <p>由 {@link EpubraLauncher} 间接调用，避免以 {@code Application} 子类直接做 main-class
 * 触发非模块化构建下的运行时组件校验失败。详见 {@code .workbuddy/memory/MEMORY.md} 硬约束段。
 */
public class EpubraApp extends Application {

    public static final String APP_NAME = "Epubra";

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(EpubraApp.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
        Parent root = loader.load();

        MainController controller = loader.getController();
        controller.setStage(stage);

        Scene scene = new Scene(root, 1240, 780);
        stage.setTitle(APP_NAME + " - EPUB 编辑器");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}