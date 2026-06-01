# lan-beacon 开发常用命令
# 使用方式：make <target>

.PHONY: help assemble test lint check clean publish-local

help:
	@echo "可用命令："
	@echo "  make assemble       编译 debug + release AAR"
	@echo "  make test           运行单元测试"
	@echo "  make lint           运行 Android Lint"
	@echo "  make check          test + lint 一起跑"
	@echo "  make clean          清理构建产物"
	@echo "  make publish-local  发布到 mavenLocal（本地联调用）"

assemble:
	./gradlew :android:assembleDebug :android:assembleRelease

test:
	./gradlew :android:test

lint:
	./gradlew :android:lint

check: test lint

clean:
	./gradlew clean

publish-local:
	./gradlew :android:publishToMavenLocal
