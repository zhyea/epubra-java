# Epubra 项目交付概览

## 当前轮：IDEA 风格项目模型 + 欢迎页（Sprint 9，2026-09-05）

用户指令：「参考 jetbrains idea，每本 epub 书可以视为一个项目，创建 epub 项目前需要选择工作空间」；通过 AskUserQuestion 锁定范围：**新建 + 欢迎页**（不汽车化 autosave dir、不做完整 IDEA 化）。

### 交付内容

**1. 项目目录模型**（IDEA 风）
- `<workspace>/<name>/<name>.epub + .epubra/project.json` Maven-like 标准布局
- 所有路径推导由 `support/ProjectLayout` 集中维护（含 marker JSON 读写）
- 项目标记字段：`formatVersion=1` / `name` / `createdAt` / `lastOpenedAt` / `bookFile`

**2. 最近列表持久化**
- `support/RecentProjectsStore`：Preferences 持久化最近 workspaces + 最近 projects
- 上限 10 条；dedup + move-to-front；缺省 ASCII Unit Separator (`\u001F`) 序列化

**3. 新建项目对话框**
- `NewProjectDialog`（静态包装）+ `NewProjectDialogController` + `NewProjectResult` record
- 实时校验：workspace 必须是已存在目录、project name 不能含 FS 非法字符、目标不能重名
- OK 按钮实时 enable；title 留空回退为 project name

**4. 欢迎页**
- `WelcomePageController` + `welcome-page.fxml`
- 单列居中布局：标题 + 3 主操作（新建/打开/退出）+ 最近项目 / 最近工作空间 两段
- 订阅 `BookLoadedEvent` 自动隐藏（新建/打开/自动暂存恢复都触发）
- 最近列表按 `.epub` / 目录分类展示，点击解析为可打开目标

**5. MainController 集成**
- `<center>` 包 `<StackPane>`，welcome-page 作为末位 child（Z 序最高）
- 启动时不再调用 `newBook()`——ctx.book() 在欢迎页阶段为 null
- 6 处 null-book guard 集中在控制器入口方法
- 新加 `onOpenRecent(Path)` + `resolveOpenTarget` 处理工作空间→.epub 解析

### 三道门禁（真实结果）

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 编译+测试 | `mvn -B clean test` | **BUILD SUCCESS**，epublib 56 + app 149 = **205 / 205 全绿**（+26） |
| 安装 | `mvn -B clean install` | BUILD SUCCESS（两个 jar 进本地仓库）|
| 启动 | `timeout 25 mvn -B javafx:run` | Exit 143（timeout 杀前台 GUI = 进程存活），**零** LoadException / NullPointer / ClassNotFound |

### 关键实现要点

- **`ProjectLayout.inferProjectDir(Path)` 用 while 循环一路上溯**——marker 文件（位于 `.epubra/`）作为输入时，单层 `getParent()` 会落到 `.epubra/` 误判为项目目录；改为「直到首个含 marker 的祖先」
- **`DocumentController.newProject` 原子语义**——失败时绝不写 recents；UI 层包 try-catch 转 errorReporter
- **`promptRecoveryIfAny()` 也广播 `BookLoadedEvent`**——恢复草稿后欢迎页会自动收起，否则会留下「草稿载入但欢迎页还盖在上面」
- **null-book 容忍集中在 controller 入口方法**（`refreshAll()` 一次性 guard → 下游 `refreshToc` / `refreshResources` 自然安全）
- **`fx:include` 双字段注入**——主 FXML 同时声明 `welcomePage`（StackPane 根节点）与 `welcomePageController`（子控制器），按命名规则 `<fx:id>Controller`

### Follow-up

- 用户偏好决定后再考虑：是否加「关闭项目」按钮回欢迎页
- 跑一遍手动 UI 流程：在工作空间下创建项目 → 编辑 → 保存 → 重启 → 从「最近项目」打开 → 验证 Atomic 语义与 lastOpenedAt 更新
- `drafts/` 目录（ProjectLayout 注释里提到）目前是占位，未与项目级自动暂存联动——后续若要把项目级草稿从全局 `epubra-autosave` 迁到 `<workspace>/<name>/.epubra/drafts/` 再做

### Git 状态

仍未提交（按约定，未获用户显式批准不执行）。
