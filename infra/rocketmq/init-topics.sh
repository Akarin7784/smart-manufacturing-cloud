#!/usr/bin/env bash
# 一次性初始化：等待 Broker 注册到 NameServer，再按 topics.txt 幂等创建 Topic（updateTopic 重复执行结果一致）。
# 在 rocketmq 镜像内运行，只用镜像自带的 mqadmin，不引入额外依赖。
set -eu
ns=${NAMESRV_ADDR:-namesrv:9876}
cluster=${ROCKETMQ_CLUSTER:-DefaultCluster}
admin="$ROCKETMQ_HOME/bin/mqadmin"
# 工具默认堆 1G，演示环境压到 128M，避免一次性容器挤占共享机器内存。
export JAVA_OPT_EXT="-Xms64m -Xmx128m -Xmn32m"

i=0
until "$admin" clusterList -n "$ns" 2>/dev/null | grep -qw "broker-a"; do
  i=$((i+1)); [ "$i" -le 60 ] || { echo "Broker 未注册到 NameServer" >&2; exit 1; }
  sleep 2
done

grep -v '^\s*#' /topics.txt | grep -v '^\s*$' | while read -r topic; do
  "$admin" updateTopic -n "$ns" -c "$cluster" -t "$topic" -r 4 -w 4 >/dev/null
  echo "已创建/更新 Topic：$topic"
done
