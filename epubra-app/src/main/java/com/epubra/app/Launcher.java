package com.epubra.app;

/**
 * 启动引导类。
 *
 * <p>本项目为非模块化构建（无 module-info），JavaFX 只出现在 classpath。此时若以
 * {@code Application} 子类直接作为 main-class，JVM 在启动阶段会先做 JavaFX 运行时组件校验并直接失败：
 * “缺少 JavaFX 运行时组件，需要使用该组件来运行此应用程序”，导致命令行 {@code java -cp}、
 * 以及 jpackage / exe 打包产物都无法启动。
 *
 * <p>经由本类（非 Application 子类）间接调用即可绕开该校验，使同一份构建产物
 * 既能被 {@code javafx:run} 运行，也能被打包成可双击的桌面应用。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
