# Epubra 项目交付概览

## 当前轮：用户数据目录统一到 ~/.Epubra/（Sprint 10，2026-09-05 晚）

用户指令：「放在 用户目录下的 临时数据目录最好 不使用 com.epubra.app.EpubraApp 这样的目录， 改为使用简单直接的 .Epubra」。

### 现象

JavaFX 24 WebView 在 Windows 上以 main class FQCN 派生 native 缓存目录，实测为：

```
C:\Users\robin\.com.epubra.app.EpubraApp\webview\.lock
C:\Users\robin\.com.epubra.app.EpubraApp\webview\localstorage\
```

丑且长，与 IDEA / VSCode / Git 等工具的「`.工具名/`」命名风格不一致。

### 交付内容

**1. AppPaths 工具类**（`com.epubra.app.support.AppPaths`）
- `userDataDir()` / `autosaveDir()` / `webviewCacheDir()`：统一路径入口
- `redirectUserHome()`：把 `user.home` 改写到 `~/.Epubra/`，让 JavaFX native 缓存跟随
- `migrateLegacyIfAny()`：一次性把旧 `<user.dir>/epubra-autosave` 与 `~/epubra-autosave` 下的 `.draft` 搬到新位置
- `resetForTesting()`：单元测试 hook

**2. EpubraLauncher 启动时序调整**
- `AppPaths.redirectUserHome()` 必须**先于** `PlatformLogging.quietJavaFx()`
- 重写 user.home 后，所有 native 缓存（webview、openjfx）父目录自动归到 `~/.Epubra/`

**3. BookContext.autosaveDir 改造**
- 默认 `<user.dir>/epubra-autosave` → `AppPaths.autosaveDir()`（= `~/.Epubra/autosave`）
- fallback 路径同步：`java.io.tmpdir` 下同名子目录

**4. Preferences 命名空间迁移**
- `ThemeManager` / `RecentProjectsStore` / `AutosaveConfig`：`userNodeForPackage(Xxx.class)` → `userRoot().node("/Epubra/Xxx")`
- Windows 注册表键从 `com\epubra\app\support\Xxx` → `Epubra\Xxx`
- macOS / Linux 同理迁移

### 三道门禁（真实结果）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 编译+测试 | `mvn -B clean test` | **BUILD SUCCESS**，epublib 56 + app 163 = **219 / 219 全绿**（+14） |
| 安装 | `mvn -B install -DskipTests` | BUILD SUCCESS |
| 启动 | `timeout 25 mvn -B javafx:run` | Exit 143（timeout 杀前台 GUI = 进程存活） |

启动后实测 `~/.Epubra/` 内容：

```
~/.Epubra/
├── autosave/                                  ← AppPaths.autosaveDir()
├── webview/                                   ← AppPaths.webviewCacheDir()（预创建）
├── .com.epubra.app.EpubraApp/webview/         ← JavaFX WebView native 缓存（子目录名是 JavaFX 硬编码）
└── .openjfx/cache/24.0.1+4/amd64/             ← openjfx native 资源缓存
```

老位置 `~/.com.epubra.app.EpubraApp/` 删除后重启不再创建 ✓

### 关键实现要点

- **`AtomicBoolean` + `epubra.userDataDir` 派生属性双重防嵌套**：redirect 后 user.home 已是 `~/.Epubra/`，旧版 `Path.of(user.home, ".Epubra")` 会得到 `~/.Epubra/.Epubra/autosave`；新版优先读 `epubra.userDataDir` 属性
- **`migrateLegacyIfAny()` 只搬 `.draft` 文件**：保护用户在旧目录里手动放的其他文件（实现层 filter，不动非 .draft）
- **冲突跳过不覆盖**：旧目录有同名文件时 `moveIfAbsent` 静默返回，绝不覆盖用户数据
- **测试隔离**：`resetForTesting()` + `@AfterEach` 清 `epubra.userDataDir` 系统属性，避免 JVM 级状态跨测试污染

### 遗留 / 已知限制

- **`~/.Epubra/.com.epubra.app.EpubraApp/webview/` 子目录名无法消除**——这是 JavaFX 24 native 基于 launcher 标识的硬编码派生，父目录已正确，但子目录名需等 OpenJFX 上游提供公开 system property 或改 launcher 标识策略才能根治
- **Preferences 旧键迁移未做**——本次未加「旧 `com\epubra\app\support\Xxx` 键值 → 新 `Epubra\Xxx` 键值」的一次性 copy；首次启动后主题会回退到 light，最近项目列表为空。如需保留旧偏好，加一层 `preferences().get(legacyKey, default)` 回退即可

### Follow-up

- 是否需要 Preferences 旧键迁移（保留主题 / 最近项目跨版本升级）
- 启动器启动日志是否还需要补充「`user.home` redirected to」调试输出
- OpenJFX upstream 跟踪 WebView 缓存目录命名是否提供公开 system property

### Git 状态

仍未提交（按约定，未获用户显式批准不执行）。