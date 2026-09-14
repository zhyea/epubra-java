# Epubra 全量代码审查报告

- 日期：2026-09-13
- 范围：`epubra-lib`（内核 33 文件）+ `epubra-app`（前端 55 文件）
- 规模：主源码 16,476 行 / 测试 10,047 行；45 个测试类
- 当前基线：`mvn -B clean test` = 439 全绿（lib 67 + app 372）
- 说明：本报告只做**分析与方案**，未改动任何代码

> **执行状态（2026-09-13 晚补记，#63）**
>
> | 项 | 状态 | 结果 |
> |---|---|---|
> | B1~B8 | ✅ 全部修复 | 各带守卫测试（新增 8 条）；门禁 448 全绿 + 冒烟零异常 |
> | 拆分批次 A | ✅ 完成 | `AutosaveIndicator` / `DraftRecoveryActivity` / `FileDropActivity`；MainController **1931 → 1684** |
> | 拆分批次 D | ✅ 完成 | D1 脚本外置（PreviewHtml **1101 → 355**）；D2 FormatTest 拆 5 类 + 脚手架；门禁 **449** 全绿 |
> | 拆分批次 B/C | ✅ 完成（2026-09-14） | B = `VisualEditorSession` + `EditorToolbarController`，C = `PreviewController` + `EditorShellActivity`；MainController **1721 → 1442**；详见文末「第三轮」 |
>
> 当前基线更新为 **449**（lib 68 + app 381）。

> **第二轮扫描（2026-09-13 夜，#64）** —— 复查后再发现 5 处真实缺陷，已全部修复并补齐守卫
>
> | 编号 | 位置 | 缺陷 | 修复 |
> |---|---|---|---|
> | #1 | `DocumentActivity.onSave/onSaveAs` | 停在欢迎页（`ctx.book()==null`）按 Ctrl+S → NPE 冒到 FX 事件线程，表现为「按了没反应」 | 入口判空 → `reportNothingToSave()` 明确提示；`defaultFileName()` 同步判空 |
> | #2 | `EpubraApp` / `MainController` | 关窗只调 `dispose()`，节流窗口内（默认 5s）最后一次编辑**直接丢失** | 新增 `onWindowCloseRequest()` → `AutosaveIndicator.flushPending()`（节流 RUNNING 时补写盘）；`markDirty()` 判空 |
> | #3 | `TocEditor.syncSpineFromToc` | 用 `addResourceId()` 重建 spine 恒置 `linear=true`，封面等 `linear="no"` 条目被**永久升为线性** | 重建前记录每个 id 的原 `linear`，按原值重建 |
> | #5 | `NewDraftDialogController` | 工作空间路径含 `\| < > : " ? *` → `Path.of` 抛 `InvalidPathException`，从 `setResultConverter` 冒到 `showAndWait()` | 统一走 `pathFromField()`（try/catch），非法输入按「给原因 / 当取消」处理 |
> | #8 | `DocumentActivity.workingDraftTarget` | 后缀比对大小写敏感，`三体.EPUB` 派生出 `三体.EPUB.draft` → 工作空间里同一本书**两张卡片** | 比对前 `toLowerCase()`，与 `isTextFile` / `FileDropActivity` 口径对齐 |
>
> 守卫测试：`onSaveAndSaveAsWithNoBookReportInsteadOfThrowing`、`openFileTreatsUpperCaseEpubSuffixAsDraftSource`、`AutosaveIndicatorTest`(2)、`重建阅读顺序时应保留原有的线性标志`、`NewDraftDialogControllerTest`(2) —— 共 7 条。
>
> 本轮门禁：**456** 全绿（lib 69 + app 387）+ 冒烟零异常（`javafx:run` exit 143 = `timeout` 杀进程，属预期）。
>
> 判定为**非缺陷**：#4（locale 相关小写化，理论问题）、#6（FX 线程自动暂存 = 既有架构选择）；#7 / #9 / #10 / #11 为轻微健壮性项，转入下一轮。

