# MindTrace AI 推理档案 — 项目长期记忆

> 本文件**常驻注入**，只放"每轮都该知道"的内容。细节按主题拆到 `topics/`，需要时再读：
> - `topics/verification.md` — 验收脚本一览、脚本编写约定、CASE-001 打满配方
> - `topics/ai-npc.md` — AI 链路约定、NPC 对话约定全文、Token 计量与提示词压缩
> - `topics/data-concurrency-auth.md` — data.sql/migration、来源链接、成就、事务边界、并发写入、鉴权错误码

## 项目定位与硬约束
AI 推理侦探游戏。Vue 3 + TS + Vite 6 + Pinia / Spring Boot 3.4.5 + MyBatis-Plus + MySQL / DeepSeek API。
用户硬性约束（每轮适用）：
- 不删现有代码、不重初始化、不重写已完成功能；先查工作区实际状态，**以文件为准，不假设历史对话**
- **不假装测试成功**：没真正跑通的必须说明
- `DEEPSEEK_API_KEY` 只走环境变量，绝不写入 Git / 前端
- 内容区分 REAL / ADAPTED / FICTIONAL，**AI 不得为现实悬案伪造结论**
- 未配置 Key 时必须明确说明「AI 实际调用未验证」
- 破坏性测试**必须用临时数据库**；不为让测试变绿而改断言；**BLOCKED 不算 PASS**

## ⚠️ 铁律一：改完文件必须复核内容
编辑会**静默丢失**。不要只看 `git status` 就提交，必须 grep 关键字符串：
```bash
for s in "关键字符串1" "关键字符串2"; do grep -q "$s" FILE && echo "OK $s" || echo "MISS $s"; done
git show <sha>:FILE | grep -c "关键字符串"   # 提交后再查已入库版本
```
- 同一批编辑里**靠后的几处最容易丢**。
- **同一条消息里对同一个文件发多个 Edit → 丢失更新**（2026-09-20 复现：给 `ChatService` 同时发「加 `@Transactional`」+「加 import」，import 在、注解消失）。
  → **同一文件的多处编辑必须分步执行（一次一个 Edit），每步 grep 复核后再发下一个。**
- **别把备份 `cp` 到已有基线上**（2026-09-21：`cp 计量文件 基线文件` 覆盖了基线，而测量脚本每次会先删文件 → 明细永久丢失，只能靠逐轮打印的记录重建）。

## ⚠️ 铁律二：`.ps1` 必须带 UTF-8 BOM
PS 5.1 无 BOM 时按系统 ANSI（GBK）解析，**行尾汉字会吃掉换行**：
- 症状一（最危险）：某条 `Assert-True` **凭空消失**，日志既无 PASS 也无 FAIL，脚本报「All checks passed」——看起来全绿，其实少跑一条
- 症状二：语法错误 `表达式或语句中包含意外的标记"}"`
- **排查**：`[System.Management.Automation.Language.Parser]::ParseFile($path,[ref]$t,[ref]$e)` 看 `$e.Count`
- **修法**：Python `encoding='utf-8-sig'` 补 BOM（**Write 工具写出的 .ps1 没有 BOM**）
- **只有新写的脚本会炸**，老脚本一直正常 → 容易误判成「新代码写错了」
- 配套自检：`Assert-True` 里累加 `$script:executed`，用 `[regex]::Matches` 数源码声明的断言数，对不上就 FAIL
- **姊妹问题**：PS 5.1 `Invoke-RestMethod` 无显式 charset 时按 ISO-8859-1 解码 → 中文乱码。取原始字节显式 UTF-8 再 `ConvertFrom-Json`（`start-all.ps1` 的 `Invoke-JsonUtf8`）。**只影响 PS 脚本显示，前端 axios 不受影响**
- PS 5.1 续行：运算符必须留在**行尾**（`('a' + ⏎ 'b')` 合法）。改 `.ps1` 后**先跑解析器**再跑脚本

## ⚠️ 铁律三：别写出「恒真式」断言
2026-09-21 在 `verify-api.ps1` 发现 `($chatHistory.data.Count -ge 0)` —— 断言名叫「returns persisted messages」，其实**一条都没查**，永远不可能失败。同一天我自己替换时又写出 `(-ne 12 -or -eq 12)`，**同类错误连犯两次**。
- 写完每条断言自问：**什么情况下它会 FAIL？** 答不上来就是恒真式。
- 也别写「在全新安装上必然失败」的断言（`data.sql` 会 `TRUNCATE chat_messages`，写死 `-gt 12` 必假失败）→ 交给能自己播种的脚本。

