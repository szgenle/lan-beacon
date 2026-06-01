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

## mDNS 服务类型（保留字段，供 plugin.cfg 描述用，不影响实际扫描逻辑）。
@export var service_type: String = "_agentpost._tcp."

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
var _active_scans: int = 0


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

func _get_local_subnets() -> Array[String]:
	## 返回本机所有私有 IP 的 /24 子网前缀（如 "192.168.31."）
	var subnets: Array[String] = []
	var addrs := IP.get_local_addresses()
	for addr: String in addrs:
		# 只取 IPv4 私有地址
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

	_scan_queue.clear()
	for prefix in subnets:
		for i in range(1, 255):
			_scan_queue.append(prefix + str(i))

	_scanning = true
	_active_scans = 0
	# 启动并发扫描
	_dispatch_scans()


func _dispatch_scans() -> void:
	## 从队列中取出 IP 分配给空闲的 HTTPRequest
	while _active_scans < _scan_pool.size() and not _scan_queue.is_empty():
		if not _scanning:
			break
		var ip := _scan_queue.pop_front()
		var http := _scan_pool[_active_scans]
		_active_scans += 1
		var url := "http://%s:%d/v1/healthz" % [ip, target_port]
		http.set_meta("scan_ip", ip)
		var err := http.request(url)
		if err != OK:
			# 请求发不出去，跳过
			_active_scans -= 1
			_dispatch_scans()
			return


func _on_scan_response(result: int, response_code: int, _headers: PackedStringArray, body: PackedByteArray, http: HTTPRequest) -> void:
	_active_scans -= 1
	var ip: String = http.get_meta("scan_ip", "")

	if result == HTTPRequest.RESULT_SUCCESS and response_code == 200:
		# 验证是否是 agentpost
		var parsed: Variant = JSON.parse_string(body.get_string_from_utf8())
		if parsed is Dictionary and String(parsed.get("app", "")) == "agentpost":
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
			}
			device_found.emit(info)
			heartbeat_received.emit(info)
			return

	# 未命中，继续调度
	if _scanning:
		if not _scan_queue.is_empty():
			# 复用这个 http 节点发下一个
			var next_ip := _scan_queue.pop_front()
			var url := "http://%s:%d/v1/healthz" % [next_ip, target_port]
			http.set_meta("scan_ip", next_ip)
			_active_scans += 1
			var err := http.request(url)
			if err != OK:
				_active_scans -= 1
		elif _active_scans <= 0:
			# 队列空且无活跃请求 → 扫描结束
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
	var url := "http://%s:%d/v1/healthz" % [_current_device["ip"], _current_device["port"]]
	var err := _heartbeat_http.request(url)
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
	for i in range(scan_concurrency):
		var http := HTTPRequest.new()
		http.timeout = scan_timeout
		add_child(http)
		# 用 bind 把 http 自身传入回调，方便复用
		http.request_completed.connect(_on_scan_response.bind(http))
		_scan_pool.append(http)


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
## 适用于用户已知手机 IP 的场景。
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