> **第三轮（2026-09-14）** —— 轻微项收口 + 拆分批次 B/C 落地
>
> **轻微项**
>
> | 编号 | 结果 | 说明 |
> |---|---|---|
> | #7 | ✅ 已修 | `ValidationController.highlightIssueAnchor` 改用新增的 `TextSearch.locateAnchor`（返回匹配下标 + **实际匹配长度**）。原先退化到「取最后一个 `/` 之后末段」时仍按锚点原长选区 → 选区过伸 |
> | #9 | ✅ 已修 | `Resources.add` 覆写时清理**对侧索引**的僵尸条目（同 href 换人 → 清 byId；同 id 换人 → 清 byHref）；同对象重复加入保持幂等 |
> | #10 | ⛔ **未修** | `Image(InputStream,…)` **没有** backgroundLoading 重载（只有 URL 变体有）。想从内存字节异步解码，只能先落临时文件或转 `data:` URI —— 两者都更差。经 `javap` 核对 `javafx-graphics-24.0.1` 构造器后**维持同步实现 + 注释留证**，不做假修复 |
> | #11 | ✅ 已修 | `MetadataViewController.loadIntoFields` 判空 `metadata`（null → 投射空 `MetadataDraft`），并加 `setText` 小工具跳过未注入的字段 |
>
> 守卫测试：`TextSearchTest`(4)、`ResourceManagementTest`(3)、`MetadataViewControllerTest`(1) —— 共 8 条；门禁 **464** 全绿（lib 72 + app 392）。
>
> **拆分批次 C**（预览 / 外壳，纯搬运）
>
> | 新类 | 迁出内容 |
> |---|---|
> | `controller/view/PreviewController.java` | `refreshPreview` + `previewBaseHref` + `applyPreviewMode` + `onToggleSplitPreview` + `previewMirror` 字段 + `mirrorImagesReferencedBy` |
> | `activities/EditorShellActivity.java` | `setEditorChromeVisible`（活动栏 / 状态栏 / 4 个编辑类 Menu） |
>
> 取舍两处：① 资源镜像与「相对引用解析基准」同处一地才讲得通，故 `previewMirror` 一并搬入预览类；② `onToggleSplitPreview` **不注入状态栏**，只回传布尔值由主控制器写提示——预览类不认识 `StatusCoordinator`。tab 索引经构造参数注入，不复制常量。
>
> **拆分批次 B**（可视化编辑簇，纯搬运）
>
> | 新类 | 迁出内容 |
> |---|---|
> | `editor/VisualEditorSession.java` | `installVisualEditorBridge` + `VisualEditBridge` + `syncSourceFromVisualEditor` + `reloadVisualEditor` + `flushVisualEditor` + `visualEditorLoaded` + `PENDING_*` 常量 |
> | `editor/EditorToolbarController.java` | `updateToolbarState` + `clearToolbarState` + `TOOLBAR_*` 常量 |
>
> 取舍：**`wireEditorTabSwitching` / `onVisualTab` / `insertXhtmlIntoActiveEditor` 留在主控制器**——它们是跨面板编排（先 flush 源码区，再决定 reload 还是刷预览），搬出去会绕成回调环。会话只认「我的 WebView 与正文」，副作用一律经注入钩子走出（`undoStep` / `undoAction` / `redoAction` / `markDirty` / `statusRefresh` / `toolbarState`）。FXML 与 `ResourceController.XhtmlInserter` 侧只留**一行委派**，caller 不变。
>
> 构造顺序硬约束：`editorToolbarController` 必须先于 `visualEditorSession` 构造——后者持有 `editorToolbarController::update` 方法引用，**方法引用在求值那一刻就要拿到非空实例**。
>
> 结果：`MainController` **1721 → 1442**（-279 行 / -16%）；门禁 **464** 全绿 + 冒烟零异常（`javafx:run` exit 124 = `timeout` 杀进程，属预期）。
>
> ⚠ 测试联动：`VisualEditorToolbarActivationTest` 原先反射读 `MainController.visualEditorLoaded`，已改为读 `visualEditorSession.isLoaded()` —— `loaded` 标记必须与 JS 桥同处一地，散在外面迟早漏掉某处重置。
>
> 当前基线：**464**（lib 72 + app 392）；拆分批次 A / B / C / D 全部完成。

