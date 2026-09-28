# 验收脚本与脚本编写约定

> 从 `MEMORY.md` 拆出。改脚本、加断言、或要跑某一层验收时读这份。

## 脚本一览
| 脚本 | 层次 | 跑法 |
|---|---|---|
| `scripts/verify-api.ps1` | 接口 | `-LogPath .runtime/verify-api.log`（82 条） |
| `scripts/check-source-links.py` | 数据 | `python scripts/check-source-links.py` |
| `scripts/verify-fresh-install.py` | 数据 | `[--self-test]`，装进独立库逐表逐列比对 |
| `frontend/scripts/visual-check.mjs` | 页面 | `cd frontend && node ...`（64 条） |
| `frontend/scripts/source-link-check.mjs` | 页面 | 同上（72 条） |
| `frontend/scripts/puzzle-drag-check.mjs` | 交互 | 同上 |
| `frontend/scripts/npc-chat-ui-check.mjs` | 交互 | 同上（11 条，10 轮同会话 + 刷新复验） |
| `frontend/scripts/e2e-full-loop.mjs` | 全链路 | 同上（29 项） |
| `frontend/scripts/rc-resilience-check.mjs` | 页面 | 宽度/控制台/网络/降级，39 条 |
| `scripts/verify-npc-voice.py` | AI | `NPC_VOICE_NPC_ID=1..7`（**7 个都要跑**） |
| `scripts/verify-live-ai.py` | AI | 对话/推理/结案/多轮/防注入 |
| `scripts/verify-ai-error-mapping.py` | AI | 需先起故障桩 + 后端指向桩 |
| `scripts/verify-reward-bonus.py` | 接口 | `score>=80` 加成分支唯一入口 |
| `scripts/verify-rc-security.py` | 接口 | 鉴权/越权/注入/隐藏数据/编码/404，127 条 |
| `scripts/verify-rc-resilience.py` | 接口 | 并发与重复提交，31 条 |
| `scripts/verify-rc-race-hammer.py` | 接口 | `ROUNDS=10`，竞态压测，不调 AI |
| `scripts/verify-rc-performance.py` | 接口 | 响应时间/规模/分页，34 条 |
| `scripts/check-trx-boundary.sh` | 数据 | 需延迟桩；断言未结束事务**峰值 0** |
| `scripts/measure-coverage.py` | 单测 | 0 部分覆盖才 exit 0 |
| `scripts/deepseek-meter-proxy.mjs` | 计量 | 只测量不改逻辑，见 `ai-npc.md` |
| `scripts/measure-npc-tokens.py` | 计量 | `--label before/after`，固定 8 问 |

前端脚本都用 `playwright-core` 驱动本机 Edge（`BROWSER_PATH` 可覆盖，默认 `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`）；`frontend/scripts/` 不参与 `vue-tsc`、不进打包。报告写 `artifacts/`（**`artifacts/` 不在 .gitignore，报告要提交**）。

## 脚本编写约定（都是踩过的坑）
- **脚本要带自检**：按**编号**核对「声明的检查项都执行过」。按出现次数算会误报（if/else 分支共用编号）。
- **汇总必须在所有 check 之后算**（`e2e-full-loop.mjs` 曾把控制台错误检查放在汇总后 → 有错却退出 0）。
- **断言要能在失败时给出可诊断的信息**：把实际值打进消息里（`实际为：` + 值），否则只看到 FAIL 还得重跑。
- **界面断言只校验不变量，别写死下标**（老账号记录会增长，写死下标必然假失败）。账号可用 `MINDTRACE_USER/PASS` 覆盖。
- **`fullPage` 截图走 `captureBeyondViewport`，不会真滚动** → 截图前先调 `settleReveals()` 真滚一遍，否则大片空白。
- **HTML5 拖放最容易「写了不生效」** → 单独有 `puzzle-drag-check.mjs`。

## 「AI 是否真被调用」的判定
- 看两个信号：`aiAvailable` **和** 请求耗时（缺一不可）。**降级是毫秒级**，真实调用 NPC 3~4s / 推理 ~15s / 结案 ~10s。
- **`deepSeekConfigured:true` 不能证明「没指向桩」**（指向桩时它同样是 true）→ 需三项同时确认：
  1. 桩端口没人听（`netstat`）
  2. 后端进程命令行无 `OVERRIDE_BASE_URL`（PowerShell `Get-CimInstance Win32_Process`）
  3. 一次真实调用成功（桩已死，若还指向桩必然失败）
- 桩 AI 跑出来的 `artifacts/e2e-full-loop.json` **不要提交**（`aiActuallyCalled=true` 会误导）。
- **`curl` 返回 502 不能证明桩已死**（环境代理会插一脚）→ 用裸 socket 连接判断端口死活。

## 密钥泄露检查
必须覆盖全链路：后端日志、前端源码与 `dist`、`git grep` 历史、浏览器 DOM/`localStorage`/内联脚本、**所有 API 响应体**。正则 `sk-[A-Za-z0-9]{20,}`。

## 数据一致性
- **改了 `data.sql` / `migration-*.sql` 就跑 `verify-fresh-install.py`**
  - ⚠️ 安全设计不可省：`data.sql` 带 `TRUNCATE TABLE` 且硬编码 `USE mindtrace;`，**替换没生效就执行 = 清空正在用的库** → 断言正文里一个 `mindtrace` 都不剩
  - **比对必须排除 `created_at`/`updated_at`**（否则每张表都误报成分叉）
  - `--self-test` 往全新库注入一处差异，要求比对必须发现

## 测试数据与公式
- **奖励公式唯一真相**：`exp = score*2 + (score>=80 ? 60 : 0)`，`coins = score + (score>=80 ? 30 : 0)`。
  `verify-api.ps1` 里两条只写了 `score*2`/`score`（当前不误报，用玩满账号会失败）。
- **新用户初始余额 `exp=0, coins=100`**（写「余额 == 各项奖励之和」必须算进去）。
- **CASE-001 打满配方**：5 地点依次 `SEARCH`（lobby→elevator→guest-room→water-system→rooftop，逐级解锁）；
  谜题 `timeline-sort`=`checkin,elevator,lastseen,complaints,bodyfound`、`evidence-chain`=`guest-report,maintenance,rooftop-tank`、
  `elevator-hypothesis`=`unverified`；NPC 关键词 **NPC1「电梯」→ CLUE-004**、**NPC2「网络」→ CLUE-012**；
  结案文本 ≥180 字。满分 100（30+25+20+25）。
- **注册用户名硬限制** `^[A-Za-z0-9_]{3,20}$`（上限 20；脚本里 `前缀+13位毫秒` 必须 ≤20）。
- `existing.status != COMPLETED` 分支**接口层不可达**（`GameRecord` 只由 `GameService` 写）→ 纯防御代码，别造用例。
