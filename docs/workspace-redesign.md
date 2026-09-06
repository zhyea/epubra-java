# 工作空间重构设计：扁平 `.draft` 文档集合

> 日期：2026-09-06 · 状态：待用户确认后实施
> 影响：数据模型 + 欢迎页 + 文件菜单 + 自动暂存机制（架构级重构）

---

## 1. 需求与已确认决策

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | `.draft` 语义 | **处理中的主文档**。编辑即写它；「导出 EPUB」才另存 `.epub` 到别处，`.draft` 保留 |
| D2 | 自动暂存 | 机制并入「保存」——`.draft` 本身就是持续保存的目标，不再另存副本 |
| D3 | 旧项目模型 | **完全废弃**：`ProjectLayout` / `.epubra/project.json` / `<name>/` 子目录全部停用，不兼容旧数据 |
| D4 | `.draft` 来源 | ① 新建空文档 ② 导入现有 `.epub`（转为 `.draft` 存入工作空间） |
| D5 | 文件菜单 | 「最近的工作空间」子菜单 + 「打开工作空间…」 |
| D6 | 工作空间标记 | **不需要**标记文件——任意目录都可当工作空间，识别依据 = 目录内存在 `*.draft` 或用户显式打开过 |

### 模型对比

```
【旧·废弃】                        【新·目标】
<ws>/                              <ws>/
  <name>/                            三体.draft
    <name>.epub                       球状闪电.draft
    .epubra/project.json              朝闻道.draft
  <name2>/                          （扁平，无子目录，无元数据目录）
    ...
```

---

## 2. 文档生命周期

```
【新建】 输入标题 ──→ <ws>/<title>.draft          （空骨架 EPUB）
【导入】 选 .epub ──→ 读入 ──→ 写 <ws>/<stem>.draft
【编辑】 ──────────→ 持续写回同一个 .draft（Ctrl+S = 写回，非"另存"）
【导出】 .draft ──→ FileChooser ──→ <用户路径>/<title>.epub   （.draft 不动）
```

**「保存」语义变更（需用户二次确认措辞）**

| 原 | 新 |
| --- | --- |
| `Ctrl+S` 保存 → 写 `ctx.currentFile()`（`.epub`） | `Ctrl+S` 保存 → 写回当前 `.draft`（保存进度） |
| `Ctrl+Shift+S` 另存为 | `Ctrl+E` 导出为 EPUB…（另存 `.epub` 副本） |

---

## 3. 布局：工作空间页面（宫格）

### 3.1 结构

```
┌──────────────────────────────────────────────────────┐
│ 工作空间  D:/Books                    [切换工作空间 ▾] │  ← 顶部栏 48px
├──────────────────────────────────────────────────────┤
│                                                        │
│   ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐                │
│   │ ▤   │  │ ▤   │  │  ？ │  │  ＋ │                │
│   │     │  │     │  │     │  │     │                │  ← TilePane 宫格
│   │三体 │  │球状 │  │朝闻 │  │新建 │                │    gap 16，居中
│   │2时前│  │昨天 │  │3天前│  │     │                │
│   └─────┘  └─────┘  └─────┘  └─────┘                │
│                                                        │
└──────────────────────────────────────────────────────┘
```

### 3.2 卡片点击行为

**单击 `.draft` 卡片 → 直接进入该文档的编辑页**，无中间确认页、无二次对话框。

```
点击卡片 ─→ DocumentController.openDraftAsync(path)
         ─→ 后台 EpubReader.read(.draft)      （.draft 内容是合法 EPUB zip，可直接读）
         ─→ FX 线程 ctx.setBook + publish BookLoadedEvent
         ─→ 宫格自动收起（复用现有 BookLoadedEvent 订阅）、编辑区呈现
```

| 环节 | 处理 |
| --- | --- |
| 未保存修改 | 仅当 `ctx.dirty()` 为真才弹「丢弃确认」；刚启动/刚切工作空间时无此步骤，做到"直达" |
| 状态栏文案 | 「已打开 三体」——去掉 `.draft` 后缀，不显示扩展名 |
| 工作空间记忆 | 打开后 `WorkspaceStore.setLast(文档所在目录)`，下次启动直达该工作空间 |
| 当前文件 | `ctx.currentFile()` = 该 `.draft` 路径，后续 `Ctrl+S` 写回它 |
| 异步 | 走 `AsyncTasks.runIo`，状态栏显示「正在打开 三体」，大书不卡 UI |
| 读失败 | `errorReporter` 报错并留在宫格，不进入空的编辑页 |

