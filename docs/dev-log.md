# DeskFlow 开发记录

Phase 1 MVP（仅 vivo `com.bbk.launcher2`），依据 chat-export 方案设计文档实现。

## 当前状态（2026-09-26）

- 工程骨架：AGP 9.3.1 + Kotlin 2.4.10（AGP 内置 Kotlin，不应用 `org.jetbrains.kotlin.android`）+ Compose BOM 2026.08（compose-ui 1.12）+ libsu 6.0.0，JDK 21（mise）
- 构建脚本：`build_debug.bat` / `build_release.bat` / `build_test.bat`（风格对齐 vivo-helper-shell，优先 mise java@21 避免 foojay 下载）

## 已验证

| 项目 | 结果 |
|------|------|
| `:app:compileDebugKotlin` | 通过 |
| `:app:testDebugUnitTest`（9 个 JVM 单测：网格放置/交换/越界/建文件夹/删除回填/只读 widget/临时 id 映射/Dock） | 全部通过 |
| `build_debug.bat` → `app-debug.apk`（约 29MB） | 成功 |
| 真机调研（此前 adb root 会话）：`launcher.db` 位于 `/data/user_de/0/com.bbk.launcher2/databases/`，`PRAGMA journal_mode=delete`（无 WAL，文件复制法安全），`desktopStyle.currentDesktop=1000`（jan 表激活），intent 格式 `pkg/class` | 已确认，适配层据此实现 |

## 架构落地情况（对照设计文档）

- 统一模型：`domain/model/Models.kt`（ItemState/Workspace/GridConfig，container -101=Dock、-100=桌面、>0=文件夹）
- 编辑操作：`domain/layout/WorkspaceEditor.kt`（放置/交换/合并建夹/删除回填，widget 等未知 itemType 只读）
- Diff→SQL：`domain/layout/DiffCalculator.kt`（Update/Insert/Delete，新行用 `MAX(_id)+1` 预分配，夹内父子 temp id 映射）
- vivo 适配器：`data/adapter/vivo/VivoLauncherAdapter.kt`（读 `location_jan`+`screen_jan`；写回同步 `attribute` 表）
- Root 安全写入：`data/db/LauncherDbAccess.kt` + `SafeWriter.kt`（root cp 副本→本地事务改→cp 回写并恢复 owner/mode/SELinux context→force-stop+HOME 重载→读回验证，不一致自动回滚）
- 备份：`data/backup/BackupManager.kt`（应用内部目录 `files/launcher/backups`，保留 10 份，手动/自动）
- UI：`ui/editor/EditorScreen.kt`（页面 chip + 4x7 网格 + Dock 行；点选-点放、长按拖拽、拖出左右边缘换页、文件夹弹层、应用确认、备份列表恢复）

## 真机 E2E 结论（2026-09-26 02:13，vivo X200 / KernelSU）

全链路验证通过：

| 环节 | 结果 |
|------|------|
| Root 授权（libsu → ksu，`uid=0 context=u:r:ksu:s0`） | 通过 |
| 读取渲染：290 图标、12 页 chip、Dock、文件夹 2x2 预览 | 通过 |
| UI 编辑：点选「电子邮件」→ 点「VIVO OTA」→ 生成新文件夹（含两子图标） | 通过 |
| 应用写回：`location_jan` 新增 `_id=337`（itemType=10，screen/cell 正确）+ 两子项 container=337 + `attribute` 行；force-stop 后 vivo 桌面真实显示新文件夹 | 通过 |
| 备份恢复：用户手动从备份恢复，桌面回到原状（电子邮件/VIVO OTA 复位） | 通过 |

### 踩坑记录（已修复）

