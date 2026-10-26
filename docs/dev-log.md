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

## 10. 收夹切块改为「尾组不足 4 个并回上一夹」（2026-09-26）

**规则（用户确认）**：同类图标仍按每夹 9 个（`GridConfig.folderCapacity`）切块，但**尾组不足 `FOLDER_MIN_ITEMS`（4）个时并回上一夹**，
不再单独成夹。于是 12 个同类 = 一个 12 个图标的夹；13 个 = 「影音 9 个」+「影音 2 4 个」；21 个（9+9+3）= 9 + 12。
改前的行为是 12 → 9 + 一个只有 3 个图标的小夹（只有尾组恰好 1 个时才散放桌面），与「不留小夹子」的初衷相悖。

**改前实测：夹容量不是 9**（推翻了设计文档与 `GridConfig` 的 3×3 假设）。查夹 353「XP插件」现状：

- vivo 自己的 a11y 描述为 `XP插件,文件夹,10个应用`，即 10 个子项是合法状态
- 点开后 `uiautomator dump` 出来是 **3 列 × 4 行**，10 个全部渲染（第 4 行 1 个）
- 桌面上的夹预览只画 rank 前 9 个：第 10 个「Bug」（rank 9）不出现，只有打开夹才能看到——
  容易误读成「第 10 个丢了」。渲染顺序由 `rank` 决定，夹内 `cellX/cellY` 不参与排版
  （该夹成员 `cellY` 已到 3，超出 3×3 仍正常显示）

结论：夹是「3 列多行、可滚动」，标称容量只用于**切块**，不是硬上限。因此「一个夹装 12 个」（3 列 4 行）在设计上可行，
但**12 个的渲染尚未实测**（本轮只验过 10 个）。

**代码改动**：

- `AutoArranger.slotsOf`：`chunked(capacity)` 后过 `mergeUndersizedTail()`（尾组 < `FOLDER_MIN_ITEMS` 且存在上一夹时合并），
  删掉原来的「尾组 1 个散放桌面」分支——现在所有切块都成夹
- `Models.kt` 注释改正：`folderCapacity` 是切块用标称容量，不是硬上限
- `ArrangeDialog` 预览文案补「尾组不足 4 个时并回上一夹」

**测试**：65 个 JVM 单测全绿（改前 63）。替换/新增覆盖：`twelveOfAKindStayInOneFolder`（12 → 单夹，并断言成员按行优先紧密排满
cellY 0..3）、`tailSplitsOnlyWhenItFillsAFolder`（13 → 9+4 续编号）、`undersizedTailMergesIntoPreviousFolder`（21 → 9+12）、
`xpTailMergesIntoOneFolder`（11 → 单夹，替代原「尾组 1 个散放」用例）；`arrangeFillsPagesInCategoryOrder` 的 30 个影音
由 4 夹改为 3 夹（9/9/12）。

**待真机验证（用户自行执行）**：建一个 12 个图标的夹确认第 4 行渲染正常（理论上与已验的 10 个同构）。

## 11. 一键整理改为「锁定现有布局 + 只收散放图标」（2026-09-26）

**规则（用户确认）**：把当前桌面当模板。`一键整理` 不再重排整张桌面，只处理「新图标」。

- **原位锁死**：首页（第一张未删除页）上的全部图标、任意页上的既有文件夹、只读项（widget 等）、
  Dock 项——保持原页原格；**首页连空格都不给新夹用**（`fixed[home] = 全部格子`）。
- **待归类 = 散放**：非首页桌面上、既不是文件夹也不是只读项的应用/快捷方式。
  同类达到 `FOLDER_MIN_ITEMS`（4）个就收夹（每夹 9 个、续编号、尾组 <4 并回上一夹，见第 10 节）；
  不满 4 个或未分类的散放项**原地不动**。
- **新夹落位**：优先用本次腾空的格子（被收走项的原位），其次非首页空位；兜底追加新页。
- 首页之外的「页 + 格」也锁死：既有夹不再随整理挪位，散放项也不再被压缩重排。

**改动**：

- `AutoArranger.arrange` 重写：`isLooseApp(item, home)` 单一判据挑出候选；
  `frozen = 桌面项 − 被收走者` 全部进占位表与预览；`layoutOf` 只铺新夹，锁定项按原位登记，
  所以预览看到的是「原布局 + 新夹」而不是只有新夹。