---

## 一、规模盘点（Top 15）

| 文件 | 行数 | 评估 |
|---|---|---|
| `controller/MainController.java` | **1931** | 严重超载，混杂 12 类职责 |
| `editor/PreviewHtml.java` | **1095** | Java 逻辑 + 内嵌 JS/CSS 混居 |
| `controller/view/TocController.java` | 585 | 可接受，局部可优化 |
| `activities/DocumentActivity.java` | 562 | 可接受 |
| `controller/view/ResourceController.java` | 526 | 可接受，含死代码 |
| `lib/util/ResourceReferences.java` | 499 | 可接受 |
| `document/Autosave.java` | 415 | 可接受 |
| `controller/view/MetadataViewController.java` | 415 | 可接受 |
| `lib/io/EpubWriter.java` | 371 | 可接受 |
| `controller/layout/SidebarController.java` | 343 | 可接受 |
| `lib/io/EpubReader.java` | 327 | 可接受 |
| `lib/domain/Book.java` | 324 | 可接受 |
| `lib/validation/NavigationRules.java` | 318 | 可接受 |
| `controller/view/WelcomePageController.java` | 310 | 可接受 |
| `test/.../VisualEditorFormatTest.java` | **1090** | 测试类过大 |

---

## 二、BUG 清单

### B1｜资源「删除 / 清理」的确认框与警告文案全部丢失 —— 严重度：中

**位置**：`ResourceController.deleteSelected()` L263-270、`cleanupUnused()` L473-488

**证据**：

```java
// deleteSelected()
String message = "确定删除资源「" + row.getName() + "」？";
if (ResourceOps.isReferencedByChapters(ctx.book(), resource)) {
    message += "\n\n注意：正文中存在对它的引用，删除后相关图片或样式将无法显示。";
}
if (!confirm.getAsBoolean()) { return; }      // ← message 拼完从未使用
```

```java
// cleanupUnused()
String names = orphans.stream().limit(12)...orElse("");
if (orphans.size() > 12) { names += " 等"; }  // ← names 拼完从未使用
if (!confirm.getAsBoolean()) { return; }
```

`confirm` 由 `MainController` 注入为 `this::confirmDiscardChanges`：

```java
private boolean confirmDiscardChanges() {
    if (!ctx.dirty()) { return true; }        // ← 书是"干净"时直接放行
    return confirm("未保存的修改", "当前书籍有未保存的修改。\n继续操作将丢弃这些修改，是否继续？");
}
```

**后果（三条）**：

1. 用户**永远看不到**「确定删除资源「X」？」——文案是死代码；
2. `isReferencedByChapters` 算出来的「正文中存在对它的引用」警告**永不显示**，用户删掉正文引用的图后才发现裂图；
3. 书籍为 clean 状态时（刚打开书、未做任何修改就删资源）`confirmDiscardChanges()` 直接返回 `true` → **不弹任何对话框即执行删除**。

**定性**：可撤销（`beginChange()` 已拍快照），不是不可逆数据丢失；但「确认语义 + 风险提示」双双失效，属功能性缺陷。

**修复方向**：把 `BooleanSupplier confirm` 换成 `Function<String, Boolean>`（或新增 `confirmWith(String message)` 回调），在资源侧传入真正的确认文案。注意 `setCoverFromSelected` 等其它入口不受影响。

---

### B2｜保存正式 EPUB 时残留草稿标记 —— 严重度：低-中

**位置**：`Autosave.unmarkDraft()` javadoc vs `DocumentActivity.saveTo()` L485-501

**证据**：`unmarkDraft` 的 javadoc 明写「主文件保存成功后调用，让 .epub 不带 draft 痕迹」，但全仓 grep 只有两个调用点：

| 调用点 | 位置 |
|---|---|
| `Autosave.discardFor()` | Autosave.java:234 |
| `Autosave.readDraft()` | Autosave.java:412 |
| ~~`DocumentActivity.saveTo()`~~ | **缺失** |

