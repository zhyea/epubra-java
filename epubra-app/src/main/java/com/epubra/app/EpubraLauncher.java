package com.epubra.app;

import com.epubra.app.support.PlatformLogging;

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
 *
 * <p>本方法同时做启动期日志降噪：非模块化运行方式必然触发 JavaFX 的
 * “Unsupported JavaFX configuration” 告警，用 {@link PlatformLogging} 精准压掉这一条。
 */
public final class EpubraLauncher {

    private EpubraLauncher() {
    }

    /**
     * JVM 入口。
     *
     * <p>必须是 {@code public}：JVM 启动器与 jpackage 生成的启动器都只认
     * {@code public static void main(String[])}，写成包私有会直接报
     * “Main method not found in class”。
     */
    public static void main(String[] args) {
        PlatformLogging.quietJavaFx();
        EpubraApp.main(args);
    }
}
