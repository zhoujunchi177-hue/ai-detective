-- ============================================================================
-- 迁移脚本：调查地图节点解锁条件（地点状态由后端 Java 计算）
--
-- 适用场景：数据库已经初始化过（users 等表里有你的测试账号），
--           不想重跑 data.sql（那会清空数据），只补齐地点解锁条件。
--
-- 用法：
--   mysql --host=127.0.0.1 --port=3307 --user=root --database=mindtrace \
--         < database/migration-2026-09-20-location-nodes.sql
--
-- 说明：本脚本是幂等的，可以重复执行。
-- ============================================================================

USE mindtrace;

-- unlock_condition 语义（由 CaseQueryService.locationViews 解析）：
--   public                 默认开放
--   clue:CLUE-002          需先发现该线索
--   puzzle:1               需先破解该谜题（按谜题 ID）
--   location:lobby         需先调查该地点
-- 多个条件用逗号分隔，表示「全部满足」。

-- CASE-001 水箱回声：大厅/电梯开放 → 客人档案、供水系统 → 屋顶水箱
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 1 AND location_key = 'lobby';
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 1 AND location_key = 'elevator';
UPDATE case_locations SET unlock_condition = 'clue:CLUE-002'    WHERE case_id = 1 AND location_key = 'guest-room';
UPDATE case_locations SET unlock_condition = 'location:lobby'   WHERE case_id = 1 AND location_key = 'water-system';
UPDATE case_locations SET unlock_condition = 'clue:CLUE-006'    WHERE case_id = 1 AND location_key = 'rooftop';

-- CASE-002 剪影未署名：最后出现地点/发现区域开放 → 报刊档案 → 大陪审团档案
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 2 AND location_key = 'last-seen';
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 2 AND location_key = 'discovery-site';
UPDATE case_locations SET unlock_condition = 'location:last-seen' WHERE case_id = 2 AND location_key = 'newsroom';
UPDATE case_locations SET unlock_condition = 'clue:CLUE-022'    WHERE case_id = 2 AND location_key = 'grand-jury';

-- CASE-003 北加州的密文：两个案发地点开放 → 依次推进到密码分析工作台
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 3 AND location_key = 'lake-herman';
UPDATE case_locations SET unlock_condition = 'public'           WHERE case_id = 3 AND location_key = 'blue-rock';
UPDATE case_locations SET unlock_condition = 'location:blue-rock'      WHERE case_id = 3 AND location_key = 'lake-berryessa';
UPDATE case_locations SET unlock_condition = 'location:lake-berryessa' WHERE case_id = 3 AND location_key = 'presidio';
UPDATE case_locations SET unlock_condition = 'clue:CLUE-031'    WHERE case_id = 3 AND location_key = 'cipher-desk';

-- 校验：每个案件至少要有 2 个默认开放节点，否则玩家开局无路可走。
SELECT case_id,
       SUM(unlock_condition = 'public') AS open_nodes,
       COUNT(*)                         AS total_nodes
FROM case_locations
GROUP BY case_id;