`flushNow()` → `markDraft(book)`（Autosave.java:197）会把 `dcterms:status=draft` 与 `epubra:autosaved-at` **写进内存 Book 的 metadata**。此后用户「另存为」正式 `.epub`，`saveTo()` 直接 `writer.write(book, target)` → 两个私有/草稿 meta 一并写进正式包。

**修复注意**：`saveTo` 的目标也可能就是 `.draft`（默认保存对话框就是 `*.draft`），此时**不应**清标记。正确条件：

```java
if (!Autosave.isDraftFile(target)) { Autosave.unmarkDraft(book); }
```

---

### B3｜换封面选到同名文件时复用旧图，封面「换不掉」 —— 严重度：低-中

**位置**：`MetadataViewController.importCoverFile()` L374-380

```java
for (Resource existing : ctx.book().resources().all()) {
    if (fileName.equals(existing.fileName()) && mediaType.equals(existing.mediaType())) {
        return existing;      // ← 只比文件名 + 媒体类型，不比内容
    }
}
```

**后果**：用户从另一个目录选了一张**同名但不同内容**的图（很常见：本地把新图覆盖成 `cover.jpg` 后再选），返回的是书内旧资源 → `CoverOps.set(book, existing)` → 封面视觉毫无变化，用户以为功能坏了。

**附带**：与项目已确立的「排重比字节不比 md5」口径（#50 / `ResourceOps.findByContent`）不一致。

**修复方向**：判据改为 `Arrays.equals(data, existing.data())`，或直接复用 `ResourceOps.findByContent(ctx.book(), data)`。

---

### B4｜源码视图下 5 个工具条按钮静默无响应 —— 严重度：低

**位置**：`MainController` L1249-1272

```java
@FXML public void onInsertQuote()     { applyVisualFormat("quote"); }       // 无 fallback
@FXML public void onInsertRule()      { applyVisualFormat("rule"); }        // 无 fallback
@FXML public void onInsertUnderline() { applyVisualFormat("underline"); }   // 无 fallback
@FXML public void onInsertStrike()    { applyVisualFormat("strike"); }      // 无 fallback
@FXML public void onInsertCode()      { applyVisualFormat("code"); }        // 无 fallback
```

对比同组其它入口（有兜底）：

```java
@FXML public void onInsertBold() { if (!applyVisualFormat("bold")) { insertActivity.bold(); } }
```

**后果**：用户在「源码」tab（或可视化编辑器尚未加载完成时）点这 5 个按钮 → `visualEditorReady()` 为 false → `applyVisualFormat` 直接返回 false → 函数体结束：**既不改正文，也不给任何状态栏提示**，按钮像坏了一样。

`InsertActivity` 现有 `wrapTag(String)` 通用方法（L91）与 `insertFragment`（L66），补齐兜底的成本很低（只需为 `blockquote` / `hr` / `u` / `del` / `code` 各接一条 + 一句 `status.set(...)`）。

---

### B5｜`rescueStrayNodes` 依赖非标准 `root.head` —— 严重度：低（脆弱点）

**位置**：`PreviewHtml` JS `rescueStrayNodes()` L274-282

```javascript
var body = root.querySelector('body');
for (var c = root.firstChild; c; c = c.nextSibling) {
    if (c.nodeType === 1 && c !== root.head && c !== body) { stray.push(c); }
}
```

`root` 是 `document.documentElement`（XML DOM 的 `Element`）。`head` 是 `HTMLDocument` 的接口，**XML DOM 规范不保证 `Element.head` 存在**；当前能工作依赖 WebKit 的实现细节。一旦某个 JavaFX/WebKit 版本收紧，`c !== root.head` 恒为 true → **`<head>` 被搬进 `<body>`**（注入样式/脚本/基准全乱，且会写进书）。

**修复**：改用名称判定，零成本消除该依赖。

```javascript
if (c.nodeType === 1 && c.nodeName.toLowerCase() !== 'head' && c !== body) { stray.push(c); }
```

---

### B6｜`EpubWriter.normalize()` 空 spine 边界 —— 严重度：低（边界）

**位置**：`EpubWriter.normalize()` L135-140