- `ArrangeResult` 删除 `pinned` / `dissolvedFolders`，新增 `looseCount` / `lockedCount`；
  `placedCount` 改为「收进新夹的图标数」。
- **删除 `PinnedApps.kt` 与整套钉选机制**（`pinnedItems` / `pinnedSlots` / 从夹与 Dock 强制抽出 /
  抽空解散 / `apply` 里的解散兜底），以及为此存在的 7 个钉选用例。
- **新夹命名跳过既有夹占用的标题**（`claimLabel`）：既有夹是锁定项、不再与新夹合并，
  名字撞车会在桌面留下两个同名的「影音」，所以编号往后顺延（首页已有「影音」「影音 2」时，
  新夹叫「影音 3」）。
- `ArrangeDialog` 文案重写：标题改「按分类收拢散放图标」，删「钉选到首页」「解散文件夹」两行，
  分类概览对不收夹的类别改显「→ 原地不动」，分页行加 `*` 标记该页含原位保留项，
  无候选时显示「没有可归类的散放应用，桌面不会变动」。

**真机数据离线核对（本机 124 个桌面项）**：页序 `1,6,8,11,9`（`screen_jan: _id|screen_order` =
`1|0 / 6|1 / 8|2 / 11|3 / 9|4`，另有 `0|0` 是 Dock 行 `screen_container=-101`、`5|0` 是智能桌面
`screen_container=0`，都不在 `VivoLauncherAdapter` 取的 `screen_container=-100` 集合里），首页 = screen 1。

| 项 | 数 |
|----|----|
| 桌面项合计 | 124 |
| 锁定（首页 28 + 非首页既有夹 9） | 37 |
| 待归类散放应用 | 87 |
| 新建夹 | **0** |
| 移位 | **0** |

即：**当前桌面上点一次整理是纯空操作**，与「封存现有布局」的预期一致。代价是 87 个散放项
**全部落到「未分类」**（GKD / Shizuku / InstallerX / DSU Sideloader / ReVanced Manager /
数据备份 / 星野Scene / 剪映 / 美图秀秀 / 什么值得买 / 铁路12306 …），而未分类不收夹。
根因是 `DefaultRules` 关键词表偏薄，加上「散放=待归类」只处理非首页项。**关键词是否扩充待用户定**
（扩充会真的移动这 87 项中的一部分，与「不打扰」相冲突，所以没有自行决定）。

**测试**：62 个 JVM 单测全绿（改前 65）。新增覆盖：`homePageIsNeverRearranged`（首页 6 项一格不动、
0 夹 0 移位）、`newFolderNeverLandsOnHomePage`（首页有 27 个空格也不用）、
`existingFolderWithItsChildrenStaysPut`、`readOnlyAndDockItemsAreUntouched`（2x2 widget 占位后
新夹落在腾空的 (2,0)）、`undersizedAndUnclassifiedLooseAppsStayPut`、
`newFolderTakesTheVaultedCells`、`newFolderNameSkipsTitlesTakenByLockedFolders` /
`newFolderNamesStayUniqueAcrossChunks`、`arrangingTwiceChangesNothing`（整理过一遍后二次为空操作）、
`overCapacityFallsBackToNewPage`（1×1 退化容量下加页兜底，防死循环）。

## 12. 抽屉风格适配（2026-09-27）

**症状**：手机桌面从「标准风格」切到「抽屉风格」后，DeskFlow 整体失效——读到的还是切风格前的旧桌面，写回后面面无变化。

**诊断**（对比线上 `launcher.db` 与 9 份标准时代备份）：

