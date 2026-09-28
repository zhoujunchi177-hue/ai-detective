param(
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$LogPath = ''
)

$script:lines = New-Object System.Collections.Generic.List[string]
$failures = 0
$script:executed = 0
$script:skipped = 0

function Write-Line([string]$Text) {
    $script:lines.Add($Text)
    Write-Host $Text
}

function Save-Log {
    if ($LogPath) {
        $indexed = for ($i = 0; $i -lt $script:lines.Count; $i++) { "[$($i + 1)] $($script:lines[$i])" }
        Set-Content -Path $LogPath -Value ($indexed -join [Environment]::NewLine) -Encoding UTF8
    }
}

function Assert-True([string]$Name, [bool]$Condition) {
    $script:executed++
    if ($Condition) {
        Write-Line "PASS $Name"
    } else {
        Write-Line "FAIL $Name"
        $script:failures++
    }
}

function Skip-Check([string]$Name) {
    $script:skipped++
    Write-Line "SKIP $Name"
}

# 统一出口，顺带对瞬时网络错误重试三次。
# 注意：这不是「断言凭空消失」的解药 —— 那类问题的真凶是脚本缺少 UTF-8 BOM，
# 让 PowerShell 5.1 按 GBK 解码、行尾汉字吃掉换行符。见文件末尾的自检段落。
function Get-Json([string]$Uri, [hashtable]$Headers) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            return Invoke-RestMethod -Uri $Uri -Headers $Headers
        } catch {
            if ($attempt -eq 3) { throw }
            Start-Sleep -Milliseconds 200
        }
    }
}

