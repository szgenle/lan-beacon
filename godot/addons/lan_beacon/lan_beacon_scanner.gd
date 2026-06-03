## 局域网在场发现节点。
##
## 通过子网 HTTP 扫描发现运行 agentpost 的设备（端口已知，验证 /v1/healthz 响应）。
## 发现设备后定时 HTTP 轮询确认在场。
## [br][br]
## 用法：将此节点添加到场景树，配置参数，连接信号即可。
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

## 目标服务端口（与 agentpost 监听端口一致）。
@export var target_port: int = 47821

## HTTP 心跳轮询间隔（秒）。
@export_range(1.0, 60.0, 0.5) var heartbeat_interval: float = 5.0

## 连续失败多少次判定设备离场。
@export_range(1, 20) var max_miss_count: int = 3

## 子网扫描间隔（秒）。未发现设备时按此周期重复扫描。
@export_range(10.0, 300.0, 5.0) var scan_interval: float = 30.0

## 每批并发 HTTP 请求数量。越大扫描越快，但消耗越多。
@export_range(4, 64) var scan_concurrency: int = 32

## 扫描时单次 HTTP 请求超时（秒）。
@export_range(0.5, 5.0, 0.5) var scan_timeout: float = 1.5

## 目标应用标识。用于匹配 healthz 响应中的 app 字段。
## 为空时接受任何有效 healthz 响应（不过滤），非空则精确匹配。
@export var target_app: String = ""

## mDNS 服务类型（保留字段，供 plugin.cfg 描述用，不影响实际扫描逻辑）。
@export var service_type: String = "_lanbeacon._tcp."

## Bearer Token 共享密钥。非空时在所有 HTTP 请求中添加 Authorization 头。
## 为空时不发送鉴权头（向后兼容 v0.1）。
@export var auth_token: String = ""

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

var _heartbeat_http: HTTPRequest = null
var _heartbeat_timer: Timer = null
var _scan_timer: Timer = null

## 当前发现的设备信息；空 dict 表示未发现
var _current_device: Dictionary = {}
var _miss_count: int = 0
var _is_device_present: bool = false

## 扫描状态
var _scanning: bool = false
var _scan_queue: Array[String] = []  # 待扫描 IP 列表
var _scan_pool: Array[HTTPRequest] = []  # 并发 HTTP 请求池
var _scan_busy: Array[bool] = []  # 与 _scan_pool 等长，标记每个槽是否在飞行中
var _active_scans: int = 0


## 构建 HTTP 自定义头（token 非空时添加 Authorization 头）。
func _get_auth_headers() -> PackedStringArray:
	if auth_token.is_empty():
		return PackedStringArray()
	return PackedStringArray(["Authorization: Bearer " + auth_token])


## 构建 healthz URL，自动处理 IPv6 括号格式。
## IPv6 地址在 URL 中必须用方括号包裹：http://[fe80::1]:47821/v1/healthz
func _build_url(ip: String, port: int) -> String:
	if ":" in ip:
		# IPv6 地址需要方括号
		return "http://[%s]:%d/v1/healthz" % [ip, port]
	return "http://%s:%d/v1/healthz" % [ip, port]


# ==============================================================================
# 生命周期
# ==============================================================================

func _ready() -> void:
	_setup_heartbeat()
	_setup_scan_pool()
	_setup_timers()
	# 启动时立即扫描一次
	_start_scan()


func _exit_tree() -> void:
	_cleanup()


# ==============================================================================
# 子网扫描
# ==============================================================================

func _match_app(data: Dictionary) -> bool:
	## 判断 healthz 响应是否匹配目标应用。
	## target_app 为空时只要有 app 字段即认为匹配；非空则精确比较。
	var app_value := String(data.get("app", ""))
	if app_value.is_empty():
		return false  # 无 app 字段的响应不是合法 beacon
	if target_app.is_empty():
		return true  # 不过滤，接受任何带 app 字段的响应
	return app_value == target_app

