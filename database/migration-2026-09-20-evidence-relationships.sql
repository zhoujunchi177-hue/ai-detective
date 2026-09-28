-- ============================================================================
-- 迁移脚本：证据板关联表 evidence_relationships
--
-- 适用场景：数据库已经初始化过（里面有你的测试账号和进度），
--           不想重跑 schema.sql / data.sql（那会清空数据），只补这张新表。
--
-- 用法：
--   mysql --host=127.0.0.1 --port=3307 --user=root --database=mindtrace \
--         < database/migration-2026-09-20-evidence-relationships.sql
--
-- 说明：本脚本幂等，可以重复执行。
-- ============================================================================

USE mindtrace;

CREATE TABLE IF NOT EXISTS evidence_relationships (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    from_clue_id BIGINT NOT NULL,
    to_clue_id BIGINT NOT NULL,
    relation_type VARCHAR(30) NOT NULL DEFAULT 'SUPPORTS',
    note VARCHAR(300),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_from_clue FOREIGN KEY (from_clue_id) REFERENCES clues(id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_to_clue FOREIGN KEY (to_clue_id) REFERENCES clues(id) ON DELETE CASCADE,
    -- 同一对线索不区分方向，避免 A→B 和 B→A 重复出现
    UNIQUE KEY uk_evidence_pair (user_id, from_clue_id, to_clue_id),
    INDEX idx_evidence_user_case (user_id, case_id, created_at)
) ENGINE=InnoDB;

-- 校验：表已存在且字段齐全
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'mindtrace' AND TABLE_NAME = 'evidence_relationships'
ORDER BY ORDINAL_POSITION;