- vivo 切换桌面风格**不清表、不改原数据**，而是每个风格各有一套工作区表，`launcherStyle` 无关；由 `desktopStyle` 表单行指针 `currentDesktop` 决定哪套在用：**1000=标准（`location_jan`/`screen_jan`）、1002=抽屉（`location_jan_drawer`/`screen_jan_drawer`）**。该映射系单台 X200 实测（备份均为 1000、切风格后线上为 1002），非官方文档语义。
- 抽屉线上表组实测：桌面仅 1 页 19 图标 + Dock 4 项 + 系统「工具」夹（`_id=0`）；`location_jan` 324 行原封不动躺在库里。App 硬编码 `location_jan` → 读写的是**不再显示的备用表**，这就是失效根因。
- `_id` 跨样式表共享命名空间：切抽屉时复用原 id（`应用商店`两风格下都是 `_id=10`；抽屉表 MAX=35 而标准表 MAX=361）；`attribute`（318 行）跨风格共享、切风格未动。**新行 id 必须取全部 `location_*` 表 + `attribute` 的全局 MAX+1**，按在用表单算会撞备用表占号（如 36 撞标准表第 36 行）。
- `location_allapps`（抽屉全部应用表）实测 **0 行**：抽屉里"全部应用"清单不落 DB（由 launcher 按 PackageManager 现生成），**改库排不了抽屉页**——路线图里"抽屉页编辑"目标就此证伪。
- 抽屉桌面只有 1 页 ⇒ 所有图标都是首页锁定项 ⇒ 一键整理=纯空操作。这是语义设计（第 11 节）的直接推论，不是 bug。

**实现**：

- `VivoDesktopStyles.resolve()`：null（无 `desktopStyle` 表或值为 NULL，按旧版处理）/1000→标准，1002→抽屉，**其余值抛 `UnsupportedDesktopStyleException` 拒绝写库**，错误经 `RootError.UnsupportedDesktopStyle` 上屏并带 `currentDesktop` 实际值。
- `VivoLauncherAdapter` 全部 SQL（read/apply/insert/insertScreen/readScreens/readPositions/readTitles）经 `resolveTables(db)` 选表；`globalMaxId()` 用 `sqlite_master` 收集全部 `location_*` + `attribute` 表做 UNION MAX。
- 标准风格回归确认：1000/表缺失路径解析结果与改造前逐字一致；唯一差异是 id 分配更保守（全局 MAX 恒 ≥ 原单表 MAX）与未知值从静默误写改为报错。

**回滚点与备份**：写库前用 root `cat` 拉线上抽屉态 DB（`.tmp/ld2.db`）；App 内置备份只在「采用」时生成，当前列表里全部是标准时代的，**还原旧备份=连样式指针一起切回标准桌面**（自洽但会覆盖抽屉态，勿手滑）。

**测试**：新增 `DrawerTest` 4 例（样式映射含未知值拒绝、跨表 id 分配、单页桌面空操作语义、多页散放收夹），共 66 例全绿；`adb install -r` 已装机（10AF59276U006BH），真机点按验证由用户执行中。

## 13. 添加应用到桌面（2026-09-27）

抽屉风格下桌面只剩钉选的 19 个图标，全部应用清单不落库（12 节：`location_allapps` 实测空表），
「把应用放上台面」只能走系统长按抽屉项添加，或 DeskFlow 自己插行。实测 vivo 桌面行的写入格式极简：
`intent = "pkg/activity"`、`itemType = 30`、`rank = 0`（标准表 115/125 桌面行 rank=0，
少数非零系拖拽残留、语义未证明；夹内子项 rank 0..n 连号但与 cellX/Y 顺序一致，
收夹链路只写 cell 已多次真机验证正常——**rank 非显示必要条件**，新行写 0 即可）。

实现：`WorkspaceEditor.addApp`（当前页首个空位，页满拒绝）+ ViewModel 应用清单
（`queryIntentActivities` MAIN/LAUNCHER，manifest 已有 `<queries>` 无需新权限；Collator 按拼音排序、
label/包名过滤、已在桌面的置灰）+ 「更多 → 添加应用」选择对话框（图标异步加载缓存）。
走既有 `DbOp.Insert` 链路，id 取全局 MAX+1（12 节），标准/抽屉两风格通用（表由 `resolveTables` 选）。
撤销、二次确认、写后验证全部复用现有机制。单测 +3（落位与 INSERT 内容、畸形 intent 拒绝、页满兜底 Dock），共 69 例。

## 14. 首次打开图标全灰，重读桌面才显示（2026-10-25）

现象：冷启动进编辑器网格全是灰块，点「重新读取桌面」立刻正常。重读后显示的其实还是**首次加载的位图**
（`iconCache` 跨 load 保留、dbId 不变），说明图标早加载好了，坏的是「加载完成后通知 UI 重读缓存」这一步。