「＋ 新建」卡片走独立路径（`NewDraftDialog` 输标题 → 在工作空间建 `.draft` → 再打开）。

### 3.2.1 排序规则

宫格内 `.draft` 文档**按最近修改时间降序**（最近改过的排最前）。

```
Comparator：Files.getLastModifiedTime(path) 降序
            ↓ mtime 相同时
            文件名升序（tie-breaker，保证确定性）
```

**为什么必须有 tie-breaker**：Windows NTFS mtime 精度 100ns，但批量导入 / 复制产生的文件常落在同一时间戳；若只按 mtime 排，`Comparator` 遇到相等元素时顺序取决于 `Files.list` 的返回次序（文件系统相关、不稳定），会导致每次刷新宫格时卡片位置随机跳动。加文件名升序兜底可完全消除该抖动。

- 读不到 mtime（权限 / 文件刚被删）→ 视为 `Instant.EPOCH`，排到最末
- 「＋ 新建」卡片**固定排在宫格末尾**，不参与排序

### 3.3 卡片规格

| 元素 | 规格 |
| --- | --- |
| 卡片尺寸 | 108 × 168（封面 84×112 + 左右 padding 12 + 文字区 40） |
| 封面 | 84 × 112（3:4），`PreserveRatio` 居中；无封面 → 占位块 + 书名首字 |
| 标题 | 14px / 500，单行截断（中点省略），最大宽 96 |
| 时间 | 12px，`-epubra-muted-fg`，相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 / M月D日） |
| 间距 | card gap 16，容器内 padding 24 |

### 3.3 响应式列数（窗口宽度断点）

| 窗口宽 | 列数 |
| --- | --- |
| < 400 | 2 |
| 400–679 | 3 |
| 680–959 | 4 |
| 960–1279 | 5 |
| ≥ 1280 | 6 |

实现：`TilePane.setPrefColumns(n)`，监听容器 `widthProperty` 变化重算。
（不用 `GridPane`——需手动算行列；不用 `FlowPane`——tile 不等宽会错位。）

### 3.4 卡片状态

| 状态 | 表现 | 动效 |
| --- | --- | --- |
| default | `-epubra-bg-panel` 底 + `-epubra-border` 1px 描边 | — |
| hover | 描边转 `-epubra-accent`，轻微 `translateY(-2px)` | 0.2s，仅 `transform` + `opacity` |
| focus（键盘） | 焦点环 `-fx-focus-color` 2px | 必须可见（WCAG 2.4.7） |
| pressed | `scale(0.97)` | 0.1s |
| 无封面 | 灰度占位块 + 书名首字 24px | — |

> 动效克制原则：只动 `transform` 与 `opacity`（GPU 加速），不动 `layoutX/Y` 或宽高。

---

## 4. 启动与菜单

### 4.1 启动流程

```
App 启动
  ├─ Preferences 里有 lastWorkspace 且目录仍存在
  │     └→ 直接展示该工作空间宫格
  └─ 否则
        └→ 展示「选择工作空间」引导态（[打开工作空间…] + [新建工作空间…]）
```

### 4.2 文件菜单结构

```
文件
  新建文档…                 Ctrl+N
  导入 EPUB…
  导出为 EPUB…              Ctrl+E
  ─────────────────────
  打开工作空间…
  最近的工作空间   ▶     D:/Books            ✓（当前）
                       D:/Docs/Epubs
                       D:/tmp/test
  ─────────────────────
  退出                      Ctrl+Q
```

- 「最近的工作空间」最多 8 条，当前项前置 `✓`
- 点击切换 → 重扫该目录 → 重画宫格 → 写入 `lastWorkspace`

---

## 5. 实施计划（分阶段）

