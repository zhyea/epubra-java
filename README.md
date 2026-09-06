# Epubra

EPUB 电子书维护工具，JavaFX 桌面应用。界面采用 **VSCode 风格布局**：最左侧活动栏切换侧边视图、侧边栏单视图、中间编辑区、底部「问题」面板、分区状态栏。

工程为 **Maven 多模块**结构，EPUB 内核与桌面前端彻底解耦。

## 模块结构

| 模块 | 产物 | 职责 |
| --- | --- | --- |
| `epubra-parent`（根 POM） | pom | 聚合构建、依赖与插件版本锁定（dependencyManagement / pluginManagement） |
| `epubra-lib` | jar | EPUB 内核：EPUB 2/3 的读取与写出。**只依赖 JDK**（`java.util.zip` + `javax.xml`），零第三方库 |
| `epubra-app` | jar | JavaFX 前端：目录浏览、章节编辑、资源维护、元数据编辑、存取 EPUB、EPUB 结构校验 |

```
epubra/
├── pom.xml                    epubra-parent（packaging=pom，聚合两个模块）
├── epubra-lib/
│   └── src/main/java/org/chobit/epubra/lib/
│       ├── io/                EpubReader / EpubWriter
│       ├── domain/            Book、Metadata、Spine、TableOfContents、Resource、TocEditor…
│       └── util/              Hrefs、Xmls
└── epubra-app/
    └── src/main/
        ├── java/org/chobit/epubra/app/
        │   ├── Launcher.java          启动引导类（非 Application 子类）
        │   ├── EpubraApp.java        Application 子类，装载主界面
        │   ├── controller/            MainController（主界面）
        │   ├── components/            ChapterNode、ResourceRow、ValidationIssueRow、NewProjectDialog、NewProjectDialogController
        │   └── support/               BookHistory、TextSearch、Theme / ThemeManager / PreviewHtml、ValidationTexts
        └── resources/org/chobit/epubra/app/
            ├── view/main-window.fxml  主界面布局（活动栏 / 侧边栏 / 编辑区 / 底部面板 / 状态栏）
            └── css/app.css            三主题样式（浅色 / 深色 / 护眼米黄）与 -epubra-* 配色变量
```

## 界面布局

```
┌──────────────────────────────────────────────────────────────┐
│ MenuBar  文件 · 编辑 · 开始 · 插入 · 视图 · 帮助              │
├────┬───────────────┬─────────────────────────────────────────┤
│ 活 │ 侧边栏        │ 编辑区：内容 / 预览                      │
│ 动 │ （单视图）    ├─────────────────────────────────────────┤
│ 栏 │ 目录 / 资源 / │ 底部面板「问题」（Ctrl+` 开关，默认隐藏） │
│    │ 元数据        │                                          │
├────┴───────────────┴─────────────────────────────────────────┤
│ statusLabel             错误 │ 警告 │ 章节 │ 字数 │ 主题      │
└──────────────────────────────────────────────────────────────┘
```

- **活动栏**（48px）：四个 `ToggleButton` 共用同一个 `ToggleGroup`，选中项左侧有 2px 指示条。
  图标是 `SVGPath`（16×16 视口），颜色走 `-epubra-*` 变量，三套主题自动换色。
  - 目录 / 资源 / 元数据 → 切换侧边栏视图（三个视图叠放在同一个 `StackPane` 里，切 `visible` + `managed`）
  - 校验 → **不切换侧边栏**，改为展开底部面板并立即跑一次校验
- **工具栏已移除**：命令全部走菜单 + 快捷键，撤销 / 重做的禁用态由菜单项 `undoItem` / `redoItem` 控制。
- **底部面板**：`Ctrl+\`` 或「视图 → 问题面板」开关；面板头显示错误 / 警告条数与关闭按钮；
  表格四列为级别 / 分组 / 说明 / 位置，**双击一行定位到对应章节**（选中目录树节点、切到「内容」页签，
  并尽量把光标带到正文里出问题的 `id` 或引用串上）。