1. **KernelSU 给 App 的 root shell 在隔离 mount namespace**，`test -e /data/user_de/...` 直接失败（rc=1 无 stderr）。修复：`LauncherDbAccess.exec` 统一包 `nsenter -t 1 -m -- sh -c`（探测可用才包，否则回退）。
2. **root 经 FUSE 写到 `/storage/emulated/0/Android/data/<pkg>/` 的文件应用进程打不开**（SQLITE_CANTOPEN code 14）。修复：工作副本/备份全部改到应用内部目录 `files/launcher/`，root cp 后 `chown <appUid>` + `chcon $(stat -c %C dir)`。
3. AGP 9 内置 Kotlin，不能再应用 `org.jetbrains.kotlin.android` 插件。
4. Compose 1.12 移除了 `LayoutCoordinates.local()/boundsInWindow()`，改用 `windowToLocal(Offset)`。
5. vivo 会把文件夹子项的 `screen` 归一化为 0（原生文件夹亦如此），属正常行为。

### 已知待改进

- 部分图标灰色占位（MT管理器/酷安/Vivo助手/SakiSU/folder_tools）：`getActivityIcon` 因 vivo 包可见性或双开/快捷方式 intent 为空失败，后续可用 `iconPackage/iconResource` 列 + `Resources.getDrawable` 兜底
- `diagnose()` 调试辅助保留在 LauncherDbAccess 中

### 布局错位修复（2026-09-26 02:20，已验证）

桌面页网格原先用 `fillMaxWidth().aspectRatio(4/7)`，高度超出 `weight(1f)` 容器且 Compose 默认不裁剪 → 首行顶进 chip 行、末行压到底部提示条。修复：桌面页改 `fillMaxSize()`（cellH=可用高/7）、Dock 保持 `aspectRatio(5f)`、网格容器加 `clipToBounds()`。真机验证：4×7 全显、图标与网格线对齐、无重叠。

## 与文档的偏差（MVP 有意简化）

- 未引入 Hilt/MVI 框架（单 ViewModel + mutableStateOf；后续拆分再引入不迟）
- 未做页面增删排序、抽屉（`location_jan_drawer`）、负一屏编辑（只动 `location_jan`/`screen_jan`，其余表不碰）
- Widget 迁移按文档建议跳过（只读占位）

## 本轮进展（2026-09-26 后续会话）

### 1. 图标兜底加载

`DeskFlowViewModel.loadIcon` 改成四级解析链（原来只有 getActivityIcon 一级）：

1. intent 组件 → `getActivityIcon`
2. intent 包名 → `getApplicationIcon`
3. `iconPackage` + `iconResource` 列 → `getResourcesForApplication(pkg).getDrawable`（drawable/mipmap 都试，resource 名取 `:` `/` 之后、去扩展名）
4. `componentPackage()` 或 `iconPackage` → `getApplicationIcon`

图标缓存 key 从 intent 字符串改为 `dbId`（双开/快捷方式可分别命中不同兜底路径）。

### 2. Phase 2 智能分类（domain 纯逻辑 + 单测）

新增 `domain/classifier/`：

- `Category.kt`：11 个类别（系统/社交/影音/资讯阅读/购物生活/金融支付/办公效率/游戏/拍摄美化/实用工具/未分类）+ `CategoryRules`（exactPackages / keywords / titleKeywords）+ `DefaultRules` 内置规则表
- `AppClassifier`：精确包名 → 包名分段包含（同段多命中取**最长**关键词，故 vivo 浏览器归工具而非系统）→ 标题中文关键词 → `OTHER`
- `AutoArranger.arrange`：按类别分组后顺序铺满桌面页（行优先），产出 `ArrangeResult`（分页排布 / `unplaced` / 分类概览 / `movedCount`）；`apply` 写回 workspace
- 重排约定：Dock、widget（readOnly）、文件夹子项不参与；文件夹整体移动；页面不足时剩余项保持原位并计入 `unplaced`

用户映射表：`app/src/main/assets/category_mapping.json` + `data/classifier/CategoryMappingLoader`（org.json 解析，与内置规则 merge 后覆盖；文件缺失/损坏静默退化为无自定义规则）。domain 层不碰 org.json，保证单测在纯 JVM 可跑。

