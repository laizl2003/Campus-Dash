#!/usr/bin/env bash
# RocketMQ 初始化：创建 DELAY 类型 topic 与消费组。
#
# 为什么要显式建 topic：5.x 的定时消息要求 topic 的 message.type=DELAY，
# 自动创建的普通 topic 会让 broker 拒收定时消息，报错信息还不太直观。
set -euo pipefail

BROKER_CONTAINER="${BROKER_CONTAINER:-dash-rmqbroker}"
NAMESRV="${NAMESRV:-rmqnamesrv:9876}"
CLUSTER="${CLUSTER:-DefaultCluster}"
TOPIC="${TOPIC:-errand-confirm-timeout}"
# P3：送达后 24h 自动结算（DELAY 类型，与超时流转同类）
TOPIC_AUTO_SETTLE="${TOPIC_AUTO_SETTLE:-errand-auto-settle}"
# P3：资金事件通知（普通类型，走事务消息——事务消息不支持延迟，所以必须是普通 topic）
TOPIC_FUND_EVENT="${TOPIC_FUND_EVENT:-errand-fund-event}"
# P5：延迟双删（DELAY 类型）
TOPIC_CACHE_EVICT="${TOPIC_CACHE_EVICT:-errand-cache-evict}"
GROUP="${GROUP:-dash-timeout-consumer}"

admin() {
  docker exec "$BROKER_CONTAINER" sh -c "cd /home/rocketmq/rocketmq-*/bin && sh mqadmin $*"
}

echo "创建 DELAY topic: $TOPIC"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC -a +message.type=DELAY" | tail -2

echo "创建 DELAY topic: $TOPIC_AUTO_SETTLE"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_AUTO_SETTLE -a +message.type=DELAY" | tail -1

echo "创建 TRANSACTION topic（事务消息用）: $TOPIC_FUND_EVENT"
# 关键：事务消息要求 topic 是 TRANSACTION 类型。建成 NORMAL 会在发送时报
# "Current message type not match with topic accept message types"（实测踩过）。
# 类型一旦建错无法原地修改，必须 deleteTopic 后重建。
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_FUND_EVENT -a +message.type=TRANSACTION" | tail -1

echo "创建 DELAY topic（延迟双删）: $TOPIC_CACHE_EVICT"
admin "updateTopic -n $NAMESRV -c $CLUSTER -t $TOPIC_CACHE_EVICT -a +message.type=DELAY" | tail -1

echo "创建消费组: $GROUP"
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g $GROUP" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-autosettle-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-fund-event-consumer" | tail -1
admin "updateSubGroup -n $NAMESRV -c $CLUSTER -g dash-cache-evict-consumer" | tail -1

echo "集群状态:"
admin "clusterList -n $NAMESRV" | tail -3