| Phase | 内容 | 涉及文件 |
| --- | --- | --- |
| Phase | 内容 | 涉及文件 | 状态 |
| --- | --- | --- | --- |
| **P1 数据层** | 新增 `DraftDocument`（record：path/title/modifiedAt）、`RelativeTime`（相对时间文案）、`WorkspaceStore`（Preferences 存最近工作空间 + lastWorkspace）、`WorkspaceScanner`（扫 `*.draft` → **按 mtime 降序 + 文件名升序兜底**）；`Autosave` 加 `stripDraftSuffix`；`DocumentController` 加 `openDraft` / `openDraftAsync` / `draftDisplayName` / `rememberWorkspaceOf`（卡片点击直达编辑页） | 新 4 / 改 2 | ✅ **已完成**（32 测试全绿 = 数据层 25 + `OpenDraftTest` 7） |
| **P2 文档操作** | `DocumentController` 重写：`newDraft` / `openDraft` / `saveDraft` / `exportEpub` / `switchWorkspace`；`Autosave` 改为写回 `.draft` 本身（废弃 `discardFor` / `findRecoverable`）；`NewProjectDialog` → `NewDraftDialog`（只输标题）；**删除 `ProjectLayout` + `RecentProjectsStore`** | 改 3 / 删 2 |
| **P3 宫格 UI** | 重写 `welcome-page.fxml` → 工作空间页；`WelcomePageController` → `WorkspacePageController`；CSS 卡片样式 | 改 2 + CSS |
| **P4 接线** | 文件菜单改结构；启动直达最近工作空间；`MainController` 编排 | 改 2 |
| **P5 测试门禁** | `WorkspaceScannerTest` / `RelativeTimeTest` / `WorkspaceStoreTest` / `OpenDraftTest` ✅；宫格 GUI 测试待 P3；`mvn clean test` + smoke | 已新增 4（待补宫格 GUI） |

预估改动 **600–900 行**，新增测试 **25–35 个**。

---

## 6. 无障碍检查清单（WCAG 2.2 AA）

| 项 | 要求 | 本方案 |
| --- | --- | --- |
| 2.4.7 焦点可见 | 键盘焦点必须可见 | 卡片用 `Button`，焦点环 2px `-fx-focus-color` |
| 1.4.3 对比度 | 正文 ≥ 4.5:1，大字/UI ≥ 3:1 | 标题 `-epubra-fg`、时间 `-epubra-muted-fg`（需实测，见下方待确认） |
| 2.1.1 键盘 | 全功能可键盘操作 | 宫格支持 Tab 遍历 + 方向键（TilePane 内 focus traversal）+ Enter 打开 |
| 1.4.1 不靠颜色 | 状态不能仅用颜色区分 | 当前工作空间用 `✓` 符号而非仅高亮色 |
| 2.5.8 目标尺寸 | 交互目标 ≥ 24×24 | 卡片 108×168 ✅ |
| 3.2.4 一致性 | 同类功能位置一致 | 「+ 新建」固定在宫格末尾 |

---

## 7. 待确认（实施前）

| # | 问题 | 我的建议 |
| --- | --- | --- |
| Q1 | 「保存」按钮文案是否改为「保存进度」？「另存为」改为「导出为 EPUB」？ | 建议改，避免与"发布"混淆 |
| Q2 | 关闭某文档后（返回宫格）是否需要入口？（如文件 → 关闭文档） | 建议加，否则只能退出重进 |
| Q3 | 宫格里是否需要删除 / 重命名文档？ | 建议 P1 阶段只做右键菜单的「删除」，重命名延后 |
| Q4 | `-epubra-muted-fg` 对比度实测是否达标？ | 实施时用取色工具核验，不达标则调亮 |
| Q5 | 是否保留拖放 `.epub` 到窗口 = 导入为 `.draft`？ | 建议保留（现有拖放代码可复用） |

---

## 8. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `Autosave` 现有 23 个测试需改 | 中 | P2 阶段同步改测试；先把新语义写进类注释 |
| `ProjectLayout` 删除后 `ProjectLayoutTest`（11 用例）失效 | 中 | 一并删除，用例数迁移到 `WorkspaceScannerTest` |
| 旧工作空间数据不兼容 | 低（用户已确认） | 首次启动时若扫到旧结构，宫格里不显示，不报错 |
| 宫格大数据量（>500 `.draft`）卡顿 | 低 | 扫描走 `Files.list` 流式；封面缩略图异步加载（复用 B1 的 `AsyncTasks`） |
