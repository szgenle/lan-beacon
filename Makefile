# lan-beacon 顶层入口
# Monorepo 结构下，每个平台（android/、windows/、macos/）是独立工程，
# 顶层 Makefile 只负责跳转到对应目录调用子工具链。
# 命名约定：<平台>-<动作>，避免未来多平台名字冲突。
# 使用方式：make <target>

.PHONY: help \
        android-assemble android-test android-lint android-check android-clean android-publish-local

help:
	@echo "可用命令："
	@echo "  Android 端："
	@echo "    make android-assemble       编译 debug + release AAR"
	@echo "    make android-test           运行单元测试"
	@echo "    make android-lint           运行 Android Lint"
	@echo "    make android-check          test + lint 一起跑"
	@echo "    make android-clean          清理构建产物"
	@echo "    make android-publish-local  发布到 mavenLocal（本地联调用）"
	@echo ""
	@echo "  Windows / macOS 端：待实现"

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

