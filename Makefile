# 演示环境快捷入口；实际逻辑在 scripts/demo.sh，Windows 可直接使用 scripts/demo.ps1。
.PHONY: demo demo-down demo-reset smoke env prepull

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

# 预拉取全部外部镜像（离线或弱网环境先在有网机器上执行）：compose 中的基础设施/监控镜像，以及 scripts/tool-images.env 中固定摘要的脚本工具镜像。
prepull:
	docker compose --profile app --profile monitoring pull --ignore-buildable
	sed -n 's/^[A-Z_]*=//p' scripts/tool-images.env | xargs -n1 docker pull
