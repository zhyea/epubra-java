# Epubra 自动暂存（autosave / draft）落地（2026-09-05 · #21–#29）

按用户「方案 B：定时自动暂存 + `.draft` 后缀 + EPUB 内 `dcterms:status` 双标识」实施。前两轮已完成 `Autosave`/`AutosaveConfig`/`AutosaveTest`，本轮串起 UI 与控制器层。

## 关键交付

### 新文件
- `support/Autosave.java`（225 行）：markDraft/unmarkDraft/draftPathFor/flushNow/discardFor/findRecoverable/readDraft
- `support/AutosaveConfig.java`（86 行）：Preferences 持久化 enabled/debounceSeconds/dirOverride
- `support/AutosaveTest.java`（23 例）：双标识、路径解析、写盘丢弃、扫描恢复、round-trip

### 改动
- `support/BookContext.java`：autosaveConfig 字段 + autosaveDir() 兜底链（user.dir → user.home → tmpdir，never throws）
- `controller/DocumentController.java`：saveTo/onNew/onOpen 调 `Autosave.discardFor`
- `controller/MainController.java`：
  - `autosaveStatusLabel` + `PauseTransition autosaveDebounce`
  - `wireAutosave()`：enabled=false 时只标"自动暂存 关"，不挂监听
  - `promptRecoveryIfAny()`：在 `newBook()` 之前扫草稿，弹「恢复草稿 / 丢弃」Alert
  - `markDirty()` 内 `playFromStart()` 触发节流
- `view/main-window.fxml`：状态栏尾部加 `autosaveStatusLabel`
- `css/app.css`：`.status-autosave-saving`（accent 加粗）+ `.status-autosave-off`（muted + 0.7 透明）

## 双标识语义

| 层 | 标识 |
| --- | --- |
| 文件系统 | `<name>.epub` → `<name>.draft`；未保存新书 → `untitled.draft` |
| EPUB 包内 | `<meta property="dcterms:status">draft</meta>` + `<meta property="epubra:autosaved-at">ISO8601</meta>`（由 EpubWriter 自动序列化 `Metadata.properties()`，内核 0 改动） |

## 验证门禁（真实退出码）

- `mvn -B clean test`：**BUILD SUCCESS**，epublib 56 + app 123 = **179 / 179 全绿**（+31 vs 上轮的 148）
- `mvn -B install -DskipTests`：BUILD SUCCESS
- `cd epubra-app && timeout 40 mvn -B javafx:run`：Exit 143（timeout 杀前台 GUI = 进程存活 39s），**零** LoadException / NullPointer / ClassNotFound

## 易踩坑（已避开）

1. **`promptRecoveryIfAny` 必须在 `newBook()` 之前**——newBook 重置 ctx.currentFile() 为 null，导致 findRecoverable 看不到旧文件草稿
2. **节流挂点选 `markDirty()` 而非 textProperty**——单一入口覆盖内容 + 元数据两种改动
3. **`autosaveDebounce` 是 PauseTransition，需要 JavaFX Toolkit**——单元测试只能测纯 IO 层（已 23 例）
4. **草稿主文件路径反推**：`book.draft` → `book.epub`，推断后 `Files.exists` 二次校验防瞎设 currentFile
5. **Preferences 关闭开关**走 `ctx.autosaveConfig().enabled()` 一次性判断，避免每按一键都查 prefs
6. **@FXML 字段声明插入**易被 Edit 工具的 old_string 不匹配静默跳过——本轮一次坑，第二次重读文件确认再补

## Follow-up

- 用户在 Preferences 面板加 GUI 开关（目前仅 API 持久化，未暴露 UI）
- 「编辑 → 立即暂存」菜单项（绕过 PauseTransition，立即 flushNow）
- 文件菜单加「丢弃当前草稿」项（手动清 `untitled.draft`）
- Git 未提交（按约定）
- 详细日志：`.workbuddy/memory/2026-09-05.md` 末尾段

## 20 项重构后总览

#1–#20 全部闭环；本轮 #21–#29 是 autosave 落地。下一个增量候选见 Follow-up。