根因：`iconCache` 是普通 HashMap（非快照态），唯一刷新信号是 `preloadIcons` 结束时的 `revision++`；
但 `revision` 只在 `DeskFlowApp` 顶层作用域被读（`remember(ws, rev)`），Scaffold content 子树（WorkGrid → ItemCell）
没有读它。顶层重组时 `pages`/`chipIds` 内容相等（`Composer.changed` 用 `Intrinsics.areEqual` 结构比较），
strong skipping（Kotlin 2.0+ 默认开）下 content lambda 按捕获（vm、chipIds）记忆化、捕获未变 → 整棵网格跳过，
`iconFor` 永不重读。重读桌面之所以有效，是 Loading→Ready 切走 `when` 分支销毁重组了整棵子树。
点选/拖拽等操作会顺带刷新是靠 `selected` 等 state 的读取，掩盖了这个洞；文件夹对话框标题里的 `rev$rev`
调试残留正是同类问题的痕迹。

修复：`iconCache` 改 `mutableStateMapOf`——图标可用性本身成为可观察态，ItemCell/FolderFace/FolderDialog
在组合期直接观察，IO 线程每写入一张位图对应格子下一帧即刷新，不依赖 revision 通知，也不必在每个渲染处
补读 revision。附带收益：图标随加载逐个浮现（不再整批等完）、快照 map 线程安全（原 HashMap 主线程读 +
IO 线程写本就是数据竞争）。

## 15. 写回冲突检测 + 批量多选 + 撤销上次应用（2026-10-25）

**冲突检测（防静默覆盖）**：原链路的 diff 只基于编辑器内存快照，打开应用到点「应用」之间桌面若自己
改了库（装新应用、桌面端拖动、widget 调尺寸），这些改动会被静默写掉。现在 `SafeWriter` 持有 load 时的
库快照基线，`writePlan` 里现读 fresh 后用 `WorkspaceDiff.summarize` 比对，有差异返回
`ApplyOutcome.Conflict`（人读摘要：位置变动/新增/移除/改名/页面变化），UI 弹「仍要写入（覆盖）/重新读取」。
细节：空 plan 无可覆盖物、不拦；写回验证通过后把写后状态设为新基线（否则二次 apply 误报）；
`restoreBackup` 后基线置空等 load 重建；反向写回走同一检测（`isReverse` 标记提示文案差异）。

**批量多选**：底栏「多选」或菜单进入。多选模式下：点图标加减选（widget 只读项不可选）、点文件夹
= 整批收进该夹（文件夹本身不可加选，收夹用 tap 语义替代拖拽）、点页签/Dock 整批 `placeFirstFree`、
批量删除带确认；长按拖拽在多选下禁用（pointerInput 键加入 `multiSelectMode` 重建手势节点）。
放不下的项留在原位并计入提示。整批包在**一次 `edit{}`** 里 = 一次「撤销上一步」。

**撤销上次应用（反向 diff）**：`ReversePlanner.reverse(ws, plan)` 在 `requestApply` 时捕获（必须先于
`commitApplied`——被删行完整数据、original* 坐标/标题、被删页 originalOrder 此刻才可得）。
反向 ops = 正向 ops **倒序逐个取逆**：Insert↔Delete、UpdatePosition/UpdateTitle 取 original* 值、
InsertScreen↔DeleteScreen、UpdateScreenOrder 取 originalOrder；倒序保证依赖成立（删新页在图标回迁后、
重建被删页在图标回迁前）。会话内新增又删除的行从未落库，反向跳过（不能 Insert 回来）。
执行走既有 `applyPlan`：备份 → 写 → 验证（verifyDB 按 ops 逐条核对，对反向 plan 同样成立）→ 强启 → load 对齐。
入口「更多 → 撤销上次应用」，编辑器有未写改动时禁用（反向 plan 与编辑态无冲突合并语义）；
`restoreBackup`/RolledBack 后失效。附带：`InsertRow` 扩展 `iconPackage/iconResource` 两列，
反向恢复被删行不丢图标兜底信息（双开/快捷方式），adapter INSERT 同步。