## 环境与启动（Windows 踩坑）
- JDK `D:\jdk`（系统 `JAVA_HOME` 为空）。Java 25 运行，pom release=21
- MySQL **项目实例在 3307**（datadir `.runtime/mysql/data`）；系统 3306 的 mysqld 不是本项目实例
- 端口：后端 8080 / 前端 5173 / MySQL 3307。**`vite preview` 必须跑 5173**（后端 CORS 只放行 5173）
- `vite dev` 在本沙箱会崩（删 `deps_temp_*` 被 safe-delete 拦）→ 用 `vite preview` 验证生产构建；**`vite build` 的 `emptyDir(dist)` 也会被拦** → 需 `dangerouslyDisableSandbox: true` 重跑
- 本环境 python 是 Windows 原生，`/tmp/xxx` 在 python 里不存在（bash 的 `>` 可以）→ 脚本写文件用项目相对路径
- 后端构建：**Git Bash 跑 mvn**（PowerShell 工具执行 .cmd 无输出），且**必须在 `backend/` 目录下跑**（根目录没有 pom）：
  `cd backend && export JAVA_HOME="D:\\jdk" && C:/Users/ZJC/.m2/wrapper/dists/apache-maven-3.9.9/*/bin/mvn.cmd -o -B <goal>`
- **改后端代码后必须重新编译并重启**，否则 8080 上仍是旧构建。`spring-boot:run` 启动时会重编译
- **改完脚本要重启进程才生效**（2026-09-21：改了代理脚本但没重启 node 进程，跑出来的数据缺字段）
- PowerShell 工具**不回显 stdout**，脚本内 `exit` 会终止 shell → 让脚本自己写 `-LogPath`；**不能 spawn 子进程**，用 `& "绝对路径\x.ps1"` 在当进程执行
- Bash 工具里**禁止调用 powershell.exe**（安全策略拒绝）
- **`reg.exe` / `wmic` 不可用**（黑名单 / 新版 Windows 已移除）→ 读环境变量用 Python `winreg`，读进程命令行用 PowerShell `Get-CimInstance Win32_Process`
- `taskkill //PID` 在 Git Bash 里参数会被路径转换 → 用 PowerShell `Get-NetTCPConnection -LocalPort X -State Listen` + `Stop-Process`

### 启动配方
**优先 `scripts/start-all.ps1`**（一键起三端 + 等就绪 + 真实冒烟）。手工配方（排障用）：
1. MySQL 3307：`"$MYSQL_HOME/bin/mysqld.exe" --datadir="<项目>/.runtime/mysql/data" --port=3307 --console`
2. 后端 8080：`python scripts/start-backend-with-key.py`（**后台跑**；Key 从注册表读、不落盘）。`start-backend.ps1` 不带 Key，AI 走降级
3. 前端 5173：`cd frontend && npx vite preview --port 5173`（**后台跑**）

- **⚠️ `start-backend.ps1` / `start-frontend.ps1` 是前台常驻进程，永不返回** → 「依次执行三个脚本」最多走到第 2 步，前端根本没起来。必须各开独立窗口（用户 2026-09-21 实际踩过）
- 端口占用两种表现：`vite` **悄悄换端口**（已加预检 + `--strictPort` 改成报错退出）；`spring-boot:run` 会明确失败 `Port 8080 was already in use.`
- 后端首次启动要编译 30s–2min，等 `/api/health` 返回 200 再登录。停全部：`scripts/stop-all.ps1`
- **三个服务都不跨会话存活**，下个会话必须重拉。判断死活别信 `.runtime/mysql/mysqld.pid`（陈旧 pid），以 `netstat -ano | grep LISTENING` 为准
- 默认账号 `demo_investigator` / `demo123`（`database/data.sql:25`）
- `GET /api/cases/{id}` 把案件信息嵌在 **`caseInfo`** 里（同层还有 locations/npcs/clues/puzzles/timeline/sources/suspects/progress）
- **冒烟别只看端口**：`/api/health` UP 时数据库可能没连上（Hikari 懒连接）→ 必须真登录 + 拉一次案件列表
- **`setx` 设的环境变量，已启动的进程继承不到**（曾把 `deepSeekConfigured:false` 误判成「环境里没有 Key」）。设完必须重启终端/IDE
- **要起「长期运行 + 需注入环境变量」的进程**：不能用 PowerShell 工具、不能用 `DETACHED_PROCESS`（会被回收，日志 0 字节）；**正解** Python `subprocess.Popen(..., shell=True)` + `proc.wait()` 并作为**后台任务**跑 → 已封装成 `scripts/start-backend-with-key.py`

