# 演示环境快捷入口；实际逻辑在 scripts/demo.sh，Windows 可直接使用 scripts/demo.ps1。
.PHONY: demo demo-down demo-reset smoke env

# 一条命令启动完整演示环境并执行冒烟测试。
demo:
	sh scripts/demo.sh up

# 停止容器，保留数据卷。
demo-down:
	sh scripts/demo.sh down

# 删除数据卷和本项目构建的本地镜像后重新启动，回到干净演示状态。
demo-reset:
	sh scripts/demo.sh reset

# 对已运行的环境单独执行冒烟测试。
smoke:
	sh scripts/smoke-test.sh

# 仅生成或补全 .env。
env:
	sh scripts/init-env.sh
