#!/usr/bin/env bash
#
# seed-scale.sh — 批量播种 N 租户 × M 活跃策略，用于规模/容量极限测试
#
# 背景：付费用户不受限流墙约束（限流只挡免费用户，付费=月限额），所以「系统能
# 撑多少租户 × 每租户多少 active 策略」的真瓶颈是 **存储** 和 **内存**，不是限流。
# 本脚本直接用 SQL generate_series 批量建策略（每个唯一 policyId + 真实 core_json），
# 让 /evaluate 能真正解析执行，从而实测存储占用与热运行内存墙。
#
# 关键设计（依据源码追踪）：
#   - /evaluate 冷路径：core_json 非空则直接执行 IR，NOT 解析 content
#     （PolicyCompiler.java:153-155）→ 可 SQL 批量塞 core_json + placeholder content。
#   - 每个策略需要两行：policy_versions(active=true) + policy_catalog(default_version_id)
#     （PolicySourceRepositoryImpl.java:22-54 的版本解析链）。
#   - core_json 用真实模板（perf/scale-template.core.json），按 module 名替换生成唯一副本。
#
# 用法：
#   TENANTS=100 POLICIES_PER=100 ./seed-scale.sh          # 播种 10,000 策略
#   TENANTS=1000 POLICIES_PER=100 PGPORT=55432 ./seed-scale.sh   # 100,000 策略
#   ./seed-scale.sh --stats-only                          # 只打印当前存储统计，不播种
#   ./seed-scale.sh --truncate                            # 清空重来
#
# 环境变量：
#   TENANTS(默认100) POLICIES_PER(默认100) PGHOST(localhost) PGPORT(55432)
#   PGDB(aster_cap) PGUSER(aster) PGPASS(aster) PG_CONTAINER(cap-verify-pg，用 podman exec)
#
set -euo pipefail

TENANTS="${TENANTS:-100}"
POLICIES_PER="${POLICIES_PER:-100}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-55432}"
PGDB="${PGDB:-aster_cap}"
PGUSER="${PGUSER:-aster}"
PGPASS="${PGPASS:-aster}"
PG_CONTAINER="${PG_CONTAINER:-cap-verify-pg}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PERF="$ROOT/perf"
TEMPLATE="$PERF/scale-template.core.json"

log() { printf '\033[1;36m[seed]\033[0m %s\n' "$*"; }

# psql 执行器：优先用 podman exec（容器内 PG），否则本机 psql
psql_exec() {
  if podman container exists "$PG_CONTAINER" 2>/dev/null; then
    podman exec -i -e PGPASSWORD="$PGPASS" "$PG_CONTAINER" psql -h localhost -U "$PGUSER" -d "$PGDB" "$@"
  else
    PGPASSWORD="$PGPASS" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDB" "$@"
  fi
}

print_stats() {
  log "存储统计："
  psql_exec -P pager=off <<'SQL'
SELECT
  (SELECT count(*) FROM policy_versions)                        AS policy_versions,
  (SELECT count(*) FROM policy_catalog)                         AS policy_catalog,
  (SELECT count(DISTINCT tenant_id) FROM policy_versions)       AS distinct_tenants,
  (SELECT count(*) FROM policy_versions WHERE active)           AS active_versions;

SELECT
  pg_size_pretty(pg_total_relation_size('policy_versions'))  AS policy_versions_total,
  pg_size_pretty(pg_total_relation_size('policy_catalog'))   AS policy_catalog_total,
  pg_size_pretty(
    pg_total_relation_size('policy_versions') + pg_total_relation_size('policy_catalog')
  ) AS combined_total;

-- 每策略平均字节（含索引 + toast）：总字节 / 策略数
SELECT
  CASE WHEN (SELECT count(*) FROM policy_versions) = 0 THEN 0
       ELSE (pg_total_relation_size('policy_versions') + pg_total_relation_size('policy_catalog'))
            / (SELECT count(*) FROM policy_versions)
  END AS bytes_per_policy,
  pg_size_pretty(pg_database_size(current_database())) AS total_db_size;
SQL
}

