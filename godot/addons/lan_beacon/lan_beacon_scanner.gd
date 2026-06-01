## 局域网在场发现节点。
##
## 自动通过 mDNS 浏览指定服务类型（如 [code]_agentpost._tcp.local.[/code]），
## 发现设备后定时 HTTP 轮询 [code]/v1/healthz[/code] 确认在场。
## [br][br]
## 用法：将此节点添加到场景树，配置 [member service_type]，连接信号即可。
## [br][br]
## 信号：
## - [signal device_found]：首次发现设备（含 IP、端口、healthz 数据）
## - [signal device_lost]：设备连续 N 次心跳失败，判定离场
## - [signal heartbeat_received]：每次心跳成功
class_name LanBeaconScanner
extends Node

# ==============================================================================
# 导出参数
# ==============================================================================

## mDNS 服务类型，需与 Android 端 BeaconConfig.serviceType 一致。
## 格式：[code]_<app>._tcp.[/code]（末尾带点）
@export var service_type: String = "_agentpost._tcp."

## HTTP 心跳轮询间隔（秒）。
@export_range(1.0, 60.0, 0.5) var heartbeat_interval: float = 5.0

## 连续失败多少次判定设备离场。
@export_range(1, 20) var max_miss_count: int = 3

## mDNS 查询间隔（秒）。即使已发现设备也会周期性重发 mDNS 查询以应对 IP 变化。
@export_range(5.0, 120.0, 5.0) var mdns_query_interval: float = 30.0

# ==============================================================================
# 信号
# ==============================================================================

## 首次发现设备。payload: { "ip": String, "port": int, "app": String, "version": String }
signal device_found(info: Dictionary)

## 设备离场（连续心跳失败超过 max_miss_count）。
signal device_lost()

## 每次心跳成功。payload: { "ip": String, "port": int, "app": String, "version": String, "ts": int }
signal heartbeat_received(info: Dictionary)

# ==============================================================================
# 内部状态
# ==============================================================================

const MDNS_MULTICAST_ADDR := "224.0.0.251"
const MDNS_PORT := 5353

var _udp: PacketPeerUDP = null
var _http: HTTPRequest = null
var _heartbeat_timer: Timer = null
var _mdns_timer: Timer = null

## 当前发现的设备信息；null 表示未发现
var _current_device: Dictionary = {}
var _miss_count: int = 0
var _is_device_present: bool = false

# 用于 mDNS 响应解析
var _pending_resolve: bool = false


# ==============================================================================
# 生命周期
# ==============================================================================

func _ready() -> void:
	_setup_mdns()
	_setup_http()
	_setup_timers()
	# 立即发一次 mDNS 查询
	_send_mdns_query()


func _exit_tree() -> void:
	_cleanup()


func _process(_delta: float) -> void:
	_poll_mdns()


# ==============================================================================
# mDNS 发现
# ==============================================================================

func _setup_mdns() -> void:
	_udp = PacketPeerUDP.new()
	# 绑定到 mDNS 端口以接收多播响应
	var err := _udp.bind(MDNS_PORT, "*")
	if err != OK:
		# 端口可能被占用（系统 mDNS responder），尝试随机端口
		err = _udp.bind(0, "*")
		if err != OK:
			push_warning("LanBeaconScanner: Failed to bind UDP socket: %s" % error_string(err))
			return
	_udp.join_multicast_group(MDNS_MULTICAST_ADDR, "")
	_udp.set_broadcast_enabled(true)


func _send_mdns_query() -> void:
	if _udp == null:
		return
	# 构造 DNS-SD PTR 查询包
	var query := _build_mdns_ptr_query(service_type + "local.")
	_udp.set_dest_address(MDNS_MULTICAST_ADDR, MDNS_PORT)
	_udp.put_packet(query)
	_pending_resolve = true


func _poll_mdns() -> void:
	if _udp == null:
		return
	while _udp.get_available_packet_count() > 0:
		var packet := _udp.get_packet()
		var from_ip := _udp.get_packet_ip()
		var from_port := _udp.get_packet_port()
		_parse_mdns_response(packet, from_ip)


func _parse_mdns_response(packet: PackedByteArray, from_ip: String) -> void:
	# 极简 mDNS 响应解析：只关心是否包含我们的 service_type
	# 完整的 DNS 解析很复杂，这里做启发式匹配
	if packet.size() < 12:
		return

	# 检查是否是响应包（QR 位 = 1）
	var flags := (packet[2] << 8) | packet[3]
	if (flags & 0x8000) == 0:
		return  # 这是查询包，忽略

	# 在包体中搜索 service_type 的文本（启发式）
	var service_local := service_type + "local"
	var packet_str := packet.get_string_from_ascii()

	# 尝试在包中找到 SRV/A 记录指向的端口和 IP
	# 简化策略：如果包来自一个私有 IP 且包含我们的服务类型标识，
	# 就认为该 IP 是目标设备，端口从包中提取或使用默认
	var svc_name := service_type.trim_suffix(".").trim_prefix("_").split("._")[0]
	if packet_str.find(svc_name) == -1:
		return  # 不是我们关心的服务

	# 尝试提取 SRV 记录中的端口（SRV 记录格式：priority(2) + weight(2) + port(2) + target）
	var port := _extract_port_from_packet(packet)
	if port <= 0:
		port = 47821  # fallback 到常见端口

	# 发现新设备或 IP 变化
	var new_device := { "ip": from_ip, "port": port }
	if _current_device.is_empty() or _current_device.get("ip") != from_ip or _current_device.get("port") != port:
		_current_device = new_device
		_miss_count = 0
		# 立即做一次 HTTP 验证
		_do_heartbeat()