提交前 code review 修三处：① 反向 plan 不再在 `requestApply` 预计算，改为写回成功后、`commitApplied`
之前从**实际执行的** plan 派生——冲突「仍要写入」路径下 SafeWriter 用 fresh.maxId 重算正向 plan，
外部加行时 Insert id 与预计算的反向 plan 错位，撤销会误删外部新增行；② 批量「全选本页」排除文件夹、
收夹批次滤掉文件夹（与点选语义一致，防嵌套夹）；③ `baseline` 加 `@Volatile`（load/writePlan 同在
IO 池，收敛可见性）。

单测 69 → 82（反向 plan 9 例：移动/新增/删除/改名/删页/新页+移动/级联删夹/进新夹的倒序断言、
未落库行跳过；快照 diff 4 例）。未真机验证：冲突弹窗真实触发路径（需编辑期间动桌面）、
反向写回的真机回滚正确性——下一步优先。

## 16. 搜索命中项快捷操作：打开 / 强制停止 / 应用信息（2026-10-26）

搜索原来只做「定位」（跳到该项所在页/夹）。现在点命中项弹出操作菜单 `ItemActionsDialog`：
**打开应用**（显式组件 + MAIN/LAUNCHER + NEW_TASK，`startActivity` 失败进 snackbar）、
**强制停止**（`LauncherDbAccess.forceStop` 复用 nsenter 包装的 root shell 执行 `am force-stop`，
成功提示推送会暂停到下次启动；失败提示需要 Root）、**应用信息**（`ACTION_APPLICATION_DETAILS_SETTINGS`
+ `package:` URI，无需 root）、**定位到桌面**（保留原行为，夹内子项会打开所在夹）。

能力边界：只有 intent 为 `pkg/activity` 组件格式的项可打开/停止/查详情（`actionTargetLaunchable`），
文件夹与 widget（intent 为空）仅显示定位。包可见性无需新声明——manifest 已有 MAIN/LAUNCHER `<queries>`。
强制停止不触碰 launcher.db，与编辑/写回链路互不影响；对话框状态在 `load()` 时重置防过期引用。
JVM 测试无可断言的域逻辑（纯 Android Context/Shell 调用），行为需真机验证。

## 17. 网格从 launcher prefs 动态读取（修「桌面一行 5 个、应用里一行 4 个」）（2026-10-26）

**取证**：真机实拉 `com.bbk.launcher2.layout.xml`（user_de/shared_prefs）+ launcher.db 对照——
网格权威来源是 prefs 的 `newCountX/newCountY`（标准）与 `newCountX_drawer/newCountY_drawer`（抽屉）；
当前值 5×9 / 4×7，与 `location_jan` 数据完全吻合（cellX 0..4、cellY 0..8、spanX=5 通栏件）。
`_2` 后缀键（4×7）是旧布局残留——正是当初硬编码 GridConfig=4×7 的来源，用户改过系统「桌面布局」设置后
编辑器没跟着变。库里无网格字段，`desktopStyle` 只有指针；各风格表实际列数不同
（jan=5 列、jan_drawer/feb=4、simple=3），也不能直接从数据反推（桌面稀疏时反推会偏小）。

**实现**：新增 `VivoLayoutPrefs`（纯解析器，可 JVM 单测：正则抠 `<int name=... value=...>`，
2..8/2..12 钳位，缺键整组放弃）+ `LauncherDbAccess.readLayoutPrefs()`（root `cat` prefs 路径）。
`SafeWriter` 的 load 与写后基线读取统一走 `resolveGrid(db)`：先 `resolveTables` 定风格，
再按风格取 prefs 网格，缺失退回默认 `GridConfig()`；风格异常仍包装成 `RootDbException`。
写回前的 fresh 现读（`readChecked`）同样走 `resolveGrid`，保证与 baseline 网格同源。
`adapter.read(db, grid)` 接受外部网格；`VivoSnapshot` 改携 `tables`（保留 styleLabel 访问器）。
UI 的 WorkGrid 列/行/Dock 格数与 Dock 宽高比全部改用 `ws.grid`（原先硬编码 4×7、Dock 5）。
Dock 格数无 prefs 来源，维持 5（实测各风格 dock 都有 cellX=4 即 ≥5 格）。
写回链路（DiffCalculator/EditorServer/AutoArranger 全走 `ws.grid`）无需改动自动适配 5×9。