case "${1:-}" in
  --stats-only) print_stats; exit 0 ;;
  --truncate)
    log "清空 policy_catalog + policy_versions…"
    psql_exec -c "TRUNCATE policy_catalog, policy_versions RESTART IDENTITY CASCADE;" >/dev/null
    log "已清空。"; exit 0 ;;
esac

[[ -f "$TEMPLATE" ]] || { echo "缺 core_json 模板 $TEMPLATE（先跑 extract-core-template 或见 README）"; exit 1; }

TOTAL=$((TENANTS * POLICIES_PER))
log "开始播种：$TENANTS 租户 × $POLICIES_PER 策略 = $TOTAL 个活跃策略"
log "core_json 模板大小：$(wc -c < "$TEMPLATE") bytes/策略（pretty，与生产一致）"

# 把模板读成单行 SQL 字面量（转义单引号）。core_json 里的 module 名用占位符
# __MOD__，播种时替换成每个策略唯一的 module（避免全同 module 触发意外去重）。
# 注：/evaluate 按 function_name 找函数，module 名可不同；此处仅为唯一性 + 真实大小。
TEMPLATE_SQL=$(sed "s/scale.tmpl/__MOD__/g" "$TEMPLATE" | sed "s/'/''/g" | tr '\n' ' ')

# 分批播种（每批一个租户的全部策略），避免单条巨型 SQL。
# policy_versions 先插（BIGSERIAL 分配 id），再插 policy_catalog 关联 default_version_id。
START_TS=$(date +%s)
for ((t=1; t<=TENANTS; t++)); do
  TENANT="scale-t$(printf '%05d' $t)"
  # 单事务内：批量插 M 个 version + M 个 catalog。用 generate_series 生成 M 条。
  psql_exec -q <<SQL
BEGIN;
-- M 个 policy_versions（每个唯一 module scale.m<tenant>_<seq>，active=true，真实 core_json）
INSERT INTO policy_versions
  (policy_id, version, module_name, function_name, content, active, created_at,
   source_hash, tenant_id, status, is_default, core_json)
SELECT
  '${TENANT}-p' || s,
  1,
  'scale.m${t}_' || s,
  'evaluate',
  'placeholder',                                  -- content 占位（core_json 存在时不解析）
  true,
  NOW(),
  encode(sha256(('${TENANT}-p' || s)::bytea), 'hex'),   -- source_hash VARCHAR(64)=sha256 hex 恰好 64 字符（md5 在部分 PG 镜像 FIPS 下不可用）
  '${TENANT}',
  'APPROVED',
  true,
  replace('${TEMPLATE_SQL}', '__MOD__', 'scale.m${t}_' || s)::jsonb
FROM generate_series(1, ${POLICIES_PER}) AS s;

-- M 个 policy_catalog，default_version_id 关联刚插的 version
INSERT INTO policy_catalog
  (id, tenant_id, module_name, function_name, default_version_id, created_at, updated_at)
SELECT
  gen_random_uuid(), '${TENANT}', pv.module_name, pv.function_name, pv.id, NOW(), NOW()
FROM policy_versions pv
WHERE pv.tenant_id = '${TENANT}';
COMMIT;
SQL
  # 进度：每 50 租户报一次
  if (( t % 50 == 0 )); then
    log "  已播种 $t/$TENANTS 租户（$((t * POLICIES_PER)) 策略）…"
  fi
done
ELAPSED=$(( $(date +%s) - START_TS ))
log "播种完成：$TOTAL 策略，耗时 ${ELAPSED}s"

print_stats
log ""
log "外推：bytes_per_policy × 目标策略数 = 存储需求。"
log "生产共享 CNPG 20Gi（3 库分），aster_api 可用约 3–8Gi → 除以 bytes_per_policy = 存储墙上限。"
log "下一步：跑 capacity-scale.js 高基数访问，抓 podman stats 找热运行内存墙。"
