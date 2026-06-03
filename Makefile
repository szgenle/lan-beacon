# lan-beacon 顶层入口
# Monorepo 结构下，每个平台（android/、godot/）是独立子工程，
# 顶层 Makefile 只负责跳转到对应目录调用子工具链。
# 命名约定：<平台>-<动作>，避免未来多平台名字冲突。
# 使用方式：make <target>

.PHONY: help \
        android-assemble android-test android-lint android-check android-clean android-publish-local \
        scanner-build scanner-test scanner-clean scanner-publish-local \
        godot-install

help:
	@echo "可用命令："
	@echo "  Android 端（beacon 广播库）："
	@echo "    make android-assemble       编译 debug + release AAR"
	@echo "    make android-test           运行单元测试"
	@echo "    make android-lint           运行 Android Lint"
	@echo "    make android-check          test + lint 一起跑"
	@echo "    make android-clean          清理构建产物"
	@echo "    make android-publish-local  发布到 mavenLocal（本地联调用）"
	@echo ""
	@echo "  Scanner 桌面端（KMP 发现库）："
	@echo "    make scanner-build          编译 JVM JAR"
	@echo "    make scanner-test           运行单元测试"
	@echo "    make scanner-clean          清理构建产物"
	@echo "    make scanner-publish-local  发布到 mavenLocal（本地联调用）"
	@echo ""
	@echo "  Godot 桌面端（discovery 客户端）："
	@echo "    make godot-install DEST=<path>  将插件复制到目标 Godot 项目的 addons/"
	@echo ""
	@echo "  示例：make godot-install DEST=~/Dev/aipet"

android-assemble:
	cd android && ./gradlew :lib:assembleDebug :lib:assembleRelease

android-test:
	cd android && ./gradlew :lib:test

android-lint:
	cd android && ./gradlew :lib:lint

android-check: android-test android-lint

android-clean:
	cd android && ./gradlew clean

android-publish-local:
	cd android && ./gradlew :lib:publishToMavenLocal

# ---- Scanner 桌面端（KMP） ----

scanner-build:
	cd scanner && ./gradlew :lib:build

scanner-test:
	cd scanner && ./gradlew :lib:jvmTest

scanner-clean:
	cd scanner && ./gradlew clean

scanner-publish-local:
	cd scanner && ./gradlew :lib:publishToMavenLocal

# ---- Godot 桌面端 ----

# DEST 必须指向目标 Godot 项目根目录（包含 project.godot 的目录）
DEST ?=

godot-install:
ifeq ($(DEST),)
	$(error 请指定目标项目路径，例如：make godot-install DEST=~/Dev/aipet)
endif
	@mkdir -p "$(DEST)/addons/lan_beacon"
	@cp -R godot/addons/lan_beacon/* "$(DEST)/addons/lan_beacon/"
	@echo "✅ 已将 lan_beacon 插件复制到 $(DEST)/addons/lan_beacon/"
	@echo "→ 在 Godot 编辑器 Project > Project Settings > Plugins 中启用 'LanBeacon Discovery'"