单测 82 → 86（prefs 解析 4 例：双风格读取、按风格取值、缺键回退、垃圾输入钳位）。
真机验证点：重新读取应显示 5 列 × 9 行，图标与真桌面逐格对齐；改系统桌面布局后再重读应跟随。

## 18. 图标磁盘缓存：冷启动免 PackageManager 逐个解码（2026-10-26）

冷启动 200+ 图标每张都要走 PackageManager 解析（getActivityIcon / 资源表 / getApplicationIcon
层层 try），要数秒，期间格子全灰逐个浮现。新增 `IconDiskCache`（`data/icon/`，应用私有
`filesDir/icons/`）：解码成功的 128px 位图落 PNG，再次加载只需解一张本地图（毫秒级）。

- key = 解析路径 + 包名 + 版本（`act:pkg/cls@ver` / `app:pkg@ver` / `res:pkg:name@ver`），
  应用更新换图标后自然落到新文件；文件名取 key 的 MD5（key 含 `/`、`:`、`@` 不宜直接做文件名）
- 读穿在 loadIcon 每一层内部：命中直接用，未命中解码后回写；写 PNG 先落 `.tmp`（带线程 id，
  并发同 key 不互踩）再原子改名；损坏文件 decode 返回 null 当未命中并删除，下次重新解码
- 添加应用列表（AppPicker）的 `appIcon` 走同一 `act:` key，与桌面图标共享缓存文件
- 清扫：`preloadIcons` 完成后 sweep——超过 30 天未更新的条目与残留 `.tmp` 一并删除
- 包版本 memo 在 `ConcurrentHashMap`（IO 线程访问）；缓存全程 best-effort，任何失败静默降级为
  原解码路径，不影响功能
- 顺手的 UX：snackbar 支持 action——「已应用」提示带「撤销」按钮直达撤销上次应用；
  冲突弹窗把「重新读取会丢弃未应用编辑」改为 error 色独立一行；搜索结果显示命中计数

纯 Android 图形/IO，无 JVM 可断言域逻辑。真机验证点：首装首次打开逐个解码（写缓存），杀进程
重开应显著变快；`adb shell pm path` 确认缓存目录生成于 `/data/data/com.deskflow.debug/files/icons/`。

## 19. 桌面图标标题重命名（2026-10-26）

原实现只允许文件夹改名（当年注释写「库里改 title 不一定生效」）；§9 的文件夹改名实测确认
vivo 读 `location_jan.title` 后，把重命名放开到普通应用图标：

- `WorkspaceEditor.renameFolder` → `renameItem`：任何非只读项（widget 拒绝）可改名，
  去空格/12 字截断规则不变；`UpdateTitle` 链路本来就通用（`titleChanged` → op），零新增写库代码
- 选中底栏对非只读项显示「重命名」；`RenameFolderDialog` → `RenameDialog`（文件夹/图标文案区分）
- 反向 plan 的 UpdateTitle 取逆（originalTitle）原本就存在，应用改名同样可撤销；
  `verifyTitles` 对任意 UpdateTitle 行同样核对

坑（待真机确认）：应用图标的显示名是否真的跟随库 title 未验证——vivo 可能直接渲染
PackageManager label；最坏情况是改名只在编辑器内生效，写库本身无害。

## 20. 使用频率：冷门收夹 + 冷门角标（2026-10-26）

待办「使用频率（PACKAGE_USAGE_STATS）：冷门标记 / 按频率归页」的落地。**语义刻意选「冷门收夹 +
角标」而非全量按频率重排**：§11 已验证用户否决搬动既有布局的整理方式（首页是模板不是整理对象），
冷门收夹是增量形态——预览可见、一次 edit 一次撤销、widget/夹/Dock 不动。

- **数据**：`UsageStatsProvider`（`data/usage/`）。`PACKAGE_USAGE_STATS` 是 AppOps 特殊权限，
  `checkOpNoThrow(OPSTR_GET_USAGE_STATS)` 探测、`ACTION_USAGE_ACCESS_SETTINGS` 引导授权；
  计数用 `queryEvents` 事件流数 `ACTIVITY_RESUMED`（minSdk 28 上与 MOVE_TO_FOREGROUND 同值），
  不用 queryUsageStats 分桶（厂商 ROM 口径不一）。窗口 30 天；无权限/无统计返回空 map