try {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" `
        -ContentType 'application/json' `
        -Body '{"username":"demo_investigator","password":"demo123"}'
    $token = $login.data.token
    Assert-True 'login returns a JWT token' ([bool]$token)

    $headers = @{ Authorization = "Bearer $token" }
    $health = Invoke-RestMethod -Uri "$BaseUrl/health"
    Assert-True 'health endpoint responds' ($health.data.status -eq 'UP')

    $cases = Invoke-RestMethod -Uri "$BaseUrl/cases" -Headers $headers
    Assert-True 'authenticated case list returns 3 cases' ($cases.data.Count -eq 3)

    $caseDetail = Invoke-RestMethod -Uri "$BaseUrl/cases/1" -Headers $headers
    Assert-True 'case 1 contains a public timeline' ($caseDetail.data.timeline.Count -gt 0)
    Assert-True 'case 1 retains discovered clues' ($caseDetail.data.discoveredClues.Count -gt 0)

    $chatHistory = Invoke-RestMethod -Uri "$BaseUrl/cases/1/chat?npcId=1" -Headers $headers
    # ⚠️ 原先写的是 `-ge 0` —— 那是**恒真**的，永远不可能失败：
    #    断言名叫「returns persisted messages」，其实一条都没查（本项目 §十二 的同类问题）。
    Assert-True 'chat history endpoint returns persisted messages' ($chatHistory.data.Count -gt 0)
    # 「历史不能被截断」这条不变量**不在这里**验：界面历史接口曾被 ConversationMemory
    # 的 12 条上限截断，玩家刷新后前几轮对话凭空消失。但这条断言依赖「库里有超过 12 条消息」，
    # 而 data.sql 会 TRUNCATE chat_messages（全新安装是 0 条）—— 写死 `-gt 12` 必然假失败。
    # 所以交给能自己播种的 frontend/scripts/npc-chat-ui-check.mjs（发满 10 轮再刷新），
    # 那条已被证明能抓到这个 BUG（修复前 FAIL、修复后 PASS）。

    $progress = Invoke-RestMethod -Uri "$BaseUrl/cases/1/progress" -Headers $headers
    Assert-True 'case 1 progress reports completion' ($progress.data.completed -eq $true)

    # 日志接口返回的是分页对象（entries/page/size/total/totalPages/hasMore/truncated/typeCounts），
    # 不再是裸数组 —— 断言必须跟着改，否则 $history.data.Count 对 PSCustomObject 恒为 $null，
    # 下面那条泄漏检查会因为拿不到数组而「零条命中」，看起来通过、其实什么都没查。
    $history = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history" -Headers $headers
    Assert-True 'case 1 history returns investigation records' ($history.data.total -gt 0)
    Assert-True 'history page exposes entries and paging metadata' (
        $history.data.entries.Count -gt 0 -and
        $history.data.page -eq 1 -and
        $history.data.totalPages -ge 1 -and
        $history.data.size -gt 0)
    $leaked = @($history.data.entries | Where-Object { $_.resultText -like '*"summary"*' })
    Assert-True 'history does not leak raw AI JSON to the client' ($leaked.Count -eq 0)

    # 类型筛选：结果里不能混进别的类型
    $searchOnly = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?type=SEARCH" -Headers $headers
    $wrongType = @($searchOnly.data.entries | Where-Object { $_.actionType -ne 'SEARCH' })
    Assert-True 'history type filter returns only the requested type' ($wrongType.Count -eq 0)
    Assert-True 'history type filter still reports a total' ($searchOnly.data.total -gt 0)

    # type=ALL 必须和不传 type 等价。SQL 下推若把字面量 ALL 拼进 WHERE，这里会变成 0 条。
    $allLiteral = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?type=ALL" -Headers $headers
    Assert-True 'history type=ALL is equivalent to no type filter' (
        $allLiteral.data.total -eq $history.data.total)

    # 分页：第 1 页取 1 条，必须正好 1 条，且 total 仍是全量
    $onePerPage = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?page=1&size=1" -Headers $headers
    Assert-True 'history size=1 returns exactly one entry' ($onePerPage.data.entries.Count -eq 1)
    Assert-True 'history paging keeps the full total' ($onePerPage.data.total -eq $history.data.total)
    Assert-True 'history hasMore is true when more pages exist' (
        $onePerPage.data.hasMore -eq ($onePerPage.data.total -gt 1))

    # 越界页返回空列表而不是报错，否则玩家翻过头会看到一个错误弹窗
    $beyond = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?page=9999&size=20" -Headers $headers
    Assert-True 'history beyond the last page returns an empty list, not an error' (
        $beyond.data.entries.Count -eq 0 -and $beyond.data.total -eq $history.data.total)

    # 类型计数在类型筛选之前统计：ALL 计数应等于全量，且各类型计数之和不超过 ALL
    Assert-True 'history typeCounts.ALL equals the unfiltered total' (
        $history.data.typeCounts.ALL -eq $history.data.total)
    $sumOfTypes = ($history.data.typeCounts.PSObject.Properties |
        Where-Object { $_.Name -ne 'ALL' } |
        Measure-Object -Property Value -Sum).Sum
    Assert-True 'sum of per-type counts does not exceed typeCounts.ALL' (
        $sumOfTypes -le $history.data.typeCounts.ALL)

    # 日期范围：先用覆盖一切的宽区间，确认参数不会误杀已有记录。
    # 这里不用「今天~今天」—— 库里记录的产生时间不确定，那样写会因为筛不到而假失败。
    # 「to 含当天整天」这个边界由后端单测 rangeEndCoversTheWholeDay 用 LocalTime.MAX 钉住。
    $wideRange = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?from=2000-01-01&to=2099-12-31" -Headers $headers
    Assert-True 'history wide date range keeps every record' ($wideRange.data.total -eq $history.data.total)

    # 完全落在未来的区间必须为空。若后端把 from/to 当摆设（例如漏了下推），
    # 这里会返回全量，断言立刻失败 —— 这才能真正证明日期参数是生效的。
    $futureRange = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?from=2099-01-01&to=2099-12-31" -Headers $headers
    Assert-True 'history future-only date range returns nothing' ($futureRange.data.total -eq 0)

    # 关键词在**展示文本**上过滤：搜一个只可能出现在原始 AI JSON 里的词，必须搜不到。
    # 但要先确认库里真有 REASONING 记录，否则「搜不到」只是因为没数据 —— 又是一次静默通过。
    $reasoning = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?type=REASONING" -Headers $headers
    Assert-True 'there is at least one REASONING record to check against' ($reasoning.data.total -gt 0)
    $rawJsonVisible = @($reasoning.data.entries | Where-Object { $_.resultText -like '*"summary"*' })
    Assert-True 'reasoning display text hides the raw AI JSON' ($rawJsonVisible.Count -eq 0)

    $jsonOnly = Invoke-RestMethod -Uri "$BaseUrl/cases/1/history?keyword=summary" -Headers $headers
    Assert-True 'history keyword matches display text, not the raw stored JSON' (
        $jsonOnly.data.total -eq 0)

    $ranking = Invoke-RestMethod -Uri "$BaseUrl/ranking?type=score" -Headers $headers
    Assert-True 'ranking endpoint returns entries' ($ranking.data.entries.Count -gt 0)

    $profile = Invoke-RestMethod -Uri "$BaseUrl/user/profile" -Headers $headers
    $achievements = @($profile.data.achievements)
    Assert-True 'profile returns achievement definitions' ($achievements.Count -gt 0)
    $missingReward = @($achievements | Where-Object { $null -eq $_.rewardExp -or $null -eq $_.rewardCoins })
    Assert-True 'every achievement exposes rewardExp and rewardCoins' ($missingReward.Count -eq 0)

    # 稀有度：只能是四档之一，且每一档都要真的有徽章 ——
    # 定义了 LEGENDARY 却没有任何徽章属于它，说明分级是拍的、不是按内容算的。
    $allowedRarity = @('COMMON', 'RARE', 'EPIC', 'LEGENDARY')
    $badRarity = @($achievements | Where-Object { $allowedRarity -notcontains $_.rarity })
    Assert-True 'every achievement rarity is one of COMMON/RARE/EPIC/LEGENDARY' ($badRarity.Count -eq 0)
    $usedRarity = @($achievements | Select-Object -ExpandProperty rarity -Unique)
    Assert-True 'all four rarity tiers are actually used by some achievement' ($usedRarity.Count -eq 4)

    # ------------------------------------------------------------------
    # 调查地图节点状态（由后端 Java 计算，前端不得推断）
    # 用一次性新账号验证完整解锁链：location:lobby -> water-system -> CLUE-006 -> rooftop
    # ------------------------------------------------------------------
    $allowedStatus = @('LOCKED', 'AVAILABLE', 'INVESTIGATED', 'COMPLETED')

    $demoNodes = @((Invoke-RestMethod -Uri "$BaseUrl/cases/1/locations" -Headers $headers).data)
    $unknownStatus = @($demoNodes | Where-Object { $allowedStatus -notcontains $_.status })
    Assert-True 'every map node exposes a known backend status' ($unknownStatus.Count -eq 0)
    $badCounts = @($demoNodes | Where-Object {
            $null -eq $_.clueCount -or $null -eq $_.foundClueCount -or $_.foundClueCount -gt $_.clueCount
        })
    Assert-True 'every map node reports consistent clue counts' ($badCounts.Count -eq 0)
    $demoLobby = $demoNodes | Where-Object { $_.locationKey -eq 'lobby' }
    Assert-True 'a completed case shows its first node as COMPLETED' ($demoLobby.status -eq 'COMPLETED')

    $freshName = 'nodechk' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $freshPass = 'nodecheck123'
    $regBody = "{`"username`":`"$freshName`",`"nickname`":`"节点校验`",`"password`":`"$freshPass`"}"
    $reg = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/register" -ContentType 'application/json' -Body $regBody
    $freshHeaders = @{ Authorization = "Bearer $($reg.data.token)" }

    $freshDetail = Invoke-RestMethod -Uri "$BaseUrl/cases/1" -Headers $freshHeaders
    $freshNodes = @($freshDetail.data.locations)
    Assert-True 'a fresh account starts with no discovered clues' (@($freshDetail.data.discoveredClues).Count -eq 0)
    Assert-True 'a fresh account can start case 1 (at least 2 open nodes)' (@($freshNodes | Where-Object { $_.status -eq 'AVAILABLE' }).Count -ge 2)

    $freshRooftop = $freshNodes | Where-Object { $_.locationKey -eq 'rooftop' }
    Assert-True 'a clue-gated node starts LOCKED' ($freshRooftop.status -eq 'LOCKED')
    Assert-True 'a LOCKED node names its unmet prerequisite' ($freshRooftop.lockedReason -like '*CLUE-006*')

    # 业务错误以 HTTP 400 + {"success":false,...} 返回。
    # 这里只断言「被拒绝」这个事实：错误响应体的读取在 PowerShell 5.1 上不稳定，
    # 解锁原因已由上一条断言通过 lockedReason 字段验证。
    $lockedRejected = $false
    try {
        $lockedAttempt = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/investigate" -Headers $freshHeaders -ContentType 'application/json' -Body '{"locationKey":"rooftop","action":"SEARCH"}'
        $lockedRejected = ($lockedAttempt.success -eq $false)
    } catch {
        $lockedRejected = $true
    }
    Assert-True 'backend rejects investigating a LOCKED node' $lockedRejected

    $lobbyResult = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/investigate" -Headers $freshHeaders -ContentType 'application/json' -Body '{"locationKey":"lobby","action":"SEARCH"}'
    Assert-True 'investigating an open node returns its recomputed status' ($allowedStatus -contains $lobbyResult.data.location.status -and $lobbyResult.data.location.status -ne 'LOCKED')

    $afterLobby = @((Invoke-RestMethod -Uri "$BaseUrl/cases/1/locations" -Headers $freshHeaders).data)
    $waterSystem = $afterLobby | Where-Object { $_.locationKey -eq 'water-system' }
    $rooftopAfterLobby = $afterLobby | Where-Object { $_.locationKey -eq 'rooftop' }
    Assert-True 'investigating a node unlocks the node gated by location:<key>' ($waterSystem.status -eq 'AVAILABLE')
    Assert-True 'a node gated by another clue stays LOCKED' ($rooftopAfterLobby.status -eq 'LOCKED')

    Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/investigate" -Headers $freshHeaders -ContentType 'application/json' -Body '{"locationKey":"water-system","action":"SEARCH"}' | Out-Null
    $afterWater = @((Invoke-RestMethod -Uri "$BaseUrl/cases/1/locations" -Headers $freshHeaders).data)
    $rooftopAfterWater = $afterWater | Where-Object { $_.locationKey -eq 'rooftop' }
    Assert-True 'a node gated by clue:<code> unlocks once that clue is found' ($rooftopAfterWater.status -eq 'AVAILABLE')

    $reloginBody = "{`"username`":`"$freshName`",`"password`":`"$freshPass`"}"
    $relogin = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType 'application/json' -Body $reloginBody
    $reloginHeaders = @{ Authorization = "Bearer $($relogin.data.token)" }
    $persistedNodes = @((Invoke-RestMethod -Uri "$BaseUrl/cases/1/locations" -Headers $reloginHeaders).data)
    $persistedLobby = $persistedNodes | Where-Object { $_.locationKey -eq 'lobby' }
    Assert-True 'node state is persisted per user in MySQL (survives re-login)' (@('INVESTIGATED', 'COMPLETED') -contains $persistedLobby.status)

    Write-Line "INFO node-check account: $freshName / $freshPass"

    # ------------------------------------------------------------------
    # 证据板：连线是玩家自己的推理产物，后端只校验归属，不判断关联是否成立
    # ------------------------------------------------------------------
    $demoBoard = Invoke-RestMethod -Uri "$BaseUrl/cases/1/evidence-links" -Headers $headers
    $relationTypes = @($demoBoard.data.relationTypes)
    Assert-True 'evidence board exposes relation type options' ($relationTypes.Count -gt 0)
    $nodesWithoutCode = @($demoBoard.data.nodes | Where-Object { -not $_.clueCode })
    Assert-True 'every evidence node carries a clue code' ($nodesWithoutCode.Count -eq 0)

    $board = Invoke-RestMethod -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders
    $boardNodes = @($board.data.nodes)
    Assert-True 'a fresh account board only contains its own discovered clues' ($boardNodes.Count -ge 2)
    Assert-True 'a fresh account starts with an empty evidence board' (@($board.data.links).Count -eq 0)

    $linkBody = "{`"fromClueId`":$($boardNodes[0].clueId),`"toClueId`":$($boardNodes[1].clueId),`"relationType`":`"TIMELINE`",`"note`":`"接口断言创建`"}"
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders -ContentType 'application/json' -Body $linkBody
    Assert-True 'creating an evidence link returns the resolved relation label' ([bool]$created.data.relationLabel -and $created.data.relationType -eq 'TIMELINE')
    Assert-True 'the created link carries both endpoint clue summaries' ([bool]$created.data.from.clueCode -and [bool]$created.data.to.clueCode)

    # 反向再连一次必须被拒（同一对线索不分方向）
    $reverseBody = "{`"fromClueId`":$($boardNodes[1].clueId),`"toClueId`":$($boardNodes[0].clueId)}"
    $duplicateRejected = $false
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders -ContentType 'application/json' -Body $reverseBody
        $duplicateRejected = ($r.success -eq $false)
    } catch {
        $duplicateRejected = $true
    }
    Assert-True 'the reverse of an existing link is rejected as a duplicate' $duplicateRejected

    # 自连必须被拒
    $selfBody = "{`"fromClueId`":$($boardNodes[0].clueId),`"toClueId`":$($boardNodes[0].clueId)}"
    $selfRejected = $false
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders -ContentType 'application/json' -Body $selfBody
        $selfRejected = ($r.success -eq $false)
    } catch {
        $selfRejected = $true
    }
    Assert-True 'linking a clue to itself is rejected' $selfRejected

    # 不能关联自己还没发现的线索
    $freshClueIds = @($boardNodes | ForEach-Object { $_.clueId })
    $demoDetail = Invoke-RestMethod -Uri "$BaseUrl/cases/1" -Headers $headers
    $demoClueIds = @($demoDetail.data.discoveredClues | ForEach-Object { $_.id })
    $notDiscovered = @($demoClueIds | Where-Object { $freshClueIds -notcontains $_ }) | Select-Object -First 1
    if ($notDiscovered) {
        $foreignBody = "{`"fromClueId`":$($boardNodes[0].clueId),`"toClueId`":$notDiscovered}"
        $foreignRejected = $false
        try {
            $r = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders -ContentType 'application/json' -Body $foreignBody
            $foreignRejected = ($r.success -eq $false)
        } catch {
            $foreignRejected = $true
        }
        Assert-True 'linking an undiscovered clue is rejected' $foreignRejected
    } else {
        Skip-Check 'undiscovered-clue check (both accounts discovered the same clues)'
    }

    # 删除别人的连线必须失败，而不是「删除成功」
    $foreignDeleteRejected = $false
    try {
        Invoke-RestMethod -Method Delete -Uri "$BaseUrl/cases/1/evidence-links/$($created.data.id)" -Headers $headers | Out-Null
    } catch {
        $foreignDeleteRejected = $true
    }
    Assert-True "deleting another user's link is rejected" $foreignDeleteRejected

    Invoke-RestMethod -Method Delete -Uri "$BaseUrl/cases/1/evidence-links/$($created.data.id)" -Headers $freshHeaders | Out-Null
    $afterDelete = Invoke-RestMethod -Uri "$BaseUrl/cases/1/evidence-links" -Headers $freshHeaders
    Assert-True 'deleting your own link removes it from the board' (@($afterDelete.data.links).Count -eq 0)

    # ------------------------------------------------------------------
    # 存档进度：列表页与详情页必须给出同一个百分比，且未开始的案件要能区分出来
    # ------------------------------------------------------------------
    $demoList = Get-Json "$BaseUrl/cases" $headers
    $caseOne = @($demoList.data | Where-Object { $_.id -eq 1 })[0]
    Assert-True 'case list carries per-user progress' ($null -ne $caseOne.playerProgress)
    Assert-True 'a finished case is flagged completed in the list' ($caseOne.playerProgress.completed -eq $true -and $caseOne.playerProgress.started -eq $true)

    $caseOneDetail = Invoke-RestMethod -Uri "$BaseUrl/cases/1" -Headers $headers
    Assert-True 'list progress percent equals the detail overall percent' ($caseOne.playerProgress.percent -eq $caseOneDetail.data.progress.overallPercent)
    Assert-True 'list progress breakdown matches the detail progress' ($caseOne.playerProgress.discoveredClues -eq $caseOneDetail.data.progress.discoveredClues -and $caseOne.playerProgress.investigatedLocations -eq $caseOneDetail.data.progress.investigatedLocations -and $caseOne.playerProgress.solvedPuzzles -eq $caseOneDetail.data.progress.solvedPuzzles)

    $outOfRange = @($demoList.data | Where-Object { $_.playerProgress.percent -lt 0 -or $_.playerProgress.percent -gt 100 })
    Assert-True 'every progress percent stays within 0..100' ($outOfRange.Count -eq 0)

    # 这个账号只调查过 CASE-001，所以 CASE-002/003 应该是「未开始」而不是「开始了但 0%」
    $freshList = Get-Json "$BaseUrl/cases" $freshHeaders
    $freshCases = @($freshList.data)
    $freshCaseOne = @($freshCases | Where-Object { $_.id -eq 1 })[0]
    $freshCaseTwo = @($freshCases | Where-Object { $_.id -eq 2 })[0]

    Assert-True 'case list returns one entry per case' ($freshCases.Count -eq 3)
    Assert-True 'a partially played case reports started but not completed' ($freshCaseOne.playerProgress.started -eq $true -and $freshCaseOne.playerProgress.completed -eq $false)
    Assert-True 'a partially played case has a percent between 0 and 100' ($freshCaseOne.playerProgress.percent -gt 0 -and $freshCaseOne.playerProgress.percent -lt 100)

    # 未开始的案件仍然返回进度对象，但 started 必须是 false (界面靠这个字段区分「没开始」)
    # 条件先算成变量，避免超长表达式在参数绑定处出意外。
    $untouchedIsClean = ($freshCaseTwo.playerProgress.started -eq $false) -and
                        ($freshCaseTwo.playerProgress.discoveredClues -eq 0) -and
                        ($freshCaseTwo.playerProgress.percent -eq 0)
    Assert-True 'an untouched case reports started false with zero counts' $untouchedIsClean
    Assert-True 'an untouched case still reports its content totals' ($freshCaseTwo.playerProgress.totalClues -gt 0 -and $freshCaseTwo.playerProgress.totalLocations -gt 0)

    $freshCaseOneDetail = Get-Json "$BaseUrl/cases/1" $freshHeaders
    Assert-True 'fresh account list percent also matches its detail percent' ($freshCaseOne.playerProgress.percent -eq $freshCaseOneDetail.data.progress.overallPercent)

    # ------------------------------------------------------------------
    # 结案提交：首次结案发奖励，**重复提交不再发**。
    #
    # 这段覆盖 GameService.submit 里可达的三条分支。此前只有 E2E 提交过一次结案，
    # 「已结案后再提交」的两条分支（补差额 / 完全不发）从未被验证过 ——
    # 而它们正是「重复提交刷 EXP 金币」这类经济系统漏洞的入口。
    #
    # 注：GameRecord 只由 GameService 写入，且 insert 时一律落 COMPLETED，
    # 所以 existing.status != COMPLETED 那条分支在接口层不可达（纯防御代码）。
    # ------------------------------------------------------------------
    # 注意：用户名正则是 ^[A-Za-z0-9_]{3,20}$，上限 20 字符。
    # 前缀 + 13 位毫秒必须 <= 20，否则注册直接 400（这里曾用 submitchk 踩过）。
    $submitName = 'subchk' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $submitRegBody = "{`"username`":`"$submitName`",`"nickname`":`"结案校验`",`"password`":`"submitcheck123`"}"
    $submitReg = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/register" -ContentType 'application/json' -Body $submitRegBody
    $submitHeaders = @{ Authorization = "Bearer $($submitReg.data.token)" }

    $richHypothesis = '作案者利用顶楼水箱的供水异常制造不在场证明，实际动手时间远早于报警时间，值班表上缺失的那十五分钟是关键。'
    $richTimeline = '20:10 住客投诉供水异常；20:40 屋顶水箱发现遗体；21:00 酒店封锁现场并清点住客名单。'
    # 续行陷阱：Windows PowerShell 5.1 只认「运算符在行尾」的续行。
    #   ('a' +  ⏎  'b')  合法
    #   ('a'  ⏎  + 'b')  语法错误（LF / CRLF 都一样，已实测）
    # 所以下面每个 '+' 都必须留在行末，别挪到下一行开头。
    $richReasoning = ('先核对住客投诉记录与水箱检修单之间的时间差，再比对值班表上缺失的那十五分钟；' +
        '供水异常的时段恰好覆盖了遗体被发现之前的窗口，说明有人刻意让水泵空转以掩盖声响与出入痕迹。' +
        '结合大堂监控里始终没有出现的那个身影，可以排除外部人员临时起意的可能，' +
        '嫌疑因此收拢到熟悉设备维护流程的内部人员身上。')
    $richConclusion = '结论：作案者是熟悉酒店供水系统的内部人员，利用水箱检修窗口实施并伪装成设备意外。'

    $submitBody = @{
        hypothesis      = $richHypothesis
        keyPeople       = '值班工程师、前台主管'
        keyTimeline     = $richTimeline
        evidenceClueIds = @()
        reasoningText   = $richReasoning
        conclusion      = $richConclusion
    } | ConvertTo-Json -Compress

    $firstSubmit = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/submit" -Headers $submitHeaders -ContentType 'application/json' -Body $submitBody
    $firstScore = [int]$firstSubmit.data.totalScore
    Assert-True 'first completion produces a non-zero score (reward checks are not vacuous)' ($firstScore -gt 0)
    Assert-True 'first completion grants EXP equal to twice the score' ([int]$firstSubmit.data.expReward -eq $firstScore * 2)
    Assert-True 'first completion grants coins equal to the score' ([int]$firstSubmit.data.coinReward -eq $firstScore)

    $afterFirst = Invoke-RestMethod -Uri "$BaseUrl/user/profile" -Headers $submitHeaders
    Assert-True 'first completion counts the case exactly once' ($afterFirst.data.completedCases -eq 1)
    $expAfterFirst = [int]$afterFirst.data.exp
    $coinsAfterFirst = [int]$afterFirst.data.coins

    # 同一份内容再提交：分数不变，所以既不发奖励，也不动任何统计。
    $sameSubmit = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/submit" -Headers $submitHeaders -ContentType 'application/json' -Body $submitBody
    Assert-True 'resubmitting identical content grants no EXP' ([int]$sameSubmit.data.expReward -eq 0)
    Assert-True 'resubmitting identical content grants no coins' ([int]$sameSubmit.data.coinReward -eq 0)
    Assert-True 'resubmitting identical content keeps the same total score' ([int]$sameSubmit.data.totalScore -eq $firstScore)

    $afterSame = Invoke-RestMethod -Uri "$BaseUrl/user/profile" -Headers $submitHeaders
    Assert-True 'resubmit leaves EXP and coins untouched' ([int]$afterSame.data.exp -eq $expAfterFirst -and [int]$afterSame.data.coins -eq $coinsAfterFirst)
    Assert-True 'resubmit does not count the case a second time' ($afterSame.data.completedCases -eq 1)

    # 提高进度后再提交：分数确实变高，但**仍然不发奖励**，只把 totalScore 补到新分数。
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/investigate" -Headers $submitHeaders -ContentType 'application/json' -Body '{"locationKey":"lobby","action":"SEARCH"}' | Out-Null
    $higherSubmit = Invoke-RestMethod -Method Post -Uri "$BaseUrl/cases/1/submit" -Headers $submitHeaders -ContentType 'application/json' -Body $submitBody
    $higherScore = [int]$higherSubmit.data.totalScore
    Assert-True 'a better resubmission really scores higher (the branch is exercised)' ($higherScore -gt $firstScore)
    Assert-True 'a better resubmission still grants no EXP' ([int]$higherSubmit.data.expReward -eq 0)
    Assert-True 'a better resubmission still grants no coins' ([int]$higherSubmit.data.coinReward -eq 0)

    $afterHigher = Invoke-RestMethod -Uri "$BaseUrl/user/profile" -Headers $submitHeaders
    Assert-True 'a better resubmission raises totalScore to exactly the new score' ([int]$afterHigher.data.totalScore -eq $higherScore)
    Assert-True 'a better resubmission does not count the case again' ($afterHigher.data.completedCases -eq 1)

    $submitDetail = Invoke-RestMethod -Uri "$BaseUrl/cases/1" -Headers $submitHeaders
    Assert-True 'the case reports completed after submission' ($submitDetail.data.progress.completed -eq $true)

    # 存档里保存的必须是「历史最高分」，而不是最后一次提交的分数。
    $submitHistory = @($afterHigher.data.history | Where-Object { $_.caseId -eq 1 })
    Assert-True 'the profile history holds exactly one record for the case' ($submitHistory.Count -eq 1)
    Assert-True 'the stored record keeps the best score' ([int]$submitHistory[0].totalScore -eq $higherScore)
} catch {
    Write-Line "FAIL verification stopped: $($_.Exception.Message)"
    $script:failures++
}

# ------------------------------------------------------------------
# 自检：源码里声明了多少条断言，就必须记录多少条。
#
# 这个脚本曾经因为文件缺少 UTF-8 BOM，被 Windows PowerShell 5.1 按 GBK 解码，
# 行尾的汉字把换行符一起吃掉，导致紧跟其后的那行 Assert-True 被并进注释、静默消失。
# 日志看起来「全部通过」，实际少跑了一条 —— 正是最危险的那种假成功。
# 现在把「声明的断言数」和「实际执行的断言数」对上，这类问题再也藏不住。
# ------------------------------------------------------------------
$declaredChecks = ([regex]::Matches((Get-Content -Path $PSCommandPath -Raw), "Assert-True\s+['`"]")).Count
$accountedFor = $script:executed + $script:skipped
Write-Line "SUMMARY declared=$declaredChecks executed=$($script:executed) skipped=$($script:skipped) failed=$failures"
Assert-True 'every declared check actually ran (no silently swallowed line)' ($accountedFor -eq ($declaredChecks - 1))

if ($failures -gt 0) {
    Write-Line "$failures check(s) failed"
    Save-Log
    exit 1
}

Write-Line 'All API checks passed.'
Save-Log
