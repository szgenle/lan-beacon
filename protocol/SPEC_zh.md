# lan-beacon 协议规范 v1

[English](SPEC.md)

> 本文档是各平台实现的**唯一参考源 (Single Source of Truth)**。  
> 任何一端的修改都必须先更新此文档，再同步改代码。

---

## 1. 概述

lan-beacon 用于局域网内设备在场感知。分为两个角色：

| 角色 | 职责 | 典型平台 |
|------|------|----------|
| **Beacon（广播端）** | 注册 mDNS 服务 + 运行 HTTP 心跳端点 | Android（手机） |
| **Scanner（发现端）** | 发现 Beacon（子网扫描或 mDNS）+ 轮询 HTTP 端点 | Godot 桌面（PC） |

发现阶段支持两种策略，Scanner 实现至少选其一：

| 策略 | 原理 | 当前采用 |
|------|------|----------|
| **子网扫描（Subnet Scan）** | 遍历本机 /24 子网所有 IP，逐个探测 HTTP 端点 | Godot 端 |
| **mDNS 浏览** | 向组播地址发送 PTR 查询，被动接收响应 | （保留，未来可选） |

通信链路（子网扫描模式）：

```
Scanner                           Beacon
  │                                  │
  │── HTTP GET /v1/healthz ───────►│  (子网遍历探测，并发)
  │◄── 200 JSON ──────────────────│  → 发现设备
  │                                  │
  │── HTTP GET /v1/healthz ───────►│  (心跳阶段，定时轮询)
  │◄── 200 JSON ──────────────────│
  │                                  │
  │   (连续 N 次超时 → 判定离场)     │
```

---

## 2. 设备发现

### 2.1 子网扫描（Subnet Scan）——当前 Godot 端采用

Scanner 遍历本机所有 RFC 1918 私有 IPv4 地址所属的 /24 子网，对每个 IP 主动发起 HTTP 探测：

**流程：**

1. 枚举本机网络接口，筛选 IPv4 私有地址（10.x / 172.16-31.x / 192.168.x）
2. 对每个地址取 /24 前缀，生成 1–254 的候选 IP 列表
3. 以可配置的并发数（默认 32）批量发送 `GET http://<ip>:<port>/v1/healthz`
4. 收到 200 响应且 JSON `app` 字段匹配目标应用（或未配置过滤）→ 发现成功
5. 全部未命中 → 等待 `scan_interval` 后重试

**优势：** 无需组播支持，兼容所有网络环境（含禁用 mDNS 的企业网）。  
**限制：** /24 子网内最多扫 254 个地址；跨子网场景需用户手动 `set_target()`。

### 2.2 mDNS 服务注册（Beacon 端）

Beacon 启动时向局域网注册一条 mDNS 服务记录（用于未来可能的 mDNS Scanner 实现）：

| 字段 | 值 | 说明 |
|------|-----|------|
| Service Type | `_<app>._tcp.local.` | 每个集成应用独立命名，如 `_agentpost._tcp.local.` |
| Service Name | 自定义实例名 | 如 `agentpost-beacon`，冲突时系统自动追加编号 |
| Port | 实际 HTTP 监听端口 | 写入 SRV 记录 |
| Host | 设备当前 WiFi IP | 写入 A/AAAA 记录 |
| TXT Records | 键值对元数据 | 见下方 |

**TXT Record 属性：**

| 键 | 必填 | 说明 |
|----|------|------|
| `v` | 是 | 协议主版本号，当前固定为 `"1"` |
| *（自定义）* | 否 | 集成方自定义元数据（如 `name`、`cap`）。键名：ASCII 小写，最长 9 字节 |

TXT 记录示例：
```
v=1
name=My Phone
cap=sync,file
```

