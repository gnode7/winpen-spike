# InjectSyntheticPointerInput 判别探针（WinPen 测试工具）

针对两桩官方文档悬案的实机判别工具，兼作日常触摸/笔注入测试器。

## 官方依据（存档于 `../docs/msdoc_synthetic_pointer/`）

| 依据 | 内容 |
|---|---|
| `WinUser.h`（SDK 10.0.28000） | 函数/结构体/常量权威定义，`MAX_TOUCH_COUNT=256` |
| learn: The Virtual Screen | 虚拟屏幕=所有显示器包围矩形；**主屏含原点 (0,0)**；负坐标合法；16 位有符号限制 |
| learn: InjectSyntheticPointerInput | 3 参数；`ptPixelLocation` 相对虚拟屏幕；PT_PEN 时 `count=1` |
| learn: InjectTouchInput Remarks | 官方 hover/interactive 状态机表；坐标须在桌面边界内 |
| learn: High DPI Development | "Windows scales ... to the **coordinate space of the calling thread**" |

## 编译

```bat
:: MSVC（x64 Native Tools Command Prompt）
cl /W4 /O2 synthetic_pointer_probe.c /Fe:probe.exe /link user32.lib

:: MinGW-w64
gcc -O2 -Wall -o probe.exe synthetic_pointer_probe.c -luser32
```

无需 shcore.lib（`GetDpiForMonitor` 动态加载）。运行环境需 Windows 10 1809+；
更低版本会打印 `[fatal]` 并退出，不会崩溃。

## 实验 A：坐标系原点判别（双屏）——✅ 已实测裁决

**前提**：至少两台显示器，最好把一台副屏摆在**主屏左侧或上方**（制造负坐标区）。

```bat
probe.exe --test origin
```

**实测裁决（2026-10-10，副屏在左侧布局）**：注入 (5,5) 的圆圈出现在**左侧副屏左上角**
（物理 -1915,5 附近）——即坐标被解释为**相对虚拟屏包围盒左上角
（SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN）的偏移**，而不是主屏原点 (0,0)。

| 假说 | 结果 |
|---|---|
| ~~原点 = 主屏左上角 (0,0)~~（官方术语体系推理） | **证伪**（(5,5) 落主屏才对，实际落副屏） |
| **原点 = 包围盒左上角**（文档措辞字面义） | **实测成立** |

换算公式：`注入坐标 = 目标点(主屏原点坐标系) - (SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN)`。
单屏时 SM 偏移 = 0，两种语义重合——这就是此坑长期不被发现的原因。

## 实验 B：DPI 语义判别（单屏 150% 缩放最佳）——✅ 已实测裁决

**前提**：单显示器，设置缩放为 **150%**（非 100%，否则无判别力）。

```bat
probe.exe --test dpi --scale 150
```

**实测裁决（2026-10-10）**：

| 证据 | 数值 | 含义 |
|---|---|---|
| PMv2 上下文 `SM_CXSCREEN` | 1920（物理） | 查询在 PMv2 下返回物理值 |
| unaware 上下文 `SM_CXSCREEN` | **1280 = 1920/1.5** | 查询类 API 确实被虚拟化（机制存在） |
| 两次注入圆圈位置 | **都落在 (200,200)，重合** | 注入坐标**不**被换算 |

**结论**：注入是"写"方向，不走 "calling thread coordinate space" 虚拟化；
`ptPixelLocation` 按物理像素直通，与线程/进程 DPI awareness 无关。
读写不对称：`GetSystemMetrics` 虚拟化，`InjectSyntheticPointerInput` 不虚拟化。

> 注：屏幕缩放比例以 `设置 > 系统 > 屏幕 > 缩放` 为准；`--scale` 只用于打印
> 预期落点，不影响注入行为。

## 工具模式（回归测试复用）

**坐标输入约定（按实测裁决设计）**：`--click/--stroke` 的 X/Y 是**主屏原点屏幕坐标**
（与 `EnumDisplayMonitors`/`GetCursorPos` 同一空间），工具内部自动减去
`SM_X/YVIRTUALSCREEN` 换算成 API 需要的包围盒偏移，控制台打印 `[convert]` 行；
加 `--raw` 则按包围盒原始值直传。

```bat
probe.exe --click 500 300                 :: 单击（默认触摸，主屏原点坐标）
probe.exe --click 500 300 --type pen      :: 笔尖单击
probe.exe --stroke 100 100 900 600 --frames 40   :: 划线（笔迹管线测试）
probe.exe --stroke ... --dpi unaware      :: 指定进程 DPI 上下文（默认 pmv2）
probe.exe --click ... --feedback none     :: 关闭可视化圆圈
probe.exe --click ... --qpc               :: 携带 QPC 时间戳（测 0.1ms 窗口路径）
probe.exe --delay 0 --stroke ...          :: 连续帧极限压力测试（触发 ERROR_NOT_READY 重试路径）
```

注入序列严格按官方状态机（InjectTouchInput Remarks）：

```
hover 起:  INRANGE|UPDATE
接触下:    INRANGE|INCONTACT|DOWN|FIRSTBUTTON
移动:      INRANGE|INCONTACT|UPDATE|FIRSTBUTTON  (stroke 沿路径插值)
抬起:      INRANGE|UP
hover 止:  UPDATE        <- 序列必须以 UPDATE 或 UP 结束
```

`ERROR_NOT_READY`（0.1ms 结算窗口冲突）自动**同帧重试**（官方要求），控制台
会打印 `[retry]` 日志——`--delay 0` 可稳定触发此路径验证重试逻辑。

## 已知边界

- 单指触摸/笔（`count=1`）；双指 pinch 需扩展为 2 元素数组（`MAX_TOUCH_COUNT=256` 上限内）
- `pointerId=0, frameId=0`（官方样例惯例）；`hwndTarget=NULL` 由系统路由到焦点窗口
- 时间戳默认 `dwTime=0/PerformanceCount=0`（系统分配）；`--qpc` 模式逐帧递增，
  一旦开启将持续到序列结束（官方硬约束）
- 实验 B 的"圆圈位置"靠肉眼判读；如需精确量化，可在被测位置放一个
  PMv2 aware 的接收窗口用 `GetPointerInfo` 对拍

## 文件

- `synthetic_pointer_probe.c` — 单文件源码，无第三方依赖
- 结构体布局在编译期自检（`C_ASSERT(sizeof(...)==...)`，与 SDK 头文件逐字节对齐）