func _get_local_subnets() -> Array[String]:
	## 返回本机所有私有 IP 的 /24 子网前缀（如 "192.168.31."）
	## 注：子网扫描仅适用于 IPv4（/24 最多 254 个地址）。
	## IPv6 子网无法枚举（/64 地址空间过大），请使用 set_target() 直接指定 IPv6 地址。
	var subnets: Array[String] = []
	var addrs := IP.get_local_addresses()
	for addr: String in addrs:
		# 只取 IPv4 私有地址；IPv6 无法通过子网扫描发现
		if ":" in addr:
			continue  # 跳过 IPv6
		var parts := addr.split(".")
		if parts.size() != 4:
			continue
		var first := int(parts[0])
		var second := int(parts[1])
		# RFC1918: 10.x.x.x / 172.16-31.x.x / 192.168.x.x
		var is_private := (
			first == 10
			or (first == 172 and second >= 16 and second <= 31)
			or (first == 192 and second == 168)
		)
		if not is_private:
			continue
		var prefix := "%s.%s.%s." % [parts[0], parts[1], parts[2]]
		if prefix not in subnets:
			subnets.append(prefix)
	return subnets


func _start_scan() -> void:
	if _scanning:
		return
	if _is_device_present:
		return  # 已在场，不需要扫描

	var subnets := _get_local_subnets()
	if subnets.is_empty():
		push_warning("LanBeaconScanner: No private subnets found")
		return

	# 取消上一轮可能仍在飞行的请求，防止本轮派发时遇到 ERR_BUSY 死循环。
	_cancel_inflight_scans()

	_scan_queue.clear()
	for prefix in subnets:
		for i in range(1, 255):
			_scan_queue.append(prefix + str(i))

	_scanning = true
	# 启动并发扫描
	_dispatch_scans()


func _cancel_inflight_scans() -> void:
	## 取消所有仍标记为 busy 的扫描请求，恢复槽位空闲。
	for i in range(_scan_pool.size()):
		if _scan_busy[i]:
			_scan_pool[i].cancel_request()
			_scan_busy[i] = false
	_active_scans = 0


func _dispatch_scans() -> void:
	## 遍历空闲槽位派发请求；不再使用 _active_scans 作为下标，避免与槽位错位。
	if not _scanning:
		return
	for i in range(_scan_pool.size()):
		if _scan_queue.is_empty():
			break
		if _scan_busy[i]:
			continue
		var ip := _scan_queue.pop_front()
		var http := _scan_pool[i]
		var url := "http://%s:%d/v1/healthz" % [ip, target_port]
		http.set_meta("scan_ip", ip)
		var err := http.request(url, _get_auth_headers())
		if err != OK:
			# 请求发不出去，槽位保持 idle，跳过该 IP，继续轮询其它槽。
			continue
		_scan_busy[i] = true
		_active_scans += 1


func _on_scan_response(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray, http: HTTPRequest, slot: int) -> void:
	# 入口立即把槽标记为 idle，后续如复用再重新置 busy。
	_scan_busy[slot] = false
	_active_scans = max(0, _active_scans - 1)
	var ip: String = http.get_meta("scan_ip", "")

	# 已停止扫描（例如已发现设备 / 已被取消）则只回收槽位。
	if not _scanning:
		return

	if result == HTTPRequest.RESULT_SUCCESS and response_code == 200:
		var parsed: Variant = JSON.parse_string(body.get_string_from_utf8())
		if parsed is Dictionary and _match_app(parsed):
			# 找到了！
			_scanning = false
			_scan_queue.clear()
			_current_device = { "ip": ip, "port": target_port }
			_miss_count = 0
			_is_device_present = true
			var info := {
				"ip": ip,
				"port": target_port,
				"app": parsed.get("app", ""),
				"version": parsed.get("version", ""),
				"ts": parsed.get("ts", 0),
				"meta": parsed.get("meta", {}),
			}
			device_found.emit(info)
			heartbeat_received.emit(info)
			return

	# 未命中，继续调度：优先复用本槽以维持并发度。
	if not _scan_queue.is_empty():
		var next_ip := _scan_queue.pop_front()
		var url := "http://%s:%d/v1/healthz" % [next_ip, target_port]
		http.set_meta("scan_ip", next_ip)
		var err := http.request(url, _get_auth_headers())
		if err == OK:
			_scan_busy[slot] = true
			_active_scans += 1
			return
		# 该槽请求失败，让其它空闲槽兜底
		_dispatch_scans()
		return

	# 队列已空，所有槽都收尾后结束本轮扫描
	if _active_scans <= 0:
		_scanning = false


