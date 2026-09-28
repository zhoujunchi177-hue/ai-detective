CREATE DATABASE IF NOT EXISTS mindtrace
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE mindtrace;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS leaderboard_entries;
DROP TABLE IF EXISTS user_achievements;
DROP TABLE IF EXISTS achievements;
DROP TABLE IF EXISTS evidence_relationships;
DROP TABLE IF EXISTS user_puzzles;
DROP TABLE IF EXISTS puzzles;
DROP TABLE IF EXISTS user_clues;
DROP TABLE IF EXISTS chat_messages;
DROP TABLE IF EXISTS investigation_records;
DROP TABLE IF EXISTS game_records;
DROP TABLE IF EXISTS npc_knowledge;
DROP TABLE IF EXISTS npc;
DROP TABLE IF EXISTS case_locations;
DROP TABLE IF EXISTS case_timeline;
DROP TABLE IF EXISTS clues;
DROP TABLE IF EXISTS suspects;
DROP TABLE IF EXISTS case_sources;
DROP TABLE IF EXISTS cases;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL UNIQUE,
    nickname VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    avatar VARCHAR(255),
    level INT NOT NULL DEFAULT 1,
    exp INT NOT NULL DEFAULT 0,
    coins INT NOT NULL DEFAULT 100,
    completed_cases INT NOT NULL DEFAULT 0,
    streak_days INT NOT NULL DEFAULT 1,
    total_score INT NOT NULL DEFAULT 0,
    last_login_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE cases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_code VARCHAR(32) NOT NULL UNIQUE,
    title VARCHAR(100) NOT NULL,
    subtitle VARCHAR(200),
    real_name VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    description LONGTEXT NOT NULL,
    case_type VARCHAR(30) NOT NULL,
    difficulty VARCHAR(20) NOT NULL,
    era VARCHAR(50) NOT NULL,
    location VARCHAR(100) NOT NULL,
    cover_url VARCHAR(500),
    status VARCHAR(30) NOT NULL,
    completion INT NOT NULL DEFAULT 0,
    players INT NOT NULL DEFAULT 0,
    content_rating INT NOT NULL DEFAULT 12,
    real_ratio DECIMAL(5,2) NOT NULL DEFAULT 0,
    adapted_ratio DECIMAL(5,2) NOT NULL DEFAULT 0,
    fictional_ratio DECIMAL(5,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cases_status (status),
    INDEX idx_cases_type (case_type)
) ENGINE=InnoDB;

CREATE TABLE case_sources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    source_name VARCHAR(200) NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    published_at DATE,
    description TEXT,
    source_reliability VARCHAR(30) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_source_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    INDEX idx_sources_case (case_id)
) ENGINE=InnoDB;

CREATE TABLE suspects (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    alias VARCHAR(100),
    role VARCHAR(100) NOT NULL,
    description TEXT,
    relationship TEXT,
    status VARCHAR(50),
    evidence_level VARCHAR(30),
    content_type VARCHAR(20) NOT NULL DEFAULT 'REAL',
    avatar VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_suspect_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    INDEX idx_suspects_case (case_id)
) ENGINE=InnoDB;

CREATE TABLE clues (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    clue_code VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(40) NOT NULL,
    importance INT NOT NULL DEFAULT 1,
    source_type VARCHAR(20) NOT NULL,
    source_name VARCHAR(200),
    source_url VARCHAR(1000),
    unlock_condition VARCHAR(200),
    location_key VARCHAR(80),
    npc_id BIGINT,
    keyword VARCHAR(100),
    is_real TINYINT(1) NOT NULL DEFAULT 1,
    is_hidden TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_clue_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    UNIQUE KEY uk_clue_code (case_id, clue_code),
    INDEX idx_clues_case (case_id),
    INDEX idx_clues_location (case_id, location_key),
    INDEX idx_clues_npc (npc_id)
) ENGINE=InnoDB;

CREATE TABLE case_timeline (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    event_time DATETIME,
    event_date_text VARCHAR(100) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    people VARCHAR(300),
    location VARCHAR(200),
    source_name VARCHAR(200),
    source_url VARCHAR(1000),
    content_type VARCHAR(20) NOT NULL DEFAULT 'REAL',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_timeline_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    INDEX idx_timeline_case (case_id, sort_order)
) ENGINE=InnoDB;

CREATE TABLE case_locations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    location_key VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    icon VARCHAR(50),
    map_x INT NOT NULL DEFAULT 50,
    map_y INT NOT NULL DEFAULT 50,
    unlock_condition VARCHAR(200) DEFAULT 'public',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_location_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    UNIQUE KEY uk_location_key (case_id, location_key),
    INDEX idx_locations_case (case_id)
) ENGINE=InnoDB;

CREATE TABLE npc (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    npc_key VARCHAR(80) NOT NULL,
    name VARCHAR(100) NOT NULL,
    avatar VARCHAR(255),
    description TEXT,
    personality TEXT,
    identity VARCHAR(150),
    location VARCHAR(150),
    greeting TEXT,
    hidden_information TEXT,
    relationship TEXT,
    content_type VARCHAR(20) NOT NULL DEFAULT 'FICTIONAL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_npc_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    UNIQUE KEY uk_npc_key (case_id, npc_key),
    INDEX idx_npc_case (case_id)
) ENGINE=InnoDB;

