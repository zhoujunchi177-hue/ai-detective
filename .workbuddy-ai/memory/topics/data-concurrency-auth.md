# 数据内容、事务与并发、鉴权错误码

> 从 `MEMORY.md` 拆出。改 data.sql/migration、来源链接、成就，或碰事务/并发/状态码时读这份。

## 事务边界约定
- **含 AI 调用的入口一律不带 `@Transactional`**：`ChatService.chat` / `ReasoningService.analyze` / `GameService.submit`。
  AI 要几秒~几十秒，圈进事务会占死连接（Hikari 池 10）。这种回归**功能测试看不出来**，只有并发才暴露。
- `GameService.submit` 用 `TransactionTemplate` 只圈**落库段**（发奖励 + 累加统计 + `evaluateAndUnlock` + `syncLeaderboard`）。
  `evaluateAndUnlock` 必须同事务（要读本事务内刚更新的 `user.completedCases`）。
- `TransactionBoundaryTest` 是反射护栏：加回注解或给 `AgentService`/`DeepSeekService` 加注解
  （会让调用方被隐式圈进事务），测试立刻失败。
- **AI 发起点只有 3 处**（`AgentService.npcReply`/`reasoning`/`finalAnalysis`）；改完 grep `agentService\.` / `deepSeekService\.` 复核。
- 可观测窗口：`node scripts/deepseek-stub.mjs`（固定 5s）+ 后端指向它，再 `bash scripts/check-trx-boundary.sh`
  （采样 `information_schema.innodb_trx`）。实测对照：带注解 5/5，去掉后 0/5。

## 并发写入约定
- **同一玩家的并发写必须串行化**：`UserMapper.lockById()` 用 `SELECT id FROM users WHERE id = ? FOR UPDATE`。
  涉及 `ClueUnlockService.unlock` / `PuzzleService.submit` / `EvidenceBoardService.create`。
- **🔴 加锁必须是事务里的第一条普通 SELECT**（REPEATABLE READ 读视图在第一条普通读时建立；
  先 `requireCase` 再抢锁 = 加了锁也不管用，`PuzzleService.submit` 实测复现，压测 8 轮全挂）。
- **别用 `INSERT IGNORE` 代替「先查后插」** → 唯一键共享锁互等 → `DeadlockLoserDataAccessException`
  （500，比原来的唯一键冲突 400 更糟）。
- 锁顺序固定「先 users 行，再业务表」→ 不会与 `GameService.submit` 互锁。
- 回归网：`scripts/verify-rc-race-hammer.py` + `scripts/verify-rc-resilience.py`。

## 鉴权与错误码约定
- **未登录一律 401，已登录但无权才 403**（Spring 默认 entry point 会把「没带令牌」也回 403，
  而前端 `client.ts` **只认 401** → 令牌过期不跳登录页）
  → `SecurityConfig` 显式设 `authenticationEntryPoint`/`accessDeniedHandler`。
- **客户端参数错误一律 400 且不回传内部实现**（`GlobalExceptionHandler` 覆盖
  `MethodArgumentTypeMismatchException`/`MissingServletRequestParameterException`/
  `HttpMessageNotReadableException`/`DataIntegrityViolationException` + 兜底通用文案 + `log.error`）。
  **别回传 `exception.getMessage()`**。
- DTO 长度约束必须与数据库列宽对齐（`user_puzzles.answer` VARCHAR(1000) → `@Size(max=1000)`）。
- **不存在的路径必须 404 不是 500**（`NoResourceFoundException` → 404 + warn）。
  **未登录访问不存在路径仍 401**（鉴权先于路由，不泄露路径是否存在）。
- 状态码改动会改变前端行为 → 改完必须重跑 `verify-api.ps1` + `verify-rc-security.py`。

## 日志接口约定
- `GET /api/cases/{id}/history` 返回**分页对象**：`{ entries, page, size, total, totalPages, hasMore, truncated, typeCounts }`。
- 筛选分页**全在服务端**；关键词匹配**展示文本**（`displayText` 已剥 AI JSON）→ 无法下推 SQL，
  扫最多 2000 条内存过滤，触上限时 `truncated=true`，UI 必须如实说「只扫描了最近一部分」。
- `type` 的 `ALL` 与不传必须等价（`normalizeType()` 归一成 `null`，否则拼出 `action_type='ALL'` 返回空）。
- `to` 含当天整天（`LocalTime.MAX`），由 `rangeEndCoversTheWholeDay` 钉住。
- 前端每页固定 20 条（`HISTORY_PAGE_SIZE`）。

## 成就模块约定
- 解锁条件**唯一真相源**是 `AchievementService.shouldUnlock`，数据库只存文案与奖励。
  四枚徽章条件必须两两不同（曾 `TIMELINE_MASTER` 与 `FIRST_CASE` 重复 → 第一次结案同时解锁两枚）；
  `everyBadgeHasADistinctCondition` 拦回归。
- `rarity` 只影响展示与排序，**不参与解锁、不改变奖励**；后端 `normalizeRarity` 与前端 `achievement-rarity.ts` 都兜底 COMMON。
- 改 `data.sql` 内容字段**必须同时改 `database/migration-*.sql`**（否则全新安装与老库升级分叉）。
- 分享图本地 Canvas 2D 画（`utils/share-card.ts`），不上传不调 AI；页脚必须带 REAL/ADAPTED/FICTIONAL 声明。

## 真实资料来源链接
- `source_url` 存在于**三张表**：`case_sources`（「文件」）/ `clues`（「线索」）/ `case_timeline`（「时间线」）。
  只改一张会漏大部分（实测 9 个去重链接对应 42 处引用）。改前先跑 `scripts/check-source-links.py`。
- 改链接必须**同时改 `data.sql` 与 `database/migration-*.sql`**。
- **换了机构的，署名必须一起改**（`source_name`/`source_type`/`source_reliability`/`description`）
  —— 把 A 家报道署名给 B 家 = 伪造来源，比死链严重。
- 判定死链至少**两条通路交叉验证**（curl / 真实浏览器 / Wayback API；WebFetch 能通 archive.org）。
  实测差异大：`www.bbc.com` 被 DNS 污染、`latimes.com`/`nytimes.com` 被连接重置、`fbi.gov` 无头浏览器被 Cloudflare 拦 403。
- 判定三类，**只有 404/410 才算失败**：`dead` / `blocked`(401/403/429 反爬) / `unreachable`(超时/重置)。后两类判失败会造假警报。
- `www.cnn.com` 301 → `edition.cnn.com`，比链接**比 pathname 而不是整串**。
- 「时间线」来源链接**只在条目展开时渲染**（`v-if="activeId === item.id"`）→ 校验要在展开当下点；
  「线索」页用 `discoveredClues`（新账号 0 条）→ 界面断言不能要求最小条数。

## 前端约定
- 滚动动画用全局指令 `v-reveal`（`src/directives/reveal.ts`，IntersectionObserver，只播一次，
  `prefers-reduced-motion` 下直接显示）；token 在 `main.css`：`--reveal-duration: 900ms`（要求 600–1500ms）、
  `--reveal-distance: 22px`。
- 声音：BGM / 音效两路独立（`bgmVolume`/`sfxVolume`），一键静音只翻开关不动滑块；
  自动播放被拒（`NotAllowedError`）用 `waitingForGesture` 表达；整个会话共用**一个** `AudioContext`。
- 全局指令要在 `src/types/directives.d.ts` 声明 `GlobalDirectives`。
- Element Plus 覆盖样式必须加 `:root ` 前缀。