- **状态栏**：右侧错误 / 警告 / 章节 / 字数 / 当前主题，中间用 1px 竖线分隔；错误走红系、警告走橙系变量。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25.0.3 | `maven.compiler.release=25` |
| JavaFX | 24.0.1 | controls + fxml + web，FXML + 控制器模式 |
| EPUB 内核 | 自维护 | `epubra-lib`，无第三方 EPUB 依赖 |
| JUnit | 5.12.0 | 仅测试，两个模块共 306 个用例 |
| Maven | 3.9.15 | 见下方环境说明 |

## 环境说明

本机 Git Bash 下官方 `mvn` 脚本会失效（`MAVEN_HOME` 被推导成 `/d/...` 这种 MSYS 路径，Windows 的 java.exe 不识别，报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`）。

已在 `~/.workbuddy/bin/mvn` 放置垫片脚本，并通过 `~/.bashrc` 把该目录前置到 PATH。新开终端直接 `mvn` 即可；若未生效，用全路径调用：

```bash
~/.workbuddy/bin/mvn clean test
```

## 常用命令

```bash
# 聚合构建 + 全量测试（根目录执行）
~/.workbuddy/bin/mvn clean test

# 安装到本地仓库（运行/打包前需要先执行一次）
~/.workbuddy/bin/mvn install

# 启动应用（在 app 模块内执行）
cd epubra-app && ~/.workbuddy/bin/mvn javafx:run

# 或根目录执行，-pl 限定模块
~/.workbuddy/bin/mvn -pl epubra-app javafx:run

