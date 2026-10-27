# DeskFlow

vivo 桌面（com.bbk.launcher2）图标整理工具。Root 环境下**直接读写 launcher 的 `launcher.db`**，在自研编辑器里完成拖拽、收夹、改名、换页等操作后一次性写回桌面，绕过桌面自身的手势限制。

> ⚠️ 本工具会强制重启桌面并直接修改系统应用数据库，仅限已 Root（KernelSU/Magisk）设备自用。任何写库前自动备份、写后读回验证、失败自动回滚，但风险自负。

## 功能

- **读取渲染**：解析 vivo 桌面数据库，按页/Dock/文件夹网格渲染真实布局（网格行列从 launcher prefs 动态读取，随系统「桌面布局」设置自适应；图标经 PackageManager 还原、异步就绪逐个刷新，解码结果落磁盘缓存，冷启动近即显）
- **可视化编辑**：点选移动、长按拖拽、左右滑动翻页、拖拽合并成文件夹、重命名（文件夹与应用图标，限 12 字）、拖出/移入夹内、跨页迁移（拖到屏幕边缘或页签）、页管理（增删/排序）
- **批量多选**：多选模式下点图标加减选、点文件夹整批收进、点页签/Dock 整体迁移、批量删除，一次操作一次撤销
- **搜索与快捷操作**：按名称/包名搜索桌面项，命中项可打开应用、强制停止（root `am force-stop`）、跳系统应用信息页、定位到桌面所在位置
- **一键整理**：「锁定现有布局 + 只收散放图标」语义——首页、既有文件夹、只读项（widget）、Dock 全部原页原格；仅把**非首页散放应用**按类别（≥4 同类）收进以类别命名的文件夹，格子不足自动新增桌面页
- **冷门收夹**：按系统使用统计找出 30 天未启动的散放应用（含首页，图标带灰色角标），一键收进「冷门」文件夹回收黄金格子；需「使用情况访问」权限，预览确认后才动手
- **添加应用**：从已安装应用列表（拼音排序、可按名称/包名过滤）选一个落到当前页空位，标准/抽屉两种桌面风格通用
- **写回链路**：备份 → 外部改动检测（编辑期间桌面自身改过库则弹窗确认，防静默覆盖）→ 事务写入 → 推回原路径（恢复属主/SELinux 上下文）→ 强启桌面 → 读回逐条验证，不一致自动回滚
- **撤销上次应用**：按反向操作写回，把桌面恢复到上次「应用」前的布局（编辑器有未写改动时不可用）
- **布局方案导入导出**：把当前布局（页/夹/格位/改名）存成语义化 JSON 存档，可再套用还原或迁移到其他设备；同机按行 id 精确恢复，跨机按包名匹配，多出的应用自动挪到备用位
- **备份/恢复**：保留最近 10 份数据库备份，可一键还原

## 桌面风格适配

vivo 每个桌面风格各有一套工作区表，`desktopStyle.currentDesktop` 指针决定在用表（真机实测：`1000`=标准 `location_jan`，`1002`=抽屉 `location_jan_drawer`）。切风格**不清表、不改原数据**，因此：

- 全链路（读/写/验证/页管理）按指针动态选表，未知值直接报错拒写，防止把改动写进不再显示的备用表
- 各风格表的 `_id` 共享同一命名空间，新行 id 取全部 `location_*` 表 + `attribute` 的全局 MAX+1
- 抽屉风格的「全部应用」清单不落库，无法通过改库重排

## 技术与结构

Android / Kotlin / Jetpack Compose（Material3），单 Module，分层：

```
app/src/main/java/com/deskflow/
├── data/        # 数据库访问（libsu root shell + nsenter）、vivo 表适配、备份、分类映射 JSON
├── domain/      # 纯 Kotlin：workspace 模型、编辑器、整理算法、Diff 计划（可 JVM 测试）
└── ui/          # Compose 编辑器、整理预览、页面/备份/选应用对话框
```

分类规则外置于 `assets/category_mapping.json`，改规则免重编译。

## 构建

要求：JDK 21（推荐 mise 管理），Android SDK，连接一台已 Root 并授权 shell 的设备。

```bat
build_test.bat        :: 运行 JVM 单元测试（69 例）
build_debug.bat       :: 调试 APK → app/build/outputs/apk/debug/
build_release.bat     :: 发布 APK（需本地签名配置）
```

`adb install -r app\build\outputs\apk\debug\app-debug.apk`

首次启动会在 Root 授权弹窗后复制 launcher 数据库到应用私有目录，之后所有编辑都在副本上进行，只有点「应用」才写回。

## 回归脚本

`scripts/drag-check.py`：adb 代跑的长按拖拽/点按回归（JVM 单测覆盖不到 Compose 手势），改动编辑器手势或网格渲染后 `python scripts/drag-check.py drag` 与 `tap`（后者为阳性对照）。

## 文档

- [docs/dev-log.md](docs/dev-log.md)：逐轮开发记录与真机验证结论（含踩坑：KernelSU 挂载命名空间需 nsenter、FUSE 文件应用读不了需走内部目录、AGP9 内置 Kotlin 等）
- [docs/lessons-learned.md](docs/lessons-learned.md)：经验教训汇总

## 已知限制

- 仅适配 vivo OriginOS 桌面（com.bbk.launcher2）的表结构，且值映射来自单台 X200 实测
- 应用图标名称不能改（显示名来自 PackageManager），只有文件夹标题可改
- 抽屉风格下「全部应用」页不可编辑（数据不落库）
- 多用户/分身空间的桌面数据（`/data/user_de/1xx`）未支持，只读写 user 0
