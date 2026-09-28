#!/bin/bash
# 观测一次 chat 请求期间，MySQL 中「未结束事务」的数量随时间的变化。
#
# 目的：证明 AI 调用期间数据库连接/事务有没有被长时间占用。
#   事务数长时间 >= 1  → 连接被占住（AI 调用在事务内）
#   事务数全程为 0     → 连接已归还（AI 调用在事务外）
#
# 前置：
#   1. 后端已启动，并把 AI 指向本地桩：DEEPSEEK_BASE_URL=http://127.0.0.1:9099
#      DEEPSEEK_API_KEY=stub-local（见 scripts/deepseek-stub.mjs）
#   2. 桩服务已在跑（固定延迟 5 秒以上，窗口太短会采不到样本）
#
# 用法：bash scripts/check-trx-boundary.sh
#
# 可覆盖的环境变量：
#   BASE          后端地址（默认 http://127.0.0.1:8080）
#   MYSQL_CLIENT  mysql 客户端路径
#   DB_HOST / DB_PORT / DB_USER  MySQL 连接信息（本项目自带实例在 3307）
#   CASE_ID / NPC_ID            用来发起的对话（默认 1 / 1）
#   ACCOUNT_USER / ACCOUNT_PASS 登录账号（默认 demo_investigator / demo123）
set -u

BASE=${BASE:-http://127.0.0.1:8080}
MYSQL_CLIENT=${MYSQL_CLIENT:-"/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe"}
DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3307}
DB_USER=${DB_USER:-root}
CASE_ID=${CASE_ID:-1}
NPC_ID=${NPC_ID:-1}
ACCOUNT_USER=${ACCOUNT_USER:-demo_investigator}
ACCOUNT_PASS=${ACCOUNT_PASS:-demo123}

if [ ! -x "$MYSQL_CLIENT" ]; then
  echo "找不到 mysql 客户端：$MYSQL_CLIENT" >&2
  echo "请用 MYSQL_CLIENT=/path/to/mysql 指定。" >&2
  exit 2
fi

trx_count() {
  "$MYSQL_CLIENT" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" --skip-column-names \
    -e "SELECT COUNT(*) FROM information_schema.innodb_trx;" 2>/dev/null | tr -d '\r\n '
}

TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$ACCOUNT_USER\",\"password\":\"$ACCOUNT_PASS\"}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

if [ -z "${TOKEN:-}" ]; then
  echo "登录失败，无法继续（后端是否已启动？）" >&2
  exit 2
fi

BASELINE=$(trx_count)
MSG="trx-check-$(date +%s)"
START=$(date +%s%3N)
curl -s -m 120 -X POST "$BASE/api/cases/$CASE_ID/chat" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d "{\"npcId\":$NPC_ID,\"message\":\"$MSG\"}" > /dev/null &
PID=$!

SAMPLES=0; MAX=0; NONZERO=0; OUT=""
while kill -0 $PID 2>/dev/null; do
  N=$(trx_count)
  T=$(( $(date +%s%3N) - START ))
  if [ -n "$N" ]; then
    SAMPLES=$((SAMPLES+1))
    [ "$N" -gt "$MAX" ] && MAX=$N
    [ "$N" -gt 0 ] && NONZERO=$((NONZERO+1))
    OUT="$OUT ${T}:${N}"
  fi
done
wait $PID
END=$(( $(date +%s%3N) - START ))

echo "请求总耗时 ${END}ms | 采样 ${SAMPLES} 次 | 未结束事务峰值 ${MAX} | 非零样本 ${NONZERO}"
echo "（发起前基线事务数 ${BASELINE}）"
echo "采样序列(ms:未结束事务数) =$OUT"

if [ "$SAMPLES" -lt 3 ]; then
  echo "→ 样本太少，AI 窗口可能太短 —— 请确认后端指向了带固定延迟的桩服务"
  exit 1
fi

if [ "$NONZERO" -eq 0 ]; then
  echo "→ AI 调用期间数据库连接已归还（事务未跨 AI 调用）"
else
  echo "→ 存在跨 AI 调用的事务（${NONZERO}/${SAMPLES} 个样本观测到未结束事务）"
  exit 1
fi