# 打包桌面分发镜像（-Pdist）：产出 epubra-app/target/dist/Epubra/Epubra.exe
~/.workbuddy/bin/mvn -B package -Pdist
```

> 注意：不要在根目录执行 `mvn -am javafx:run`。`javafx:run` 是 CLI 直接调用的 goal，Maven 会对 reactor 内**每个**模块都执行一次，而父模块没有 `mainClass`，会直接报 `The parameters 'mainClass' ... are missing`。请限定 `-pl epubra-app`，或进入模块目录执行。

## 运行方式说明

1. 入口为 `EpubraLauncher`（普通类）→ 间接调用 `EpubraApp.main()`。原因是本项目为非模块化构建（无 `module-info.java`），JavaFX 只出现在 classpath；此时若以 `Application` 子类直接作为 main-class，JVM 会在启动阶段做 JavaFX 运行时组件校验并失败，命令行 `java -cp` 与 jpackage 打包产物都无法启动。经由引导类绕开校验后，同一份产物既能被 `javafx:run` 运行，也能被打包成可双击的桌面应用。
2. `epubra-app` 依赖 `epubra-lib`，后者已在本地仓库时可直接运行；修改内核后需重新 `mvn install` 才会被前端用到。
3. JavaFX 不能打成 fat jar —— native 库（glass / prism 的 dll）必须在真实文件系统上，shade 后反而加载不到。因此 `dist` profile 采用「应用 jar + 依赖目录」交给 `jpackage` 组装。
4. **启动告警处理**：JDK 22+ 收紧了 native 访问与 `sun.misc.Unsafe`，启动时会有两类告警。
   - `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` —— 非模块化运行的必然结果，由 `PlatformLogging` 在启动早期用 JUL Handler Filter 精准拦截（只拦这一条，JavaFX 其它告警照常输出）。
   - `System::load`（glass NativeLibLoader）与 `sun.misc.Unsafe::allocateMemory`（Marlin）—— 属 JVM 层，需启动时传参：

     | 运行方式 | 需要的 VM options |
     | --- | --- |
     | `mvn javafx:run` | 已配在 `epubra-app/pom.xml` 的 `options` 里，无需手动加 |
     | `mvn package -Pdist` 产物 | 已配在 jpackage 的 `--java-options` 里，无需手动加 |
     | IDE 直接 Run / `java -cp` | `--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow` |

     > 两种运行方式的模块形态不同：`javafx:run` 走 module-path（JavaFX 是**具名模块**，须写 `javafx.graphics,javafx.web`），而 `java -cp` 与打包产物走 classpath（须写 `ALL-UNNAMED`）。混写会额外产生 `Unknown module` 告警。

## 当前能力

**EPUB 内核（epubra-lib）**

- EPUB 2/3 读取与写出，`mimetype` 按规范以 STORE 方式置于归档首位
- OPF / NCX / Nav 文档的解析与生成，spine 与目录（TOC）维护
- 资源增删改查与媒体类型识别、href 规范化
- 层级目录编辑（`TocEditor`）

**桌面前端（epubra-app）**

- 新建文档、打开 / 保存 / 另存为 EPUB
- 目录树浏览（支持层级）、章节添加 / 删除 / 上移 / 下移
- **章节重命名**：双击目录项或按 F2，同步更新目录条目与章节 XHTML 的 `<title>` / `<h1>`
- **目录树拖拽排序**：按落点判定拖到目标之前 / 之后 / 成为子节点，自动同步阅读顺序（spine）
- **撤销 / 重做**（`Ctrl+Z` / `Ctrl+Shift+Z`）：快照式历史，内容编辑、结构变更、资源增删、元数据改动一并覆盖
- **EPUB 结构校验**：活动栏「校验」或「视图 → 运行校验」触发，结果落在底部「问题」面板；
  有磁盘文件时跑 `validate(Book, Path)`（含 mimetype、container.xml 等容器级规则），否则降级为内存校验。
  校验是**只读操作**，不进撤销栈、不置未保存标记；双击问题行可定位到章节
- **查找替换**（`Ctrl+F`）：上一个 / 下一个 / 替换 / 全部替换，支持区分大小写，范围可选当前章节或全书
- **三主题切换**：浅色（WPS 蓝）/ 深色 / 护眼米黄，选择持久化到 Preferences 下次启动自动恢复；预览区（WebView）同步换配色
- 正文编辑（XHTML 源码级）与 WebView 实时预览
- 元数据编辑：书名、作者、语言、出版者、标识符、简介
- 资源面板：导入、导出、删除、设为封面、插入图片、清理未引用资源（带引用检测）
- 未保存修改的放弃确认、状态栏与字数统计

## 撤销 / 重做的实现取舍

历史采用「把 `Book` 写成内存 EPUB，恢复时再读回」的快照方式，而不是为每类操作单独定义逆操作：

- 好处是新增任何编辑功能都自动获得撤销能力，无需维护成对的 do/undo 逻辑；
- 代价是每次快照要序列化整本书，因此连续输入按 600ms 静默合并为一个编辑步，历史栈深上限 30。

## 测试

```bash
~/.workbuddy/bin/mvn test
```

- `epubra-lib`：57 个用例 —— 读写往返、容器结构、目录层级、EPUB 2 NCX 兼容、资源管理、目录编辑、结构校验
- `epubra-app`：249 个用例 —— `TextSearch`（查找替换与标题同步的文本逻辑）、`BookHistory`（快照式撤销 / 重做）、
  `Theme` / `ThemeManager`（主题枚举与偏好存取往复）、`PreviewHtml`（预览区主题样式注入）

与界面控件绑定的部分（拖拽落点、对话框）依赖 JavaFX 运行时，未做自动化测试；纯逻辑已抽到 `org.chobit.epubra.app.support` 下以便单测。

## 后续迭代

- EPUB 结构校验（OPF / NCX 一致性、资源引用完整性）
- 正文所见即所得编辑（HTMLEditor）
- 目录树层级升降级（缩进 / 提升，内核 `TocEditor` 已支持，尚未接入界面）
- 拼写检查、字数统计面板
