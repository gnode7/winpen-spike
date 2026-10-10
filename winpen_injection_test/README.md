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

## 实验 A：坐标系原点判别（双屏）

**前提**：至少两台显示器，最好把一台副屏摆在**主屏左侧或上方**（制造负坐标区）。

```bat
probe.exe --test origin
```

程序会打印虚拟屏 metrics 与每个显示器的矩形/DPI，然后向坐标系 `(5,5)` 注入
一次完整点击（强制显示触摸可视化圆圈）。

| 观察结果 | 结论 | 后续动作 |
|---|---|---|
| 圆圈出现在**主屏左上角**附近 | 原点 = 主屏左上角（官方术语体系裁决成立） | 直接使用屏幕坐标 |
| 圆圈出现在左侧/上方**副屏** | 原点 = 虚拟屏包围盒左上角 | 注入坐标需减去 `SM_X/YVIRTUALSCREEN` 偏移 |

## 实验 B：DPI 语义判别（单屏 150% 缩放最佳）

**前提**：单显示器，设置缩放为 **150%**（非 100%，否则无判别力）。

```bat
probe.exe --test dpi --scale 150
```

程序先自动对比两种线程上下文下 `GetSystemMetrics(SM_CXSCREEN)` 的值
（unaware 上下文若返回缩小值，即可实锤"调用线程坐标空间"机制存在），
随后用 **PMv2** 与 **unaware** 两种线程上下文各向 `(200,200)` 注入一次点击。

| 观察结果 | 结论 |
|---|---|
| 两圆圈都在物理 (200,200) | API 直通物理像素，与 DPI awareness 无关 |
| PMv2 圈在 (200,200)，unaware 圈在 (300,300) | 坐标空间跟随调用线程（官方机制推论成立）——**预期结果** |
| 两圆圈都在 (300,300) | API 固定按逻辑像素解释（可能性极低） |

> 注：屏幕缩放比例以 `设置 > 系统 > 屏幕 > 缩放` 为准；`--scale` 只用于打印
> 预期落点，不影响注入行为。

## 工具模式（回归测试复用）

```bat
probe.exe --click 500 300                 :: 单击（默认触摸）
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