CREATE TABLE npc_knowledge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    npc_id BIGINT NOT NULL,
    knowledge_key VARCHAR(80) NOT NULL,
    topic VARCHAR(150) NOT NULL,
    content TEXT NOT NULL,
    disclosure_level VARCHAR(30) NOT NULL DEFAULT 'normal',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_npc FOREIGN KEY (npc_id) REFERENCES npc(id) ON DELETE CASCADE,
    UNIQUE KEY uk_knowledge_key (npc_id, knowledge_key),
    INDEX idx_knowledge_npc (npc_id)
) ENGINE=InnoDB;

CREATE TABLE game_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
    investigation_score INT NOT NULL DEFAULT 0,
    clue_score INT NOT NULL DEFAULT 0,
    timeline_score INT NOT NULL DEFAULT 0,
    logic_score INT NOT NULL DEFAULT 0,
    total_score INT NOT NULL DEFAULT 0,
    exp_reward INT NOT NULL DEFAULT 0,
    coin_reward INT NOT NULL DEFAULT 0,
    hypothesis TEXT,
    key_people TEXT,
    key_timeline TEXT,
    evidence_clue_ids TEXT,
    reasoning_text LONGTEXT,
    conclusion LONGTEXT,
    ai_report LONGTEXT,
    completed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_game_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_game_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    UNIQUE KEY uk_game_user_case (user_id, case_id),
    INDEX idx_game_ranking (total_score DESC)
) ENGINE=InnoDB;

CREATE TABLE investigation_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    location_id BIGINT,
    action_type VARCHAR(40) NOT NULL,
    result_text LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_investigation_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_investigation_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_investigation_location FOREIGN KEY (location_id) REFERENCES case_locations(id) ON DELETE SET NULL,
    INDEX idx_investigation_user_case (user_id, case_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    npc_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    structured_content LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_npc FOREIGN KEY (npc_id) REFERENCES npc(id) ON DELETE CASCADE,
    INDEX idx_chat_memory (user_id, case_id, npc_id, id)
) ENGINE=InnoDB;

CREATE TABLE user_clues (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    clue_id BIGINT NOT NULL,
    discovered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source VARCHAR(40),
    CONSTRAINT fk_user_clue_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_clue_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_clue_clue FOREIGN KEY (clue_id) REFERENCES clues(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_clue (user_id, clue_id),
    INDEX idx_user_clues_case (user_id, case_id)
) ENGINE=InnoDB;

CREATE TABLE puzzles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_id BIGINT NOT NULL,
    puzzle_key VARCHAR(80) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload LONGTEXT NOT NULL,
    correct_answer VARCHAR(1000) NOT NULL,
    unlock_reward_clue_id BIGINT,
    importance INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_puzzle_case FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE,
    CONSTRAINT fk_puzzle_clue FOREIGN KEY (unlock_reward_clue_id) REFERENCES clues(id) ON DELETE SET NULL,
    UNIQUE KEY uk_puzzle_key (case_id, puzzle_key),
    INDEX idx_puzzles_case (case_id)
) ENGINE=InnoDB;

CREATE TABLE user_puzzles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    puzzle_id BIGINT NOT NULL,
    completed TINYINT(1) NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    answer VARCHAR(1000),
    completed_at DATETIME,
    CONSTRAINT fk_user_puzzle_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_puzzle_puzzle FOREIGN KEY (puzzle_id) REFERENCES puzzles(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_puzzle (user_id, puzzle_id)
) ENGINE=InnoDB;

CREATE TABLE achievements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(300) NOT NULL,
    icon VARCHAR(50),
    -- 稀有度只影响展示与排序，不影响解锁条件和奖励。
    -- COMMON 结案即可 / RARE 需额外调查 / EPIC 需破解谜题 / LEGENDARY 需找到隐藏线索
    rarity VARCHAR(20) NOT NULL DEFAULT 'COMMON',
    reward_exp INT NOT NULL DEFAULT 0,
    reward_coins INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE user_achievements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    achievement_id BIGINT NOT NULL,
    unlocked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_achievement_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_achievement_achievement FOREIGN KEY (achievement_id) REFERENCES achievements(id) ON DELETE CASCADE,
    UNIQUE KEY uk_user_achievement (user_id, achievement_id)
) ENGINE=InnoDB;

CREATE TABLE leaderboard_entries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    rank_type VARCHAR(30) NOT NULL,
    rank_position INT NOT NULL DEFAULT 0,
    total_score INT NOT NULL DEFAULT 0,
    completed_cases INT NOT NULL DEFAULT 0,
    level INT NOT NULL DEFAULT 1,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_leaderboard_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_leaderboard_user_type (user_id, rank_type),
    INDEX idx_leaderboard_type (rank_type, total_score DESC)
) ENGINE=InnoDB;

-- 证据板上的连线：玩家把两条已获得的线索关联起来，形成自己的推理链。
-- 这是「玩家的推理产物」，不是案件事实：relation_type 与 note 都由玩家填写，
-- 后端只校验两条线索确实属于本案且已被该玩家发现。
CREATE TABLE evidence_relationships (
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

SET FOREIGN_KEY_CHECKS = 1;
