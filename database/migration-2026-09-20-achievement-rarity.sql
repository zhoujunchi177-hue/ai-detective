-- ============================================================================
-- 迁移脚本：成就稀有度分级
--
-- 适用场景：数据库已经初始化过（users / game_records 里有你的测试数据），
--           不想重跑 data.sql（那会清空数据），只补 achievements.rarity 一列。
--
-- 用法：
--   mysql --host=127.0.0.1 --port=3307 --user=root --database=mindtrace \
--         < database/migration-2026-09-20-achievement-rarity.sql
--
-- 说明：本脚本是幂等的，可以重复执行。
-- ============================================================================

USE mindtrace;

-- ---------------------------------------------------------------------------
-- 1. 加列。MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，所以先用 information_schema
--    判断列是否存在，再用动态 SQL 决定执行 ALTER 还是空操作。
-- ---------------------------------------------------------------------------
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'achievements'
      AND COLUMN_NAME = 'rarity'
);
SET @ddl = IF(@column_exists = 0,
    'ALTER TABLE achievements ADD COLUMN rarity VARCHAR(20) NOT NULL DEFAULT ''COMMON'' AFTER icon',
    'DO 0');
PREPARE alter_rarity FROM @ddl;
EXECUTE alter_rarity;
DEALLOCATE PREPARE alter_rarity;

-- ---------------------------------------------------------------------------
-- 2. 分级依据：拿到这枚徽章需要玩到多深，而不是「名字听起来厉不厉害」。
--      COMMON    结案即可 —— 正常走完一局就会拿到
--      RARE      需要额外调查动作（走到特定地点、发现特定类型的线索）
--      EPIC      需要破解谜题
--      LEGENDARY 需要调查到隐藏点位（is_hidden = 1），最容易漏掉
--    稀有度只是展示与排序用的内容数据，**不改变解锁条件和奖励**。
-- ---------------------------------------------------------------------------
UPDATE achievements SET rarity = 'COMMON'    WHERE code = 'FIRST_CASE';
UPDATE achievements SET rarity = 'RARE'      WHERE code = 'KEY_CONTRADICTION';
UPDATE achievements SET rarity = 'EPIC'      WHERE code = 'TIMELINE_MASTER';
UPDATE achievements SET rarity = 'LEGENDARY' WHERE code = 'FIRST_HIDDEN';

-- 兜底：任何没有分级的徽章都归到 COMMON，避免前端拿到空字符串。
UPDATE achievements SET rarity = 'COMMON' WHERE rarity IS NULL OR rarity = '';

-- ---------------------------------------------------------------------------
-- 2b. 同步 TIMELINE_MASTER 的描述文案。
--     它的解锁条件从「完成案件并提交结案推理」改成了「完成时间线排序谜题」
--     （原条件与 FIRST_CASE 完全重复，见 AchievementService 的注释）。
--     描述必须跟着条件一起改 —— 条件换了而描述还写着旧条件，等于骗玩家。
--     data.sql 里已经是新文案，这一步是为了让「全新安装」和「老库升级」两条路径一致。
-- ---------------------------------------------------------------------------
UPDATE achievements
SET description = '完成「缺失时间线排序」谜题。'
WHERE code = 'TIMELINE_MASTER' AND description <> '完成「缺失时间线排序」谜题。';

-- ---------------------------------------------------------------------------
-- 3. 校验：每个案件都要有谜题，否则 TIMELINE_MASTER 无从解锁。
--    同时打印分级分布，确认四档都有人。
-- ---------------------------------------------------------------------------
SELECT rarity, COUNT(*) AS badges FROM achievements GROUP BY rarity ORDER BY FIELD(rarity, 'COMMON', 'RARE', 'EPIC', 'LEGENDARY');

SELECT case_id, COUNT(*) AS puzzles,
       SUM(type = 'TIME_SORT') AS time_sort_puzzles
FROM puzzles
GROUP BY case_id;
