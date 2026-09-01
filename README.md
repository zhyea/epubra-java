# Epubra

EPUB 电子书维护工具，JavaFX 桌面应用。目标是对标 WPS / Office 的编辑体验：左侧章节目录、中间正文、右侧元数据，打开即改、改完即存。

## 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25.0.3 | `maven.compiler.release=25` |
| JavaFX | 24.0.1 | controls + fxml，FXML + 控制器模式 |
| epublib | 3.1（`com.positiondev.epublib:epublib-core`） | EPUB 读写内核 |
| slf4j | 2.0.16 | api + simple，默认日志级别 warn |
| JUnit | 5.11.4 | 仅测试用 |
| Maven | 3.9.15 | 见下方环境说明 |

## 环境说明

本机 Git Bash 下官方 `mvn` 脚本会失效（`MAVEN_HOME` 被推导成 `/d/...` 这种 MSYS 路径，Windows 的 java.exe 不识别，报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`）。

已在 `~/.workbuddy/bin/mvn` 放置垫片脚本，并通过 `~/.bashrc` 把该目录前置到 PATH。新开终端直接 `mvn` 即可；若未生效，用全路径调用：

```bash
~/.workbuddy/bin/mvn clean test
```

## 常用命令

```bash
# 编译 + 跑测试
mvn clean test

# 启动应用
mvn process-classes exec:exec

# 冒烟自检：启动界面 → 跑一遍新建/保存/加载 → 2 秒后自动退出
EPUBRA_SMOKE=true mvn process-classes exec:exec
```

冒烟自检会打印两行结果，均为 OK 才算通过：

```
EPUBRA-EPUB-OK size=2119bytes chapters=2
EPUBRA-SMOKE-OK
```

## 目录结构

```
src/main/java/com/epubra/
├── app/
│   ├── Main.java        启动器（普通类，间接 launch，见下方说明）
│   ├── App.java         Application 子类，装载主界面
│   └── SelfCheck.java   冒烟模式下的 EPUB 内核自检
├── core/
│   ├── EpubDocument.java  文档模型：章节 + 元数据的读写转换（UI 与 epublib 唯一桥梁）
│   ├── ChapterItem.java   章节（JavaFX Property，便于目录树与编辑区联动）
│   └── BookMetadata.java  书籍元数据（dc:* 字段）
└── ui/
    └── MainController.java  主界面控制器

src/main/resources/
├── fxml/main-view.fxml   主界面布局
└── css/app.css           WPS / Office 风格浅色主题
```

## 运行方式说明

当前采用**全部依赖走 classpath** 的方式运行，原因与注意事项：

1. `org.openjfx:javafx-maven-plugin:0.0.8` 会把无法推导模块描述符的 JAR（epublib 必需的 `kxml2`、`xmlpull`）直接丢弃，导致界面能启动、但读写 EPUB 时抛 `NoClassDefFoundError`；其 `includePathExceptionsInClasspath` 开关与 MODULEPATH / CLASSPATH 两种模式实测均无效，因此不使用该插件。
2. JavaFX 从 classpath 运行时，主类不能是 `Application` 子类，否则会报 `JavaFX runtime components are missing`。因此入口拆成 `Main`（普通类）+ `App`（`Application` 子类）。
3. 打包发布阶段若要走 `jlink` / `jpackage`，需改为模块化（加 `module-info.java`，JavaFX 走模块路径，epublib 等以自动模块引入）。

## 当前能力

- 新建文档（预置起始章节与 UUID 标识符）
- 打开 / 保存 / 另存为 EPUB
- 章节增删改：添加、删除、上移、下移
- 正文编辑（XHTML 源码级，带字数统计）
- 元数据编辑：标题、作者、出版社、语言、标识符
- 未保存修改的放弃确认

## 后续迭代

- 正文所见即所得编辑（WebView / HTMLEditor）
- 目录树层级结构（当前为单层平铺）
- 图片等资源插入与管理
- 查找替换、EPUB 结构校验
- 章节标题重命名（双击目录项）