```java
if (book.spine().size() == 0) {
    book.resources().all().stream()
        .filter(Resource::isText).filter(r -> !r.isNavDocument())
        .forEach(r -> book.spine().addResourceId(r.id()));
}
```

若 `spine` 为空**且全书没有任何文本资源**，补完仍为空 → 写出 `spine` 零 `itemref` 的包，EPUB 3 规范要求至少一项。实际 `BookFactory.createEmpty` 会建章节，触发概率低，但属真实边界。

---

### B7｜`MetadataViewController.draftFromFields()` 与 `ResourceController` 多处缺 bind 前 null 防护 —— 严重度：低（健壮性）

- `draftFromFields()` L180-187 直接 `titleField.getText()`；同一类的 `editableFields()` L126-134 却专门做了 `addIfPresent` null 过滤 → **同类内标准不一致**，FXML 未注入的场景（直接 `new` 的测试）会 NPE。
- `ResourceController.refresh()` L149 判了 `resourceTable` / `ctx.book()`，但未判 `ctx == null`；`importResources()` / `exportSelected()` 等亦然。

生产路径 bind 后不会触发，属一致性缺陷。

---

### B8｜`MainController` 重复 import —— 严重度：极低（清理）

```java
import javafx.scene.layout.VBox;   // L67
...
import javafx.scene.layout.VBox;   // L77  ← 重复
```

---

## 三、优化项清单

| 编号 | 内容 | 收益 | 风险 |
|---|---|---|---|
| O1 | `MainController` 1931 行按职责拆分（见第四节） | 高 | 中 |
| O2 | `PreviewHtml` 的 `EDIT_SCRIPT`（JS）与 `EDITOR_PAPER_CSS` 外置为资源文件 | 高（可语法高亮/独立检视，Java 侧降到 ~350 行） | 中 |
| O3 | `VisualEditorFormatTest` 1090 行按功能拆成 4~5 类 | 中（可并行/定位更快） | 低 |
| O4 | tab 索引硬编码 `VISUAL_TAB_INDEX=0 / SOURCE=1 / PREVIEW=2`（L1638-1640）→ 改按 `fx:id` 查找 | 中（FXML 调序不再静默失配） | 低 |
| O5 | `SidebarController.setVisibleManaged` 与 `FxNodes.setVisibleManaged` 重复实现 | 低 | 极低 |
| O6 | 图片扩展名清单在 3 处重复（`ResourceController.IMAGE_EXTENSIONS` / `MetadataViewController.onPickCover` / `DocumentActivity.defaultDialogs`）→ 抽统一常量 | 中 | 极低 |
| O7 | `DocumentActivity` 内 `java.nio.file.Files` 全限定名（L260/L473）与 `Files`（L340/L360）混用 → 统一 | 低 | 极低 |
| O8 | `TocController` 的 `buildTreeItem` / `findByResource` 双递归 → 建 `Map<Resource, TreeItem>` 索引 | 中（大书） | 低 |
| O9 | `Book.unreferencedResources()` 每次调用全量重扫（`collectReferencedHrefs`）；资源面板 / 校验 / 清理三处各自触发 | 中（大书） | 低 |
| O10 | 5 个图片 FileChooser 的过滤器构造可抽公共工厂方法 | 低 | 极低 |
| O11 | `MainController.warn/confirm` 用 `showAndWait()` 阻塞 FX 线程——用户交互路径可接受，但**启动路径必须继续用 `show()` + `setOnHidden`**（#恢复提示 已有正例，勿回退） | 说明项 | — |
| O12 | `VisualEditBridge` 是 `MainController` 的非静态内部类，持有外部强引用——拆 B1 时一并迁出 | 低 | 低 |

---

## 四、拆分方案

### 批次 A —— 低风险纯搬运（建议先做）