UI：TopAppBar 新增「整理」→ `ui/arrange/ArrangeDialog` 预览（移位数量 / 分类概览 / 分页结果）→ 采用后排布进内存 workspace，**仍需点「应用」写库**，沿用 SafeWriter 的备份与回滚。

### 测试

`ClassifierTest.kt` 新增 13 个用例：分类命中、最长关键词优先、标题兜底、自定义规则覆盖、跨页填充顺序、widget 占位跳过、页面不足计数、Dock 不动、无重叠且不越界、apply 与 movedCount、文件夹整体移动、空页 summary、分组顺序。
`build_test.bat`：22 个全绿（9 旧 + 13 新）。

### 清理

- 移除 `LauncherDbAccess.diagnose()` 及其在 `SafeWriter.load` 中的调用
- TopAppBar 标题改 `DeskFlow · vivo`（18.sp / maxLines=1），四个按钮后不再拥挤

## 验证状态

### 3. 页面管理（增删/排序页，写 `screen_jan`）

- `ScreenPage` 增加 `isNew` / `isDeleted` / `originalOrder`；`Workspace.pages` 改为 `MutableList`
- `WorkspaceEditor`：`addPage()`（screenId = 现有最大值 + 1，不复用已删页 id，order 排最后）、`deletePage()`（先把该页图标迁到其余页空位；容量不足 / 页上有 widget / 只剩一页时拒绝并**回滚已迁移项**）、`movePage(±1)`（与相邻页交换 `screen_order`，之后 order 重新紧凑化）
- `DbOp` 增加 `InsertScreen` / `DeleteScreen` / `UpdateScreenOrder`；`DiffCalculator` 保证新页先建、删页排最后（等图标迁移完）
- `VivoLauncherAdapter.insertScreen`：`PRAGMA table_info` 动态取列，**以现有页为模板复制整行**再覆盖 `_id` / `screen_order` —— 规避 screen_jan 各版本列不同、可能存在 NOT NULL 无默认值列的问题；无模板行时退化为只写 `_id/screen_container/screen_order` 三列
- `SafeWriter.verifyDB` 增加页校验（新增页存在 / 删除页消失 / order 匹配），不满足同样回滚
- UI：页面 chip 行末尾加「页面管理」chip → `ui/pages/PageManagerDialog`（上移 / 下移 / 删除 / 新增一页）
- 测试：`PageTest.kt` 9 个用例（含"拒绝时不能留下半迁移状态"）

### 4. 易用性改造（P0 + P1）

**P0-1 写库后提交内存状态（修 bug）**：原先 `doApply()` 成功后只更新 `maxId`，workspace 仍保留 `isNew`、负 temp id 与旧的 `original*`，导致第二次「应用」对同一批行重复 INSERT → 主键冲突 → 整个事务失败。
- `DiffPlan` 增加 `idMap`（temp → real），新增 `domain/layout` 顶层函数 `commitApplied(ws, plan)`：先把子项 `container` 从 temp 映射为真实 id，再移除已删行、替换新增行 dbId、重置 `isNew`/`original*`，页同理
- `doApply` 成功分支调用 `commitApplied` 并清空撤销栈；`RolledBack` 分支改为自动 `load()` 重新读取（DB 已回到备份状态，内存必须对齐）
- 顺带修 `hasPendingChanges()`：原先只扫 items，**增删/排序页后「应用」按钮不会亮**

**P0-2 撤销栈**：`domain/layout/WorkspaceHistory`（快照式，上限 20）。ViewModel 所有改动 workspace 的操作统一走 `private fun edit(commit)` —— 先压快照，失败则 `undo()` 回滚现场。覆盖移动/交换/合并/删除/移出文件夹/拖到其它页/一键整理/增页/删页/排序页。入口在「更多 → 撤销上一步」。

**P0-3 删除二次确认**：`deleteSelected` 拆成 `requestDelete()` + `confirmDeleteNow()`，弹窗提示；删除文件夹时额外告知「N 个子项会散落到桌面」。

**P0-4**：底部提示条去掉调试文本 `(rev N)`。

