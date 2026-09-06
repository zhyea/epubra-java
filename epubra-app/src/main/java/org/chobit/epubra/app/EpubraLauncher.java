package org.chobit.epubra.app;

import org.chobit.epubra.app.support.AppPaths;
import org.chobit.epubra.app.support.PlatformLogging;

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
 * <p>本方法同时做两件启动期副作用：
 * <ol>
 *   <li>{@link AppPaths#redirectUserHome()}：把进程 {@code user.home} 重写到
 *       {@code ~/.Epubra/}，让 JavaFX WebView 等 native 缓存不再落到
 *       {@code ~/.org.chobit.epubra.app.EpubraApp/webview} 这种长名目录。</li>
 *   <li>{@link PlatformLogging#quietJavaFx()}：非模块化运行方式必然触发 JavaFX 的
 *       “Unsupported JavaFX configuration” 告警，精准压掉这一条。</li>
 * </ol>
 *
 * <p>顺序：重定向必须先于日志降噪与 JavaFX 启动——日志降噪安装 JUL Handler，
 * JavaFX 启动后任何 prefs / native 缓存都已根据当时的 {@code user.home} 决定位置。
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
        AppPaths.redirectUserHome();
        PlatformLogging.quietJavaFx();
        EpubraApp.main(args);
    }
}
