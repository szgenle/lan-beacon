@tool
extends EditorPlugin


func _enter_tree() -> void:
	add_custom_type(
		"LanBeaconScanner",
		"Node",
		preload("lan_beacon_scanner.gd"),
		null  # 暂无自定义图标
	)


func _exit_tree() -> void:
	remove_custom_type("LanBeaconScanner")
