# mDNS Spike — 验证 Windows 系统 mDNS API

## 目的

验证两个问题：
1. `DnsServiceRegister` 是否走系统 mDNS 服务（**不弹 5353 防火墙框**）
2. TXT 记录写入/读取是否完整

## 在 Windows 上运行

### 前置条件
- JDK 17+（你的开发环境应该已有）
- 不需要额外安装任何东西

### 步骤

```powershell
cd mdns-verify
.\gradlew.bat run
```

### 观察要点

1. **防火墙弹框**：程序启动后，观察是否出现 Windows Defender 防火墙提示
   - 如果弹框提到 **5353 端口** → 系统 mDNS 也需要放行，结论偏负面
   - 如果弹框提到 **19820 端口** → 只是 TCP 入站，可接受
   - 如果 **完全不弹框** → 系统 mDNS 确实走系统服务，最佳结果

2. **程序输出**：观察控制台
   - `Register callback fired: status=0` → 注册成功
   - `Browse callback` + `PTR:` → 浏览发现成功
   - `Resolve callback` + `TXT records` → TXT 记录解析成功

3. **跨设备验证**（可选）：程序运行期间等待 Enter 时，从其他设备验证
   - macOS：`dns-sd -B _winpen._tcp`
   - iOS：安装 "Discovery - DNS-SD Browser" App
   - Linux：`avahi-browse _winpen._tcp`

### 预期输出示例

```
[mDNS-Spike] === WinPen mDNS Spike ===
[mDNS-Spike] Computer name:    DESKTOP-7ABC
[mDNS-Spike] Host name:        DESKTOP-7ABC.local
[mDNS-Spike] Service instance: WinPen-Spike._winpen._tcp.local
[mDNS-Spike] TCP port:         19820
[mDNS-Spike] --- Registration ---
[mDNS-Spike] [OBSERVE] Watch for Windows Firewall popup!
[mDNS-Spike] Calling DnsServiceRegister...
[mDNS-Spike] DnsServiceRegister returned: 0 (0=success)
[mDNS-Spike] Register callback fired: status=0 (0=success)
[mDNS-Spike]   Registered instance: WinPen-Spike._winpen._tcp.local
[mDNS-Spike] --- Browse ---
[mDNS-Spike] Browsing for _winpen._tcp.local...
[mDNS-Spike] DnsServiceBrowse returned: 0 (0=success)
[mDNS-Spike] Browse callback: status=0
[mDNS-Spike]   PTR: _winpen._tcp.local -> WinPen-Spike._winpen._tcp.local
[mDNS-Spike]   Resolving WinPen-Spike._winpen._tcp.local ...
[mDNS-Spike]   Resolve callback: status=0
[mDNS-Spike]     Instance: WinPen-Spike._winpen._tcp.local
[mDNS-Spike]     Host:     DESKTOP-7ABC.local
[mDNS-Spike]     Port:     19820
[mDNS-Spike]     IPv4:     192.168.1.100
[mDNS-Spike]     TXT records (3):
[mDNS-Spike]       ver = 1
[mDNS-Spike]       name = DESKTOP-7ABC
[mDNS-Spike]       os = win
[mDNS-Spike] Press Enter to deregister and exit...
```

## 技术细节

- 使用 JNA 调用 Win32 `dnsapi.dll` 中的 `DnsServiceRegister` / `DnsServiceBrowse` / `DnsServiceResolve`
- DNS_RECORD 结构体用原始内存 (Memory) 手动构建，避免 JNA Structure union 映射的复杂性
- 请求结构体用 JNA Structure 自动处理对齐
- 回调用 JNA Callback 接口 + CallbackReference.getFunctionPointer 桥接