**P1-5 搜索定位**：TopAppBar「搜索」展开搜索栏，按标题/包名匹配（上限 50 条），结果 chip 显示「应用名 · 所在页」，点击 → 跳到该页并选中（文件夹内的项直接打开文件夹）。

**P1-6 页面管理**：入口从 chip 行末尾移到 TopAppBar「页面」（12 页时不用横向滑到底）；列表改为「第N页 · N 项」，空页一眼可识别。TopAppBar 现为 `搜索 / 页面 / 更多 / 应用`，「更多」下拉收纳 撤销 / 一键整理 / 备份恢复 / 重新读取。

测试：`HistoryTest.kt` 7 个用例（含关键的「commit 后再 plan 不得产生 INSERT」）。

### 5. 代码审核与修复（2026-09-26）

| # | 问题 | 处理 |
|---|------|------|
| 1 | `AutoArranger.arrange` 用 `ws.pages` 全量取页，**未排除已标记删除（尚未写库）的页** → 一键整理会把图标塞进用户看不见的页 | 改为 `pages.filter { !it.isDeleted }` |
| 2 | 图标缓存按 `dbId` 存，写库后新增行 id 从临时值变真实值，**缓存键失效 → 新图标变灰块** | `doApply` 成功分支先 `remapIconCache(plan.idMap)` 再 `commitApplied` |
| 3 | `undo()` 未清 `folderOpen` → 撤销掉新建的文件夹后，文件夹弹窗会残留一个已消失的对象 | `undo()` 增加 `folderOpen = null` |
| 4 | `nextTempId()` 从 -1 递减，**会撞上保留值 -100(桌面) / -101(Dock)**（新建 100 个文件夹时） | 递增时跳过这两个保留值 |
| 5 | 删除文件夹时子项回落页用 `pages.firstOrNull()`，可能是一张已标记删除的页 | 改用 `visiblePages().firstOrNull()` |
| 6 | `ItemState.componentClass()` 恒返回 null（`substringAfterLast('/')` 的结果不可能再含 '/'），且无调用点 | 删除死代码 |
| 7 | `dropOnCell` 的 `edit {}` 恒返回 true → 放置失败也会压入一条"无变化"快照，用户点撤销像没反应 | 返回真实结果 |
| 8 | 搜索结果每次重组都遍历全部 item（拖拽时会反复触发） | `remember(searchQuery, revision)` |

回归用例：`arrangeIgnoresPagesMarkedDeleted`（1）、`tempIdsSkipReservedContainerIds`（4，构造 120 个新文件夹穿过 -100/-101）。

## 验证状态

| 项目 | 结果 |
|------|------|
| `:app:testDebugUnitTest`（50 个 = 18 分类/收夹 + 13 编辑/重命名 + 10 历史/提交 + 9 页面） | 通过 |
| `build_debug.bat` → `app-debug.apk` | 通过 |
| APK 内含 `assets/category_mapping.json` | 已确认打包 |
| 真机 E2E | 2026-09-26 已做（设备 10AF59276U006BH / vivo PD2419） |

## 真机验证（6，2026-09-26，设备 10AF59276U006BH / vivo PD2419）

- 装 APK：原 MT管理器 / 酷安 / Vivo助手 / SakiSU / folder_tools 灰块消失（图标四级兜底加载生效）
- 整理预览：分类概览 + 分页结果正确；应用后 vivo 桌面按类别归页，复用 SafeWriter 备份与回滚
- 页面管理：新增一页 / 上移下移排序 / 删页（图标迁移），应用后 vivo 桌面页数与顺序一致
- 撤销：删除 / 移动 / 整理等操作可逐步撤销
- 搜索定位：按标题 / 包名命中结果，点击跳到所在页并选中（文件夹内项直接打开文件夹）

## 6. 一键整理收夹 + 文件夹重命名（2026-09-26）

### 收夹（`AutoArranger`）