# ==============================================================================
# HTTP 心跳
# ==============================================================================

func _setup_heartbeat() -> void:
	_heartbeat_http = HTTPRequest.new()
	_heartbeat_http.timeout = 3.0
	add_child(_heartbeat_http)
	_heartbeat_http.request_completed.connect(_on_heartbeat_response)


func _do_heartbeat() -> void:
	if _current_device.is_empty():
		return
	var url := _build_url(_current_device["ip"], _current_device["port"])
	var err := _heartbeat_http.request(url, _get_auth_headers())
	if err != OK:
		_on_heartbeat_fail()


func _on_heartbeat_response(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	if result != HTTPRequest.RESULT_SUCCESS or response_code != 200:
		_on_heartbeat_fail()
		return

	var parsed: Variant = JSON.parse_string(body.get_string_from_utf8())
	if not (parsed is Dictionary):
		_on_heartbeat_fail()
		return

	var data: Dictionary = parsed
	_miss_count = 0

	var info := {
		"ip": _current_device["ip"],
		"port": _current_device["port"],
		"app": data.get("app", ""),
		"version": data.get("version", ""),
		"ts": data.get("ts", 0),
		"meta": data.get("meta", {}),
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
		# 设备离场后启动重新扫描
		_start_scan()


# ==============================================================================
# 扫描请求池
# ==============================================================================

func _setup_scan_pool() -> void:
	_scan_pool.clear()
	_scan_busy.clear()
	for i in range(scan_concurrency):
		var http := HTTPRequest.new()
		http.timeout = scan_timeout
		add_child(http)
		# 用 bind 把 http 自身和槽位下标传入回调，便于复用与状态同步
		http.request_completed.connect(_on_scan_response.bind(http, i))
		_scan_pool.append(http)
		_scan_busy.append(false)


# ==============================================================================
# 定时器
# ==============================================================================

func _setup_timers() -> void:
	_heartbeat_timer = Timer.new()
	_heartbeat_timer.wait_time = heartbeat_interval
	_heartbeat_timer.autostart = true
	_heartbeat_timer.timeout.connect(_do_heartbeat)
	add_child(_heartbeat_timer)

	_scan_timer = Timer.new()
	_scan_timer.wait_time = scan_interval
	_scan_timer.autostart = true
	_scan_timer.timeout.connect(_start_scan)
	add_child(_scan_timer)


# ==============================================================================
# 公开方法
# ==============================================================================

## 手动指定目标设备（跳过扫描发现阶段）。
## 适用于用户已知手机 IP 的场景。支持 IPv4 和 IPv6 地址。
## IPv6 示例：set_target("fd12::abcd") 或 set_target("fe80::1")
func set_target(ip: String, port: int = 47821) -> void:
	_current_device = { "ip": ip, "port": port }
	_miss_count = 0
	_scanning = false
	_scan_queue.clear()
	_do_heartbeat()


## 当前设备是否在场。
func is_present() -> bool:
	return _is_device_present


## 获取当前设备信息。为空表示未发现。
func get_device_info() -> Dictionary:
	return _current_device.duplicate()


## 强制立即启动一次子网扫描。
func scan_now() -> void:
	_is_device_present = false
	_current_device = {}
	_start_scan()


# ==============================================================================
# 清理
# ==============================================================================

func _cleanup() -> void:
	_scanning = false
	_scan_queue.clear()
	_cancel_inflight_scans()