- **领域**：`FrequencyPlanner`（`domain/classifier/`，纯函数 7 例 JVM 单测）。冷门 = 桌面散放应用
  （非夹/widget/Dock，**含首页**——回收首页黄金格子正是价值所在）的包在窗口内零启动（计数缺席也算）。
  <2 项不收；分夹/尾组合并/续编号避让与 AutoArranger 同规则；落位从最后一页向前找首个空格
  （冷门项自己的格子视为已腾出），全满给临时负 id 待 apply 换真实新页
- **UI**：「更多 → 冷门收夹（30 天未用）」→ 无权限弹授权引导（去授权直达系统页，回来重试）；
  有权限弹 `ColdCollectDialog` 预览（夹数/成员样本/落点）；采用走 `FrequencyPlanner.apply` 一次 edit。
  冷门角标：`isColdItem` 在 ItemCell 图标右上角画 6dp 半透明圆点，计数是快照态、加载完成自动浮现

已知边界：统计只覆盖「近 30 天」，双开/快捷方式按 iconPackage 兜底匹配；刚授权后系统可能还没有
聚合数据（提示用户用一会儿再试）；多用户/分身的使用统计不在本 profile 内。

真机验证点：授权引导 → 授权 → 冷门角标浮现 → 冷门收夹预览与桌面实际冷门项一致 → 采用 →
应用 → 桌面出现「冷门」夹且成员正确；撤销上一步可回退。

## 下一步

- 真机验证 20 节：授权引导/角标/冷门收夹全链路（见 20 节末）
- 真机验证 19 节：改一个应用图标标题 → 应用 → 看桌面标签是否跟随（可能 vivo 渲染 PM label，则此功能仅编辑器内生效）
- 真机验证 18 节：首装冷启动慢（写缓存）→ 杀进程重开图标应近即时；更新某应用后其图标应仍正确
- 真机验证 17 节：重新读取后编辑器应为 5 列 × 9 行、与真桌面逐格对齐；Dock 5 格横排
- 真机验证 16 节：搜索 → 点命中项 → 打开应用 / 强制停止（前后 `ps` 或桌面观察进程）/ 应用信息页跳转 / 定位
- 真机验证 15 节：冲突检测（应用前在真桌面拖一个图标再点「应用」，应弹外部改动提示）；批量多选
  （加减选/整页选/收夹/跨页/批量删除 + 撤销上一步）；撤销上次应用（应用一批改动后反向回退，逐项核对桌面）
- 改动编辑器手势/网格渲染后，跑 `python scripts/drag-check.py drag` 与 `tap` 做回归（JVM 单测覆盖不到手势）
- 真机验证本轮（APK 已 `adb install -r` 到 10AF59276U006BH，待点按）：点一次「一键整理」，预览应显示
  「待归类 87 项 · 收进 0 项 · 124 项保持原位不动」、「将新建 0 个文件夹」；采用后桌面**一格都不该动**
  （这是本轮改动的核心判据；锁定项回写的是原坐标，`DiffCalculator` 不产生 op，故应用本身是纯空操作）
- 定 87 个未分类散放项怎么办：扩关键词表 / 允许未分类收夹 / 保持纯不打扰（见 11 节）
- 真机验证 12 子项夹的第 4 行渲染（见 10 节）
- 收夹是否改成按页配额（见 6 节末；11 节改语义后桌面不再塌缩，此项优先级下降）
- 桌面图标标题重命名（vivo 采用 `location_jan.title` 已由文件夹改名实测确认，可直接做；
  注意未解析的 `folder_name` 会被回填成首个子项名，见 9 节）
- 按使用频率归页（需 usage stats 权限，尚未调研）
- ~~抽屉页（`location_jan_drawer`）编辑~~ → 已由 12 节完成表适配；「全部应用」页改库不可行（`location_allapps` 实测空表）
- 真机验证 12 节（APK 已 `adb install -r`）：抽屉桌面上重新读取应见 19 图标 + Dock；拖一格采用后改动应落在 `location_jan_drawer`，`location_jan` 应零变化

---

经验教训汇总（调试判据可信性、Compose 列表顺序、外部事实需实机核对等）见 [lessons-learned.md](lessons-learned.md)。