规则（用户确认）：同类 **≥4** 才收夹、每夹 **9** 个（`GridConfig.folderCapacity = folderColumns × folderRows`）、超出续编号「影音 2」；**未分类不收夹**，排在各类文件夹之后；重排完仍进预览对话框确认，误判可走「更多 → 撤销上一步」。

- `Placement.members` 非空 = 一个**待新建文件夹槽位**，此时 `Placement.item` 只是承载标题的占位对象（不进 workspace、不落库）；`ArrangeResult` 新增 `newFolderCount` / `folderCountFor(cat)`，`placedCount` 改按图标数计（夹按成员数计），`movedCount` 把收进夹的成员一律计为移位
- `Category.byLabel()`：按类别名认领（含「影音 2」这类续编号），所以用户手工命名成类别名的文件夹也会被排到该类附近
- 既有文件夹仍整体移动（`slotsOf` 里与 `apps` 分开），不会被并入新夹，也不参与 ≥4 阈值计数
- `AutoArranger.apply(ws, result, editor)` 新增 editor 形参：单项直接改坐标，文件夹槽位调 `WorkspaceEditor.createFolderAt(x, y, container, screen, members, title)`（新增 `title` 形参与返回值，缺省 `DEFAULT_FOLDER_TITLE`，走 `nextTempId()` 的保留值避让）
- 修一个既有缺陷：`unplaced` 项保持原位，但原先的占用表不把它们算进去 → 已排好的图标会和它们**重叠**（同一格两行）。现在把未安排项的原始格子加入占位后重排，循环至收敛（每轮只新增占位格，必然终止）
- 预览对话框补：将新建 N 个文件夹 / 分类概览显示「社交 x12（2 夹）」/ 说明未分类不收夹

**容量副作用（需真机感受后再定）**：收夹让每格承载量从 1 涨到 9，图标多的桌面整理完可能塌缩——如 30 社交 + 5 游戏 + 1 未分类 = 36 项，从占 2 页变成只占 5 格 + 1 图标，桌面几乎清空（图标没丢，都在夹里）。若真机反馈太激进，方案是按页配额（`per-page 预算 ≈ 桌面格数/2`，约 14 格）：预算内放文件夹、超出的同类图标保持散放，即「夹 + 散图标」的折中。当前实现未加此约束。

### 重命名（只做文件夹）

- `ItemState.originalTitle` / `titleChanged()`；`MAX_TITLE_LEN = 12`（与网格 label 两行截断一致）
- `WorkspaceEditor.renameFolder(folder, raw)`：trim + 限长，空名拒绝；`WorkspaceEditor` 只允许文件夹（桌面图标名来自 PackageManager，改库不保证生效，故不开放）
- `DbOp.UpdateTitle` → `UPDATE location_jan SET title=?, modified=1` + 同步 `attribute.title`（该表可能无此行，0 行受影响无害）；新文件夹仍走 INSERT（`InsertRow.title` 已带上改名）
- `SafeWriter.verifyDB` 新增 `verifyTitles`：`UpdateTitle` 与新建文件夹行的标题必须读回一致，否则照旧自动回滚
- `DiffPlan.renamed` 计入确认弹窗文案；`hasPendingChanges()` 纳入 `titleChanged()`；`commitApplied` 重置 `originalTitle` 基线
- UI：文件夹弹层底部「重命名」按钮 + `RenameFolderDialog`（输入框限 12 字，保存仍需点「应用」写库）；选中文件夹时底部操作条也给「重命名」

### 测试

50 个 JVM 单测全绿（18 分类/收夹 + 13 编辑/重命名 + 10 撤销与提交 + 9 页面）。新增覆盖：同类收夹建夹与成员归位、溢出续编号「影音 2」、未分类与小类不收夹、既有文件夹按类别名认领、未安排项不再与已排布项重叠、`UpdateTitle` 单一 op、限长与空名拒绝、新文件夹走 INSERT、commit 后标题基线复位、撤销恢复标题。