> TXT 属性键名应遵循 [RFC 6763 §6.4](https://datatracker.ietf.org/doc/html/rfc6763#section-6.4) 建议：短小、ASCII 小写。

### 2.3 mDNS 服务浏览（保留参考）

> 以下内容描述 mDNS 浏览方式，Godot 端当前**未使用**，仅供未来实现或第三方参考。

Scanner 周期性向 `224.0.0.251:5353` 发送 mDNS PTR 查询：

**查询包格式：**

```
Offset  Size  Field
──────  ────  ──────────────────
0       2     Transaction ID = 0x0000
2       2     Flags = 0x0000（标准查询）
4       2     Questions = 1
6       6     Answer/Authority/Additional = 0
12      N     QNAME = "<service_type>local." (DNS label 编码)
12+N    2     QTYPE = 0x000C (PTR)
12+N+2  2     QCLASS = 0x8001 (IN, unicast-response)
```

**DNS Label 编码规则：**  
`_agentpost._tcp.local.` → `[10]_agentpost[4]_tcp[5]local[0]`  
（每段前置 1 字节长度，末尾 0x00 终止）

### 2.4 mDNS 响应解析（保留参考）

Scanner 从响应中提取：
1. **来源 IP**（UDP 包的 source address）→ 设备地址
2. **SRV 记录的 port 字段**（type=0x0021, class=0x0001）→ HTTP 端口
3. 如果 SRV 提取失败，fallback 到配置默认端口

**SRV 记录 RDATA 布局：**

```
Offset  Size  Field
──────  ────  ──────────────
0       2     Priority
2       2     Weight
4       2     Port ← 取这个
6       N     Target (DNS name)
```

---

## 3. HTTP 心跳端点

### 3.1 端点定义

```
GET /v1/healthz
```

### 3.2 请求

- Method: `GET`
- 无 Body、无特殊 Header
- 来源必须是 RFC 1918 私有网段（否则返回 403）

### 3.3 成功响应

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "app": "<appName>",
  "version": "<appVersion>",
  "ts": <unix_timestamp_ms>,
  "meta": { ... }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `app` | string | 应用标识，Scanner 据此区分不同 App 的 beacon |
| `version` | string | 应用版本号，便于兼容性判断 |
| `ts` | number | 响应时刻的 Unix 毫秒时间戳 |
| `meta` | object（可选） | 元数据键值对；为空时省略此字段。内容与 mDNS TXT 属性一致（不含 `v`） |

> `meta` 字段**可选**且向后兼容：Scanner 端必须忽略未知字段（见 §7）。当 Beacon 未配置 metadata 时，响应中不包含此字段。

### 3.4 错误响应

| HTTP Status | 含义 |
|-------------|------|
| 401 Unauthorized | 已配置 token 但请求缺少或不匹配 `Authorization` 头 |
| 403 Forbidden | 请求来源不是私有网段 |
| 404 Not Found | 路径不是 `/v1/healthz` |

### 3.5 安全策略

Beacon 端**必须**实现来源 IP 校验，仅允许以下网段：

**IPv4：**

| 网段 | CIDR |
|------|------|
| 私有 A 类 | 10.0.0.0/8 |
| 私有 B 类 | 172.16.0.0/12 |
| 私有 C 类 | 192.168.0.0/16 |
| 链路本地 | 169.254.0.0/16 |
| 环回 | 127.0.0.0/8 |

**IPv6：**

| 网段 | CIDR |
|------|------|
| 唯一本地地址 (ULA) | fc00::/7 |
| 链路本地 | fe80::/10 |
| 环回 | ::1/128 |

---

### 3.6 Token 鉴权（可选）

Beacon 端**可以**配置一个共享密钥 token。配置后，Scanner 端必须在每个请求中携带该 token：

```
Authorization: Bearer <token>
```

**验证规则：**

1. Beacon 未配置 token → 跳过验证（与 v0.1 行为一致，向后兼容）
2. Beacon 已配置 token：
   - 请求携带匹配的 `Authorization: Bearer <token>` 头 → 正常处理
   - 请求缺少或不匹配 → 返回 `401 Unauthorized`
3. Token 比较必须使用常量时间算法（timing-safe），防止侧信道攻击

**过滤顺序：** 来源 IP 校验 (403) → token 校验 (401) → 路由匹配 (404/200)

---

## 4. 在场/离场判定（Scanner 端逻辑）

| 参数 | 推荐默认值 | 说明 |
|------|-----------|------|
| `heartbeat_interval` | 5 秒 | HTTP 轮询间隔 |
| `max_miss_count` | 3 | 连续失败次数阈值 |
| `scan_interval` | 30 秒 | 子网扫描重试间隔（设备未在场时周期性重扫） |
| `scan_concurrency` | 32 | 子网扫描并发请求数 |
| `scan_timeout` | 1.5 秒 | 扫描探测单次 HTTP 超时 |

**状态机：**

```
         子网扫描命中 + healthz 200
 [未发现] ────────────────────────► [在场]
     ▲                                │
     │    连续 max_miss_count 次失败    │
     └────────────────────────────────┘
```

- **在场 (present)**：首次 healthz 200 时触发 `device_found`
- **离场 (absent)**：连续 N 次心跳失败（超时/非200）时触发 `device_lost`
- 每次心跳成功时触发 `heartbeat_received`（无论是否已在场）

---

## 5. 配置参数约定

所有实现必须支持以下配置项（命名可按平台惯例调整，语义必须一致）：

| 参数 | Beacon | Scanner | 说明 |
|------|--------|---------|------|
| `port` | ✅ | ✅ | HTTP 监听端口（Beacon）/ 探测目标端口（Scanner） |
| `appName` | ✅ | — | 写入 healthz JSON 的 `app` 字段 |
| `appVersion` | ✅ | — | 写入 healthz JSON 的 `version` 字段 |
| `serviceType` | ✅ | — | mDNS 服务类型，格式 `_<name>._tcp.`（Beacon 注册用） |
| `serviceName` | ✅ | — | mDNS 实例名（Beacon 注册用） |
| `targetApp` | — | 可选 | 扫描时匹配 healthz 响应的 `app` 字段；为空则接受任何合法 beacon |
| `token` | 可选 | 可选 | Bearer token 鉴权的共享密钥；null/空 = 不启用（见 §3.6） |
| `metadata` | 可选 | — | 键值对，写入 mDNS TXT 记录和 healthz `meta` 字段（见 §2.2、§3.3） |

> Scanner（子网扫描模式）只需 `port` 即可工作；`targetApp` 可选但推荐设置，用于多服务环境下避免误识别。
> `serviceType` 为保留字段供未来 mDNS 模式使用。

**Beacon 侧所有参数均无默认值**——强制集成方显式传入，避免遗漏导致排查困难。

---

## 6. 端口选择建议

- 使用 IANA 未注册的高端口（49152–65535 范围内）
- 推荐 `47821` 作为示例值（非强制）
- 集成方可自选，只要两端一致即可

---

## 7. 版本演进规则

- 路径前缀 `/v1/` 为协议大版本号
- 在 `/v1/` 下新增字段属于**向后兼容变更**（Scanner 端忽略未知字段即可）
- 删除字段或改变语义必须升级到 `/v2/`
- mDNS serviceType 变更 = 不兼容变更（旧 Scanner 发现不到新 Beacon）

---

## 8. 各端实现对照

| 规范条目 | Android 实现 | Godot 实现 |
|----------|-------------|------------|
| 设备发现 | N/A（仅 Beacon） | 子网扫描：遍历 /24 并发探测 HTTP 端点 |
| mDNS 注册 | `NsdManager.registerService()` | N/A（仅 Scanner） |
| HTTP 服务 | `NanoHTTPD` | N/A（仅 Scanner） |
| HTTP 客户端 | N/A | `HTTPRequest` node（扫描池 + 心跳各独立实例） |
| healthz JSON | `PresenceHttpServer.serve()` | `_on_heartbeat_response()` / `_on_scan_response()` 解析 |
| 安全过滤 | `isPrivateNetwork()` | 无需（Scanner 是发起方） |

---

## 附录 A: 完整交互时序（子网扫描模式）

```
时刻    Scanner                          Beacon (Android)
─────  ───────────────────────          ─────────────────────
T+0    启动，枚举本机子网               (NsdManager 已注册 + HTTP 就绪)
       并发探测 192.168.31.1~254
T+0~2  GET /v1/healthz →各IP    ──►     (大部分连接超时/拒绝)
T+1.2  GET /v1/healthz →.105    ──►
                                ◄──     200 {"app":"agentpost","version":"1.2.0","ts":1717200000000}
       → 停止扫描，emit device_found
T+6    GET /v1/healthz          ──►     200 OK
       → emit heartbeat_received
T+11   GET /v1/healthz          ──►     200 OK
...
T+60   GET /v1/healthz          ──►     (手机离开 WiFi)
       → 超时
T+65   GET /v1/healthz          ──►     超时 (miss=2)
T+70   GET /v1/healthz          ──►     超时 (miss=3)
       → emit device_lost
T+70   重新启动子网扫描
```
