# lan-beacon 协议规范 v1

> 本文档是各平台实现的**唯一参考源 (Single Source of Truth)**。  
> 任何一端的修改都必须先更新此文档，再同步改代码。

---

## 1. 概述

lan-beacon 用于局域网内设备在场感知。分为两个角色：

| 角色 | 职责 | 典型平台 |
|------|------|----------|
| **Beacon（广播端）** | 注册 mDNS 服务 + 运行 HTTP 心跳端点 | Android（手机） |
| **Scanner（发现端）** | 浏览 mDNS 服务 + 轮询 HTTP 端点 | Godot 桌面（PC） |

通信链路：

```
Scanner                           Beacon
  │                                  │
  │──── mDNS PTR Query ────────────►│  (发现阶段)
  │◄─── mDNS PTR/SRV/A Response ───│
  │                                  │
  │──── HTTP GET /v1/healthz ──────►│  (心跳阶段)
  │◄─── 200 JSON ──────────────────│
  │                                  │
  │   (连续 N 次超时 → 判定离场)     │
```

---

## 2. mDNS 服务发现

### 2.1 服务注册（Beacon 端）

Beacon 启动时向局域网注册一条 mDNS 服务记录：

| 字段 | 值 | 说明 |
|------|-----|------|
| Service Type | `_<app>._tcp.local.` | 每个集成应用独立命名，如 `_agentpost._tcp.local.` |
| Service Name | 自定义实例名 | 如 `agentpost-beacon`，冲突时系统自动追加编号 |
| Port | 实际 HTTP 监听端口 | 写入 SRV 记录 |
| Host | 设备当前 WiFi IP | 写入 A/AAAA 记录 |

### 2.2 服务浏览（Scanner 端）

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

### 2.3 响应解析

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
  "ts": <unix_timestamp_ms>
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `app` | string | 应用标识，Scanner 据此区分不同 App 的 beacon |
| `version` | string | 应用版本号，便于兼容性判断 |
| `ts` | number | 响应时刻的 Unix 毫秒时间戳 |

### 3.4 错误响应

| HTTP Status | 含义 |
|-------------|------|
| 403 Forbidden | 请求来源不是私有网段 |
| 404 Not Found | 路径不是 `/v1/healthz` |

### 3.5 安全策略

Beacon 端**必须**实现来源 IP 校验，仅允许以下网段：

| 网段 | CIDR |
|------|------|
| 私有 A 类 | 10.0.0.0/8 |
| 私有 B 类 | 172.16.0.0/12 |
| 私有 C 类 | 192.168.0.0/16 |
| 链路本地 | 169.254.0.0/16 |
| 环回 | 127.0.0.0/8 |

---

## 4. 在场/离场判定（Scanner 端逻辑）

| 参数 | 推荐默认值 | 说明 |
|------|-----------|------|
| `heartbeat_interval` | 5 秒 | HTTP 轮询间隔 |
| `max_miss_count` | 3 | 连续失败次数阈值 |
| `mdns_query_interval` | 30 秒 | mDNS 重查间隔（应对 IP 变化） |

**状态机：**

```
         mDNS 发现 + healthz 200
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

| 参数 | 必填 | 说明 |
|------|------|------|
| `port` | ✅ | HTTP 监听端口（Beacon）/ 期望端口（Scanner fallback） |
| `appName` | ✅ | 写入 healthz JSON 的 `app` 字段 |
| `appVersion` | ✅ | 写入 healthz JSON 的 `version` 字段 |
| `serviceType` | ✅ | mDNS 服务类型，格式 `_<name>._tcp.` |
| `serviceName` | ✅ | mDNS 实例名 |

**所有参数均无默认值**——强制集成方显式传入，避免遗漏导致排查困难。

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
| mDNS 注册 | `NsdManager.registerService()` | N/A（仅 Scanner） |
| mDNS 浏览 | N/A（仅 Beacon） | `PacketPeerUDP` multicast |
| HTTP 服务 | `NanoHTTPD` | N/A（仅 Scanner） |
| HTTP 客户端 | N/A | `HTTPRequest` node |
| healthz JSON | `PresenceHttpServer.serve()` | `_on_heartbeat_response()` 解析 |
| 安全过滤 | `isPrivateNetwork()` | 无需（Scanner 是发起方） |

---

## 附录 A: 完整交互时序

```
时刻    Scanner                          Beacon (Android)
─────  ───────────────────────          ─────────────────────
T+0    启动，发送 mDNS PTR 查询  ──►     (NsdManager 已注册)
T+0.1                           ◄──     mDNS 响应（IP + Port）
T+0.2  GET /v1/healthz          ──►
T+0.3                           ◄──     200 {"app":"agentpost","version":"1.2.0","ts":1717200000000}
       → emit device_found
T+5    GET /v1/healthz          ──►     200 OK
       → emit heartbeat_received
T+10   GET /v1/healthz          ──►     200 OK
...
T+60   GET /v1/healthz          ──►     (手机离开 WiFi)
       → 超时
T+65   GET /v1/healthz          ──►     超时 (miss=2)
T+70   GET /v1/healthz          ──►     超时 (miss=3)
       → emit device_lost
```