`build_test.bat` / `build_debug.bat` / `build_release.bat` 的取 JDK 逻辑本轮修好：双击 bat 的新 cmd 里没有 `MISE_DATA_DIR`，`mise where java@21` 解析不到 mise 数据目录（在 `D:\dev\mise\data`，bash profile 才有环境变量）返回空；同时系统级 `JAVA_HOME=D:\dev\jdk-11.0.9.1+1` 会被原逻辑保留（历史上"直接跑 gradle 失败"的根因）。现在三个脚本的顺序是：`mise where java@21` → 扫描 `%MISE_DATA_DIR%` / `%USERPROFILE%\.local\share\mise` / `D:\dev\mise\data` 的 `installs\java\21*`（取最后一个含 `bin\java.exe` 的，即 21.0.2）→ 都没有才回落到原 JAVA_HOME，并加 `-Dorg.gradle.java.home` 传给 gradle。干净环境（清掉 MISE_DATA_DIR、继承 JDK 11）实测 test 与 debug 均通过。

### 待真机验证

1. 收夹后的整体观感与是否需要按页配额（见上）
2. 文件夹改名在 force-stop 重载后 vivo 是否保留自定义标题（`location_jan.title` 不被回填覆盖）；若被覆盖，`verifyTitles` 会在写库当场判定不一致并自动回滚，不会留下坏桌面

## 7. XP插件文件夹 + 钉选首页应用（2026-09-26）

- `Category` 新增 `XP("xp", "XP插件", 10)`，OTHER 顺延为 11（仍排最后）；成员规则全部走
  `assets/category_mapping.json`（id `"xp"`，已预置 xposed/lsposed/edxposed/zygisk/riru 关键词、
  LSPosed/Xposed Installer 包名、标题「模块」），改 JSON 即可增删成员，无需重编译
- `PinnedApps`：7 个钉选包名（MT管理器/QQ/微信/酷安/高德/RikkaHub/支付宝），精确匹配、缺失跳过、
  重复包名取首个；钉选项不参与分类与收夹（RikkaHub 命中 XP 规则也上首页）
- `AutoArranger`：槽位顺序改为「所有文件夹（新建+既有）→ 钉选 → 其余」，首页天然形成夹在前、
  钉选随后；钉选项从既有文件夹/Dock 强制抽出，抽空后剩 <2 成员的夹解散（`apply` 标记删除 +
  页满成员兜底 `placeFirstFree`，无处安放才删除，与手删夹行为一致）
- 单测 59 个全绿（新增 9：XP 类别与标签认领/映射规则/≥4 收夹、钉选顺序/缺失跳过/去重、
  抽出解散/夹保留/Dock 抽出；6 个既有用例换用非钉选包名保持原意）
- 终审修复（2026-09-26）：
  1. **钉选包名大小写敏感**（Critical）：`pinnedItems` 对被查包名做了 lowercase 却与
     `PinnedApps.packages` 原样列表比对，`com.eg.android.AlipayGphone`（含大写 G）永不命中
     → 改为先把 wanted 列表整体 lowercase 再比对；`DefaultRules.exactPackages` 的支付宝键
     同步改全小写（`AppClassifier.classify` 同样小写化被查包名；JSON 映射表无碍，
     `CategoryMappingLoader` 读入时已 lowercase 键）
  2. **同包名多处时优先取桌面实例**：候选按「桌面在前」稳定排序后再去重，避免抽走 Dock
     里的那份留下空洞、桌面那份反而被收进夹（`duplicatePinnedPrefersDesktopOverDock` 锁定）
  3. **预览口径统一**：`total` 计入钉选项（与 placedCount/movedCount 一致）；新增「钉选到
     首页 N 项；解散 N 个被抽空的文件夹」提示行；说明文案改为「widget 与未钉选的 Dock 项
     不参与重排…（被钉选项抽空后剩不到 2 项时解散）」
  4. `AutoArranger` KDoc 中重复的两段钉选描述合并为一句；补 XP 续编号用例（11 项 → 「XP插件」
     +「XP插件 2」，尾组只剩 1 项时刻意不成夹）
  5. 单测 62 个全绿（59 + 新 3），`build_debug.bat` 通过