## 验证工作流（按改动层次跑，细节见 `topics/verification.md`）
1. 后端 `mvnw.cmd -o -B test` → **100 条全绿**：`AgentFallbackTest`(10)/`NpcHistoryDedupTest`(7)/`NpcPromptConstraintTest`(7)/`TextStyleTest`(6)/`DeepSeekServiceTest`(4)/`GlobalExceptionHandlerTest`(3)/`AchievementServiceTest`(8)/`CaseQueryServiceTest`(33)/`EvidenceBoardServiceTest`(12)/`PuzzleServiceTest`(4)/`ReasoningServiceTest`(3)/`TransactionBoundaryTest`(3)
   - **不要用 Mockito**：Java 25 + byte-buddy 1.15.11 不兼容，测试一律无 Mock 写法
   - 覆盖率 `python scripts/measure-coverage.py`：**基线 指令 14.9% / 分支 30.7%，部分覆盖行 0**。低是**正常的**（纯函数走单测，服务/控制器走接口与 E2E）。**只看 `mb>0 且 cb>0` 的部分覆盖行，别看总分**
2. 接口 `scripts/verify-api.ps1 -LogPath .runtime/verify-api.log` → 82 条（`declared=82 executed=81 skipped=0 failed=0`，差值 1 是自检行自身）
3. 前端 `npm run build`（`vue-tsc -b && vite build`）→ 主包 ~76 kB，最大分块 ~110 kB
4. 界面 `node frontend/scripts/visual-check.mjs`（64 条）→ 交互 `puzzle-drag-check.mjs` → **NPC 多轮对话 `npc-chat-ui-check.mjs`（11 条）** → 全链路 `e2e-full-loop.mjs`（29 项）
5. **真实 AI**（改了 Prompt/Agent/上下文后必跑）：`python scripts/verify-live-ai.py`、`cd frontend && node scripts/ai-live-check.mjs`
6. 错误映射 `python scripts/verify-ai-error-mapping.py`（8 条，需先起故障桩 + 后端指向桩，**跑完必须切回真实 API**）
7. 计分/奖励 `python scripts/verify-reward-bonus.py`

**改了提示词/Agent/上下文后，行为复验三层缺一不可**：`mvn test` + `verify-npc-voice.py` × 7 角色（`NPC_VOICE_NPC_ID=1..7`）+ `npc-chat-ui-check.mjs`。

## 架构关键点
- Agent 链：`Controller → AgentService → ContextBuilder → PromptBuilder → DeepSeekService → ConversationMemory`
- Java 只把**玩家已获得线索**交给推理上下文，只把**当前 NPC 普通级知识**交给对话上下文
- `jackson.default-property-inclusion: non_null` → **null 字段整个省略**，前端类型用可选属性
- DeepSeek 是推理模型，**思维链 token 计入 `max_tokens`**：JSON 模式 8000 / 对话 1200
- 模型名：`deepseek-flash`（默认）、`deepseek-v4-pro`
- **成就只在结案提交时判定**（`AchievementService.evaluateAndUnlock()` 只被 `GameService` 调）；`achievements.icon` 存 lucide 名，前端用**显式映射**
- **对话：用户消息必须在调 AI 之前落库**（`ConversationMemory.saveImmediately`，`REQUIRES_NEW`，**必须跨 bean 调用才生效**）。推理记录刻意不这样改
- **`ConversationMemory` 有两个上限，绝不能共用一个常量**：`MAX_RECENT_MESSAGES=12`（喂模型，要截断省 token）/ `MAX_HISTORY_MESSAGES=200`（界面回看，玩家要完整）。2026-09-21 界面接口误用 `recent()` → 问满 10 轮刷新后**前 4 轮凭空消失**（数据完好，只是被截断）。`ChatService.history()` 必须用 `history()`，`ContextBuilder` 用 `recent()`
- **`/api/health` 报 UP ≠ 数据库可用**（Hikari 懒连接）