| 目标类 | 迁出内容 | 预计行数 |
|---|---|---|
| `activities/AutosaveIndicator.java` | `wireAutosave` + `markAutosaveDisabled/Saving/Idle` + `updateAutosaveLabel` + `autosaveDebounce` 字段 | ~90 |
| `activities/DraftRecoveryActivity.java` | `promptRecoveryIfAny` + `onRecoveryChoice` + `chooseRecoveryWorkspace` + `workspacePathHint` + `adoptOrphanDraft` + `deleteQuietly` | ~95 |
| `activities/FileDropActivity.java` | `wireFileDropWhenSceneReady` + `wireFileDropTo` + `firstBookFile` | ~60 |
| — | 清理重复 import（B8） | — |

预期：1931 → **~1700**

### 批次 B —— 中风险（涉及 WebView / JS 桥）

| 目标类 | 迁出内容 | 预计行数 |
|---|---|---|
| `editor/VisualEditorSession.java` | `installVisualEditorBridge` + `VisualEditBridge` + `syncSourceFromVisualEditor` + `reloadVisualEditor` + `flushVisualEditor` + `wireEditorTabSwitching` + `onVisualTab` + `visualEditorLoaded` + tab 常量 | ~200 |
| `editor/EditorToolbarController.java` | `applyVisualFormat`×2 + `visualEditorReady` + `insertHtmlIntoVisualEditor` + `updateToolbarState` + `clearToolbarState` + `insertXhtmlIntoActiveEditor` + `mirrorImagesReferencedBy` + `PENDING_*` 常量 | ~200 |

预期：~1700 → **~1300**

> ⚠ 该批次必须保留两条既有纪律：① `StatusCoordinator` 仍须在任何子控制器 bind **之前**构造；② `stage` 只能经 `setStage()` 补发，**不得进 bind 参数**。

### 批次 C —— 中风险（预览 / 外壳）

| 目标类 | 迁出内容 | 预计行数 |
|---|---|---|
| `controller/view/PreviewController.java` | `refreshPreview` + `previewBaseHref` + `applyPreviewMode` + `onToggleSplitPreview` | ~80 |
| `activities/EditorShellActivity.java` | `setEditorChromeVisible`（含 4 个 Menu 显隐） | ~15 |

预期：~1300 → **~1150**

### 批次 D —— 资源层 / 测试层（与 A–C 相互独立）

- **D1**：`PreviewHtml` 的 `EDIT_SCRIPT` → `resources/.../editor/editor-script.js`（保留 `%s` / `%3$s` 占位，用 `String.formatted` 注入）；`EDITOR_PAPER_CSS` → `editor-paper.css`。→ PreviewHtml 1095 → **~350**
  - 守卫：现 JS 内无裸 `%` 字符，`formatted` 安全；须加一条「脚本资源可加载且三个注入 id 都在」的测试。
- **D2**：`VisualEditorFormatTest` → `VisualEditorInlineFormatTest` / `VisualEditorBlockFormatTest` / `VisualEditorListTest` / `VisualEditorSanitizeTest` / `VisualEditorRoundTripTest`，公共助手抽 `VisualEditorTestSupport`（沿用既有跨类共享 JavaFX Platform 模式）。

### 执行纪律（每批次）

1. 批次末跑 `mvn -B clean test`（对基线 **439 全绿 / exit 0**）；
2. `cd epubra-app && timeout 30 mvn -B javafx:run`（exit 124 = 预期，零 LoadException / NullPointer / ClassNotFound）；
3. 任一红灯 → 立即回退该批次，不叠加下一批；
4. 拆分期间**不夹带行为改动**（BUG 修复单独提交，避免「拆」与「改」互相掩盖）。

---

## 五、建议优先级

| 优先级 | 项 | 理由 |
|---|---|---|
| **P0** | B1、B4 | 用户可感知的功能缺陷（确认框失效 / 按钮无响应） |
| **P1** | B2、B3 | 静默污染产物 / 静默无效操作 |
| **P2** | B5、B7、B8、O4、O5、O6、O7 | 低成本、消除脆弱点 |
| **P3** | 拆分批次 A → D → B → C | 结构性改动，逐批验证 |
| **P4** | O8、O9 | 依赖「书变很大」的前提，暂不急 |

> Git 累计 #39~#61 未提交，拆分前建议先让当前状态落一个提交点（需你显式批准）。