- 实机包名核对（设备 10AF59276U006BH，`pm list packages`）：7 个钉选包名中 RikkaHub 实际为
  `me.rerere.rikkahub`（原按 `cc.rikka.rikkahub` 写错，会被静默跳过），其余 6 个与实机一致，已改正；
  可命中 XP 规则的模块有 9 个（org.lsposed.manager 及 leo.xposed.*、junzi.xposed.hook、
  fansirsqi.xposed.sesame、lin.xposed、com.xposed.woodpecker、com.noobexon.xposedfakelocation、
  io.github.chsbuffer.revancedxposed 等，均靠 `xposed` 关键词命中），≥4 会建「XP插件」夹

### 真机验证（2026-09-26，设备 10AF59276U006BH / vivo PD2419）

adb 代跑点击：更多 → 一键整理 → 采用此排布 → 应用（确认框「移动 220 项 / 新增 1 项 / 删除 0 项」），
桌面强启重载后再用「重新读取桌面」从库里读回第一页网格：

```
row0  系统      系统 2   社交    影音
row1  影音 2    购物生活  购物生活 2  金融支付
row2  金融支付 2 办公效率  拍摄美化  实用工具
row3  XP插件    folder_tools  folder_name  folder_name
row4  测试改名   MT管理器   QQ      微信
row5  酷安      高德地图    RikkaHub 支付宝
row6  视频下载去水印 …（其余图标）
```

- 预览口径正确：`钉选到首页 7 项`、`XP插件 10 项 → 1 夹`、可重排 230 项与 placedCount 一致
- 首页布局符合设计：所有文件夹（新建 + 既有）在前 → 7 个钉选应用按 `PinnedApps` 顺序 → 其余图标
- 支付宝与 RikkaHub（改对包名后）确实上了首页，验证了大小写修复
- XP 模块 10 个命中规则 → 9 个进「XP插件」夹、剩 1 个散放在钉选之后（尾组只剩 1 项不成夹的既有语义）
- 写库读回一致，SafeWriter 验证通过；上一轮的文件夹改名「测试改名」在强启重载后仍保留
  （说明 vivo 采用 `location_jan.title`，桌面图标标题重命名这条路可调研）
- 顺带观察：桌面上有两个夹的标题在库里是字面量 `folder_name`（vivo 未解析的字符串资源名），
  这类夹认领类别时会落到未分类

### 仍待真机验证

1. XP 规则误伤面（`模块` 标题关键词、`riru`/`zygisk` 关键词命中过宽时改 JSON 即可）
2. 钉选追加到 11 项 + 自动加页后的首页布局（见第 8 节，写库验证待做）
3. 「被抽空的夹剩 <2 成员自动解散」——抽出本身已在真机触发过（设置从系统夹、LSPosed 从 XP插件夹
   抽出，两夹剩余成员都 ≥2 故未解散），解散分支仍只有单测覆盖

## 8. 一键整理自动加页（2026-09-26）

**起因（实机数据）**：直接读 `launcher.db` 核对，本机是 vivo 标准桌面模式，207 个已装应用全部平铺在
桌面上——8 页 × 28 格 = 224 格**一格不空**（另有 17 个桌面级文件夹项）。钉选追加到 11 项后，
预览报「页面不足：200 项放不下」，且旧的「未安排项占位后重排、循环至收敛」机制在这种满桌面上
崩得很厉害：226 个槽位最后只排出 26 项。

**决策（用户选定）**：格子不够就**自动新增桌面页**承接余下图标；未分类既不收夹也不被挤掉，
不采用「未分类也收夹」或「未分类完全不参与整理」两种方案。

- `layoutOf`：现有页铺满即追加待新建页，screenId 用临时负值（`TEMP_SCREEN_START = -200` 起递减，
  避开桌面 -100 / Dock -101），排在最后不影响首页