## Token 与提示词（摘要，详见 `topics/ai-npc.md`）
- 提示词 system 现为 **1063 字**（2026-09-21 从 1294 压缩，-17.9%，44 条约束零丢失），上限由 `NpcPromptConstraintTest` 钉住
- **`deepseek-flash` 空闲档：输入·缓存命中 0.02 / 输入·未命中 1.0 / 输出 4.0 元每百万** → 命中是未命中的 1/50
- **费用结构：命中输入 2.8% / 未命中输入 34.8% / 输出 62.3%** → 压提示词省的是最便宜那 2.8%，**「省 token」≠「省钱」**
- **别用 prompt 均值比较提示词改动**（回复长度随机波动 ±30% 会盖过效果）→ 拟合 `prompt = a × 历史字符 + b`，比**固定成本 b**
- **提问曾被发两次**（历史最后一条 + 追加的 `playerQuestion`），已由 `ContextBuilder.stripCurrentQuestion()` 去掉：首轮 prompt 1139 → 972，8 轮合计 -13.5%。**但它只省约 7~9 token/轮**；我曾以为它还能修复缓存前缀，**推断被实测推翻**（重复项在最末尾，从不落在共享前缀里）
- **缓存只覆盖 `system + 注入上下文`，历史部分不进缓存**；`cacheHit` 恒为 **128 的整数倍**（按 128 量化）
- **思考可以关掉，而且这是最大的省 token 杠杆**（2026-09-21 推翻上一轮「动不了」的结论）：
  - 上游**只认** `thinking: {"type":"disabled"}`；网上/第三方文档普遍推荐的 `enable_thinking:false`
    **会被接受但完全无效**（实测思考照旧）—— 别照抄文档，用 `scripts/probe-thinking-param.py` 问上游
  - 开关：`deepseek.disable-thinking` / `DEEPSEEK_DISABLE_THINKING`，**默认 false = 不改行为**；
    请求体组装已抽成 static `buildPayload()`，由 `DeepSeekThinkingSwitchTest`（4 条）钉住字段
  - 实测（8 轮固定问题，NPC 1）：completion **124.9 → 42.0（−66%）**，每轮费用 **≈849 → ≈517（−39%）**；
    单发对照：对话 210→12、JSON 463→41（**仍是合法 JSON**）；延迟从数秒降到 0.6~1.2 秒
  - **JSON 链路（推理审阅/结案评估）端到端也量了**（`measure-reasoning-tokens.py`，3 推理 + 1 结案）：
    输出 8928 → **2695（−70%）**、思考 6001 → **0**、费用 37126.6 → 13599.8（**−63%**，同口径约 −65%）；
    4 次调用全 `http=200/aiAvailable=true`，confidence 12/45/22 有区分度，**功能未坏**
  - **行为未退化**：`mvn test` 104/104、`verify-npc-voice.py` ×7 `failed=0/blocked=0`、
    `npc-chat-ui-check.mjs` 11/11；注入与越界提问仍拒绝且不给现实案件下定论
  - **但默认保持 false**：属行为变更，且用户 Phase G 的授权是「优化提示词」，开不开由用户决定

## 已知遗留（已识别，未修）
- `TIMELINE_MASTER` 只能从 CASE-001 解锁（只有它有 `TIME_SORT` 谜题）—— 内容缺口，非逻辑缺陷
- `/api/health` 在数据库不可用时仍返回 UP —— 接监控需另加含数据库连通性的就绪探针
- **输出占成本 62%，其中 55.7% 是玩家看不到的「思考」**（`completion_tokens_details.reasoning_tokens` 实测）→ 折算 **思考 ≈35% / 可见回复 ≈28%**。
  ~~目前动不了~~ **已推翻：思考可以关**（见上面 Token 一节），但**默认不关**，需用户决定。`chat-max-tokens=1200` 而实测 completion 仅 96~215，**不是瓶颈**。另：滑窗满后 cacheMiss 上升的现象是真的，但「滑窗破坏前缀」这个解释已被推翻（4 次运行命中预言只对 2/7），可归因于滑窗的只有约 1 个 128 量化档（见 `topics/ai-npc.md`）

## 安全待办
**提醒用户轮换 DeepSeek API Key**（曾在早期对话以明文出现）。本轮验收全程未让 Key 落盘或回显。