func _extract_port_from_packet(packet: PackedByteArray) -> int:
	# 在 DNS 包中找 SRV 记录（type = 0x0021 = 33）
	# SRV RDATA: priority(2) + weight(2) + port(2) + target(...)
	for i in range(12, packet.size() - 12):
		# 找 type=SRV(0x00, 0x21) + class=IN(0x00, 0x01)
		if i + 10 < packet.size():
			if packet[i] == 0x00 and packet[i + 1] == 0x21 and packet[i + 2] == 0x00 and packet[i + 3] == 0x01:
				# 跳过 TTL(4) + rdlength(2) + priority(2) + weight(2)
				var port_offset := i + 4 + 4 + 2 + 2 + 2
				if port_offset + 1 < packet.size():
					return (packet[port_offset] << 8) | packet[port_offset + 1]
	return -1


func _build_mdns_ptr_query(name: String) -> PackedByteArray:
	var buf := PackedByteArray()

	# Transaction ID (0x0000 for mDNS)
	buf.append(0x00); buf.append(0x00)
	# Flags: standard query
	buf.append(0x00); buf.append(0x00)
	# Questions: 1
	buf.append(0x00); buf.append(0x01)
	# Answer/Authority/Additional: 0
	buf.append(0x00); buf.append(0x00)
	buf.append(0x00); buf.append(0x00)
	buf.append(0x00); buf.append(0x00)

	# QNAME: encode domain name
	var parts := name.trim_suffix(".").split(".")
	for part in parts:
		buf.append(part.length())
		buf.append_array(part.to_ascii_buffer())
	buf.append(0x00)  # 根标签

	# QTYPE: PTR (12 = 0x000C)
	buf.append(0x00); buf.append(0x0C)
	# QCLASS: IN (1) with unicast-response bit
	buf.append(0x80); buf.append(0x01)

	return buf


# ==============================================================================
# HTTP 心跳
# ==============================================================================

func _setup_http() -> void:
	_http = HTTPRequest.new()
	_http.timeout = 3.0
	add_child(_http)
	_http.request_completed.connect(_on_heartbeat_response)


func _do_heartbeat() -> void:
	if _current_device.is_empty():
		return
	var url := "http://%s:%d/v1/healthz" % [_current_device["ip"], _current_device["port"]]
	var err := _http.request(url)
	if err != OK:
		_on_heartbeat_fail()


func _on_heartbeat_response(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if result != HTTPRequest.RESULT_SUCCESS or response_code != 200:
		_on_heartbeat_fail()
		return

	# 解析 JSON
	var json := JSON.new()
	var parse_err := json.parse(body.get_string_from_utf8())
	if parse_err != OK:
		_on_heartbeat_fail()
		return

	var data: Dictionary = json.data if json.data is Dictionary else {}
	_miss_count = 0

	var info := {
		"ip": _current_device["ip"],
		"port": _current_device["port"],
		"app": data.get("app", ""),
		"version": data.get("version", ""),
		"ts": data.get("ts", 0),
	}

	if not _is_device_present:
		_is_device_present = true
		device_found.emit(info)

	heartbeat_received.emit(info)


func _on_heartbeat_fail() -> void:
	_miss_count += 1
	if _miss_count >= max_miss_count and _is_device_present:
		_is_device_present = false
		_current_device = {}
		device_lost.emit()


# ==============================================================================
# 定时器
# ==============================================================================

func _setup_timers() -> void:
	_heartbeat_timer = Timer.new()
	_heartbeat_timer.wait_time = heartbeat_interval
	_heartbeat_timer.autostart = true
	_heartbeat_timer.timeout.connect(_do_heartbeat)
	add_child(_heartbeat_timer)

	_mdns_timer = Timer.new()
	_mdns_timer.wait_time = mdns_query_interval
	_mdns_timer.autostart = true
	_mdns_timer.timeout.connect(_send_mdns_query)
	add_child(_mdns_timer)


# ==============================================================================
# 公开方法
# ==============================================================================

## 手动指定目标设备（跳过 mDNS 发现阶段）。
## 适用于用户已知手机 IP 的场景。
func set_target(ip: String, port: int = 47821) -> void:
	_current_device = { "ip": ip, "port": port }
	_miss_count = 0
	_do_heartbeat()


## 当前设备是否在场。
func is_present() -> bool:
	return _is_device_present


## 获取当前设备信息。为空表示未发现。
func get_device_info() -> Dictionary:
	return _current_device.duplicate()


## 强制立即发送一次 mDNS 查询。
func query_now() -> void:
	_send_mdns_query()


# ==============================================================================
# 清理
# ==============================================================================

func _cleanup() -> void:
	if _udp:
		_udp.close()
		_udp = null