- `ArrangeResult` 新增 `newScreens`（临时 id 列表，按顺序）；删除 `unplaced` 字段
- `apply()`：按 `newScreens` 逐个 `editor.addPage()` 拿真实页 id，回填到该页所有 placement 的
  `screen` 上（含 `createFolderAt` 的 screen 实参）
- **删掉整套「放不下」机制**：`merge()` / `originalCells()` / 收敛 while 循环 / `LaidOut.unplaced`
  ——自动加页后每个槽位必然有位可落，这些代码不可达；对应地 `arrangeReservesCellsOfUnplacedItems`
  改写为 `arrangeAddsPagesWhenCellsRunOut`（31 项 / 1 页 → 新增 1 页、两页格子不重叠、
  临时 id 已换成真实页 id）
- UI：预览与提示由「页面不足：N 项放不下」改为「格子不够，将新增 N 页」
- 写库安全：新页沿用页面管理既有的 `DbOp.InsertScreen` 路径，`SafeWriter.verifyScreens`
  会在写库当场核对页是否存在，失败照旧自动回滚

单测 63 个全绿。

## 9. 长按拖拽失效修复（2026-09-26）

**症状（用户报）**：编辑器里长按拖动图标完全无反应（连拖拽高亮都没出现过）；「点选 → 点空位」正常。

**回路**：手势在 JVM 单测里没有可信接缝，改用真机 adb 注入 —— `scripts/drag-check.py drag|tap`。
判据是不可伪造的净效果：把 A 移到 B 上应合并出「新文件夹」且落在 B 的格子、A 离开原格。
`tap` 模式走用户确认好使的点选路径、终点相同，作**阳性对照**（tap 绿 + drag 红 才说明断点在长按拖拽）。
约 40 秒一轮，全程只改内存、结束自动「重新读取桌面」还原。

**病因（假设 H2 被证实）**：`WorkGrid` 里 `ordered = pageItems.filter { it !== dragItem } + dragItem`
——`onDragStart` 一设 `dragItem`，被拖项就从原位置被挪到列表末尾；`forEach` 没有 key，
Compose 按顺序复用节点，于是该图标的 `pointerInput` 节点被销毁重建，拖拽协程在启动当帧即被取消。
表现正是「长按毫无反应」，而点选走网格 Canvas 的 `tapCell`，不受影响。

**修复**：遍历顺序改为稳定遍历 `pageItems`，被拖项的层级用 `Modifier.zIndex(if (dragging) 1f else 0f)` 提升
（原本靠重排来实现「拖拽时盖在邻居之上」，改用 zIndex 后节点身份不变、手势不被打断）。

**否定结论（避免下次踩同样的误判）**：第一版回路用「按住期间 `uiautomator dump` 看图标坐标是否跟手」作判据，
**测出来是假红**——手指按住不动时 a11y 等不到 idle，dump 直接返回空内容，看起来像「图标没动」。
像素截图对比（按住前后/是否位移）同样不可信：vivo 桌面零位移也判「跟手」，是动画噪声。
最终有效的是上面那个净效果判据。

**顺带发现**：库里标题为字面量 `folder_name`（vivo 未解析的资源名）的文件夹，在桌面重载后
被 vivo 回填成首个子项的应用名（实测夹 350 标题 `folder_name` → 显示「Dhizuku」）；
而用户自定义标题「测试改名」不会被覆盖。对「桌面图标标题重命名」这条待办是有用的正向证据。

单测 63 个全绿；真机 `drag-check.py drag` 与 `tap` 双绿。

## 下一步

- 改动编辑器手势/网格渲染后，跑 `python scripts/drag-check.py drag` 与 `tap` 做回归（JVM 单测覆盖不到手势）
- 真机验证本轮：一键整理收夹效果、文件夹改名是否被 vivo 保留（改名保留已于 7 节真机验证确认）
- 收夹是否改成按页配额（避免桌面塌缩，见 6 节末）
- 桌面图标标题重命名（需先调研 vivo 是否采用 `location_jan.title`）
- 按使用频率归页（需 usage stats 权限，尚未调研）
- 抽屉页（`location_jan_drawer`）编辑
