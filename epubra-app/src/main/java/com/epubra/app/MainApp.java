package com.epubra.app;

import com.epubra.app.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Epubra 应用入口。
 */
public class MainApp extends Application {

    public static final String APP_NAME = "Epubra";

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/com/epubra/app/view/main-window.fxml"));
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
