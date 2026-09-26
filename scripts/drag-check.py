"""长按拖拽回归检查（真机）。

背景：Compose 里「被拖项重排到列表末尾」会让该节点的 pointerInput 被销毁重建、拖拽协程当场取消，
表现为长按拖拽完全无反应。这类手势 bug 在 JVM 单测里没有可信接缝，故用本脚本作为回归防线。

判据（不可伪造的净效果）：把 A 移到 B 上应当合并出一个「新文件夹」，且落在 B 的格子里、A 离开原格。
  python scripts/drag-check.py drag   # 长按 A → 拖到 B → 松手
  python scripts/drag-check.py tap    # 点选 A → 点 B（阳性对照，证明判据本身可信）
两者终点相同：tap 绿而 drag 红 = 断点确实在长按拖拽。

全程只改内存、不写库，结束自动「更多 → 重新读取桌面」还原。需 adb 已连真机、DeskFlow 已安装。
"""
import re, subprocess, sys, time

A = sys.argv[2] if len(sys.argv) > 2 else "RikkaHub"
B = sys.argv[3] if len(sys.argv) > 3 else "支付宝"
MODE = sys.argv[1] if len(sys.argv) > 1 else "drag"


def text(*a):
    return subprocess.run(a, stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")


def dump():
    for _ in range(4):
        text("adb", "shell", "uiautomator", "dump", "/sdcard/g.xml")
        x = text("adb", "exec-out", "cat", "/sdcard/g.xml")
        if "<node" in x:
            return x
        time.sleep(0.5)
    return ""


def nodes(x):
    out = []
    for m in re.finditer(r"<node[^>]*>", x):
        tag = m.group(0)
        t = re.search(r'text="([^"]+)"', tag)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if t and b and t.group(1).strip():
            x1, y1, x2, y2 = map(int, b.groups())
            out.append((t.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def pos(x, label, lo=600, hi=2400):
    hits = [(a, b) for t, a, b in nodes(x) if t == label and lo < b < hi]
    return max(hits, key=lambda p: p[1]) if hits else None


def tap(x, y):
    text("adb", "shell", "input", "tap", str(x), str(y))
    time.sleep(0.7)


def focused():
    return next((l for l in text("adb", "shell", "dumpsys", "window").splitlines()
                 if "mCurrentFocus" in l), "")


def settle():
    """确保前台是 DeskFlow 编辑页且无残留弹层。DeskFlow 网格与真桌面布局几乎相同，
    必须靠顶栏标记区分，否则测的可能是 vivo 桌面。"""
    for _ in range(5):
        if "com.deskflow" not in focused():
            text("adb", "shell", "am", "start", "-n", "com.deskflow/.MainActivity")
            time.sleep(3)
            continue
        x = dump()
        if "DeskFlow · vivo" in x:
            return x
        c = [(a, b) for t, a, b in nodes(x) if t in ("取消", "关闭")]
        if c:
            tap(*c[0])
            continue
        time.sleep(1)
    sys.exit("VERDICT=ERROR 进不到 DeskFlow 编辑页")


base = settle()
if any(t == "新文件夹" for t, _, _ in nodes(base)):
    sys.exit("VERDICT=ERROR 起点已有「新文件夹」，判据会被污染，先还原")
pa, pb = pos(base, A), pos(base, B)
if not (pa and pb):
    sys.exit(f"VERDICT=ERROR 找不到 {A}={pa} 或 {B}={pb}")

if MODE == "drag":
    text("adb", "shell", "input", "motionevent", "DOWN", str(pa[0]), str(pa[1]))
    time.sleep(1.0)                      # 超过长按阈值
    for i in range(1, 9):
        text("adb", "shell", "input", "motionevent", "MOVE",
             str(int(pa[0] + (pb[0] - pa[0]) * i / 8)), str(int(pa[1] + (pb[1] - pa[1]) * i / 8)))
        time.sleep(0.07)
    text("adb", "shell", "input", "motionevent", "UP", str(pb[0]), str(pb[1]))
else:
    tap(*pa)
    tap(*pb)
time.sleep(1.2)

after = settle()
new = [(a, b) for t, a, b in nodes(after) if t == "新文件夹"]
at_b = bool(new) and all(abs(x - pb[0]) < 150 and abs(y - pb[1]) < 150 for x, y in new)
gone = pos(after, A) is None or pos(after, A) != pa
ok = at_b and gone
print(f"模式={MODE}  {A}@{pa} → {B}@{pb}")
print(f"新文件夹={new} 落在B格={at_b} A离开原格={gone}")
print("VERDICT=" + ("GREEN 移动生效" if ok else "RED 移动未生效"))

tap(833, 259)                            # 更多
r = pos(dump(), "重新读取桌面", 300, 2500)
if r:
    tap(*r)
    time.sleep(2)
sys.exit(0 if ok else 1)
