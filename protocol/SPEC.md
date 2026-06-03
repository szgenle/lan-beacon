# lan-beacon Protocol Specification v1

[中文版](SPEC_zh.md)

> This document is the **Single Source of Truth** for all platform implementations.  
> Any protocol change must be reflected here first, then synced to code.

---

## 1. Overview

lan-beacon provides LAN-based device presence detection. Two roles are defined:

| Role | Responsibility | Typical Platform |
|------|---------------|-----------------|
| **Beacon (Broadcaster)** | Register mDNS service + run HTTP heartbeat endpoint | Android (phone) |
| **Scanner (Discoverer)** | Discover Beacons (subnet scan or mDNS) + poll HTTP endpoint | Godot desktop (PC) |

The discovery phase supports two strategies; Scanner implementations must support at least one:

| Strategy | Mechanism | Currently Used |
|----------|-----------|----------------|
| **Subnet Scan** | Iterate all IPs in the local /24 subnet, probe HTTP endpoint | Godot client |
| **mDNS Browse** | Send PTR query to multicast address, receive responses passively | (reserved, future option) |

Communication flow (subnet scan mode):

```
Scanner                           Beacon
  │                                  │
  │── HTTP GET /v1/healthz ───────►│  (subnet sweep, concurrent)
  │◄── 200 JSON ──────────────────│  → device discovered
  │                                  │
  │── HTTP GET /v1/healthz ───────►│  (heartbeat phase, periodic poll)
  │◄── 200 JSON ──────────────────│
  │                                  │
  │   (N consecutive timeouts → absent)  │
```

---

## 2. Device Discovery

### 2.1 Subnet Scan — current Godot implementation

Scanner enumerates all RFC 1918 private IPv4 addresses on local interfaces, probing each /24 subnet:

**Flow:**

1. Enumerate local network interfaces, filter for private IPv4 (10.x / 172.16-31.x / 192.168.x)
2. For each address, take the /24 prefix and generate candidate IPs 1–254
3. Send `GET http://<ip>:<port>/v1/healthz` with configurable concurrency (default 32)
4. Receive 200 response with matching JSON `app` field → discovery successful
5. No matches → wait `scan_interval` then retry

**Advantages:** No multicast support required; works in all network environments (including enterprise networks with mDNS disabled).  
**Limitations:** Max 254 addresses per /24 subnet; cross-subnet scenarios require manual `set_target()`.

### 2.2 mDNS Service Registration (Beacon side)

Beacon registers an mDNS service record on startup (for potential future mDNS Scanner implementations):

| Field | Value | Description |
|-------|-------|-------------|
| Service Type | `_<app>._tcp.local.` | Each integrating app gets a unique name, e.g. `_agentpost._tcp.local.` |
| Service Name | Custom instance name | e.g. `agentpost-beacon`, system auto-appends number on conflict |
| Port | Actual HTTP listen port | Written to SRV record |
| Host | Device's current WiFi IP | Written to A/AAAA record |
| TXT Records | Key-value metadata pairs | See below |

**TXT Record attributes:**

| Key | Required | Description |
|-----|----------|-------------|
| `v` | Yes | Protocol major version, fixed `"1"` for current spec |
| *(custom)* | No | Integrator-defined metadata (e.g. `name`, `cap`). Keys: ASCII lowercase, max 9 bytes |

Example TXT record set:
```
v=1
name=My Phone
cap=sync,file
```

> TXT attribute keys should follow [RFC 6763 §6.4](https://datatracker.ietf.org/doc/html/rfc6763#section-6.4) recommendations: short, lowercase ASCII.

### 2.3 mDNS Service Browsing (reserved reference)

> The following describes mDNS browsing. The Godot client **does not currently use this** — retained for future implementations or third-party reference.

Scanner periodically sends mDNS PTR queries to `224.0.0.251:5353`:

**Query packet format:**

```
Offset  Size  Field
──────  ────  ──────────────────
0       2     Transaction ID = 0x0000
2       2     Flags = 0x0000 (standard query)
4       2     Questions = 1
6       6     Answer/Authority/Additional = 0
12      N     QNAME = "<service_type>local." (DNS label encoding)
12+N    2     QTYPE = 0x000C (PTR)
12+N+2  2     QCLASS = 0x8001 (IN, unicast-response)
```

**DNS Label encoding:**  
`_agentpost._tcp.local.` → `[10]_agentpost[4]_tcp[5]local[0]`  
(each segment prefixed with 1-byte length, terminated by 0x00)

### 2.4 mDNS Response Parsing (reserved reference)

Scanner extracts from the response:
1. **Source IP** (UDP packet source address) → device address
2. **SRV record port field** (type=0x0021, class=0x0001) → HTTP port
3. If SRV extraction fails, fallback to configured default port

**SRV record RDATA layout:**

```
Offset  Size  Field
──────  ────  ──────────────
0       2     Priority
2       2     Weight
4       2     Port ← extract this
6       N     Target (DNS name)
```

---

## 3. HTTP Heartbeat Endpoint

### 3.1 Endpoint Definition

```
GET /v1/healthz
```

### 3.2 Request

- Method: `GET`
- No body, no special headers
- Source must be RFC 1918 private network (otherwise returns 403)

### 3.3 Success Response

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

| Field | Type | Description |
|-------|------|-------------|
| `app` | string | Application identifier, used by Scanner to distinguish different app beacons |
| `version` | string | Application version, for compatibility checks |
| `ts` | number | Response timestamp in Unix milliseconds |
| `meta` | object (optional) | Metadata key-value pairs; omitted when empty. Same content as mDNS TXT attributes (excluding `v`) |

> The `meta` field is **optional** and backward-compatible: Scanners must ignore unknown fields (see §7). When Beacon has no metadata configured, this field is absent from the response.

### 3.4 Error Responses

| HTTP Status | Meaning |
|-------------|--------|
| 401 Unauthorized | Token configured but request missing or mismatched `Authorization` header |
| 403 Forbidden | Request source is not a private network |
| 404 Not Found | Path is not `/v1/healthz` |

### 3.5 Security Policy

Beacon **must** implement source IP validation, allowing only:

| Network | CIDR |
|---------|------|
| Private Class A | 10.0.0.0/8 |
| Private Class B | 172.16.0.0/12 |
| Private Class C | 192.168.0.0/16 |
| Link-local | 169.254.0.0/16 |
| Loopback | 127.0.0.0/8 |

### 3.6 Token Authentication (Optional)

Beacon **may** be configured with a shared secret token. When set, Scanner must include the token in every request:

```
Authorization: Bearer <token>
```

**Validation rules:**

1. If Beacon has no token configured → skip validation (backward compatible with v0.1)
2. If Beacon has token configured:
   - Request has matching `Authorization: Bearer <token>` header → proceed normally
   - Request has missing or mismatched header → return `401 Unauthorized`
3. Token comparison must use constant-time algorithm (timing-safe) to prevent side-channel attacks

**Filter order:** source IP check (403) → token check (401) → route match (404/200)

---

## 4. Presence / Absence Detection (Scanner logic)

| Parameter | Recommended Default | Description |
|-----------|-------------------|-------------|
| `heartbeat_interval` | 5 seconds | HTTP poll interval |
| `max_miss_count` | 3 | Consecutive failure threshold |
| `scan_interval` | 30 seconds | Subnet scan retry interval (when device absent) |
| `scan_concurrency` | 32 | Concurrent scan requests |
| `scan_timeout` | 1.5 seconds | Single HTTP probe timeout during scan |

**State machine:**

```
         Subnet scan hit + healthz 200
 [Absent] ────────────────────────► [Present]
     ▲                                │
     │    max_miss_count consecutive   │
     │          failures               │
     └────────────────────────────────┘
```

- **Present**: First healthz 200 → emit `device_found`
- **Absent**: N consecutive heartbeat failures (timeout/non-200) → emit `device_lost`
- Every successful heartbeat → emit `heartbeat_received` (regardless of current state)

---

## 5. Configuration Parameters

All implementations must support these parameters (naming may follow platform conventions, semantics must be consistent):

| Parameter | Beacon | Scanner | Description |
|-----------|--------|---------|-------------|
| `port` | Yes | Yes | HTTP listen port (Beacon) / probe target port (Scanner) |
| `appName` | Yes | — | Written to healthz JSON `app` field |
| `appVersion` | Yes | — | Written to healthz JSON `version` field |
| `serviceType` | Yes | — | mDNS service type, format `_<name>._tcp.` (Beacon registration) |
| `serviceName` | Yes | — | mDNS instance name (Beacon registration) |
| `token` | Optional | Optional | Shared secret for Bearer token auth; null/empty = disabled (see §3.6) |
| `metadata` | Optional | — | Key-value pairs written to mDNS TXT records and healthz `meta` field (see §2.2, §3.3) |

> Scanner (subnet scan mode) only needs `port` to function; `serviceType` is reserved for future mDNS mode.

**All Beacon-side parameters have no default values** — integrators must explicitly provide each one to prevent silent fallbacks.

---

## 6. Port Selection Guidelines

- Use IANA unregistered high ports (49152–65535 range)
- Recommended example: `47821` (not mandatory)
- Integrators may choose freely, as long as both ends match

---

## 7. Versioning Rules

- Path prefix `/v1/` is the major protocol version
- Adding new fields under `/v1/` is a **backward-compatible change** (Scanner ignores unknown fields)
- Removing fields or changing semantics requires upgrade to `/v2/`
- Changing mDNS serviceType = breaking change (old Scanners can't discover new Beacons)

---

## 8. Implementation Reference

| Spec Item | Android Implementation | Godot Implementation |
|-----------|----------------------|---------------------|
| Device Discovery | N/A (Beacon only) | Subnet scan: iterate /24, concurrent HTTP probes |
| mDNS Registration | `NsdManager.registerService()` | N/A (Scanner only) |
| HTTP Server | `NanoHTTPD` | N/A (Scanner only) |
| HTTP Client | N/A | `HTTPRequest` node (scan pool + heartbeat, independent instances) |
| healthz JSON | `PresenceHttpServer.serve()` | `_on_heartbeat_response()` / `_on_scan_response()` parsing |
| Security Filter | `isPrivateNetwork()` | Not needed (Scanner is the initiator) |

---

## Appendix A: Full Interaction Sequence (Subnet Scan Mode)

```
Time    Scanner                          Beacon (Android)
─────  ───────────────────────          ─────────────────────
T+0    Start, enumerate local subnets    (NsdManager registered + HTTP ready)
       Concurrent probe 192.168.31.1~254
T+0~2  GET /v1/healthz → each IP  ──►   (most connections timeout/refused)
T+1.2  GET /v1/healthz → .105     ──►
                                   ◄──   200 {"app":"agentpost","version":"1.2.0","ts":1717200000000}
       → Stop scanning, emit device_found
T+6    GET /v1/healthz             ──►   200 OK
       → emit heartbeat_received
T+11   GET /v1/healthz             ──►   200 OK
...
T+60   GET /v1/healthz             ──►   (phone leaves WiFi)
       → timeout
T+65   GET /v1/healthz             ──►   timeout (miss=2)
T+70   GET /v1/healthz             ──►   timeout (miss=3)
       → emit device_lost
T+70   Restart subnet scan
```
