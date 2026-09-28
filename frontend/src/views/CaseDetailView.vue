<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowLeft,
  BookOpenText,
  BrainCircuit,
  CalendarRange,
  CheckCircle2,
  ChevronRight,
  Clock3,
  Download,
  ExternalLink,
  FileText,
  FolderOpen,
  GitCompare,
  Landmark,
  Lightbulb,
  LoaderCircle,
  MapPinned,
  Puzzle as PuzzleIcon,
  RefreshCw,
  ScrollText,
  Search,
  ShieldQuestion,
  Users,
  X,
} from 'lucide-vue-next'
import dayjs from 'dayjs'
import { caseApi } from '@/api'
import ClueCard from '@/components/ClueCard.vue'
import EvidenceBoard from '@/components/EvidenceBoard.vue'
import InvestigationTerminal from '@/components/InvestigationTerminal.vue'
import MaterialTag from '@/components/MaterialTag.vue'
import PuzzlePanel from '@/components/PuzzlePanel.vue'
import ReasoningPanel from '@/components/ReasoningPanel.vue'
import SubmitCaseDialog from '@/components/SubmitCaseDialog.vue'
import TimelinePanel from '@/components/TimelinePanel.vue'
import { useAuthStore } from '@/stores/auth'
import { useAudioStore } from '@/stores/audio'
import type {
  CaseDetail,
  ChatMessage,
  Clue,
  HistoryEntry,
  LocationStatus,
  LocationView,
  Puzzle,
  ReasoningResult,
  SubmitResult,
} from '@/types'

type TabKey = 'timeline' | 'locations' | 'people' | 'clues' | 'evidence' | 'files' | 'puzzles' | 'log'

const route = useRoute()
const auth = useAuthStore()
const audio = useAudioStore()
const caseId = Number(route.params.id)
const detail = ref<CaseDetail | null>(null)
const loading = ref(true)
const activeTab = ref<TabKey>('timeline')
const activeLocationKey = ref('')
const investigating = ref(false)
const investigationNarrative = ref('')
const activeNpcId = ref<number>()
const chatLoading = ref(false)
const aiNotice = ref('')
const chatMemory = reactive<Record<number, ChatMessage[]>>({})
const reasoning = ref<ReasoningResult | null>(null)
const reasoningLoading = ref(false)
const submitVisible = ref(false)
const submitLoading = ref(false)
const submitResult = ref<SubmitResult | null>(null)
const historyEntries = ref<HistoryEntry[]>([])
const historyLoading = ref(false)
const historyFilter = ref<'ALL' | 'SEARCH' | 'REASONING' | 'PUZZLE'>('ALL')
const historyKeyword = ref('')
/**
 * 日期范围（yyyy-MM-dd），两端各存一个 ref。
 * 刻意不合并成一个 `[from, to] | null`：那样用 computed 桥接原生 date 输入框时，
 * 「只填了一端」的中间状态没有地方存放，setter 只能选择不更新 ——
 * 而 v-model 会立刻用旧值把输入框回滚，用户看到的是「日期填进去就自己消失了」。
 * 分开存则任意中间状态都能如实保留；`from > to` 由下面的 watch 拦截。
 * to 含当天整天，边界处理在服务端（取 LocalTime.MAX）。
 */
const historyFrom = ref('')
const historyTo = ref('')
const historyPage = ref(1)
/**
 * 每页条数固定 20。刻意不做「每页 N 条」的选择器：日志是浏览型内容，
 * 玩家关心的是「最近发生了什么」，而不是调参；多一个控件只会让筛选区更挤。
 * 上限 100 由服务端强制（见 CaseQueryService.HISTORY_MAX_SIZE）。
 */
const HISTORY_PAGE_SIZE = 20
const historyTotal = ref(0)
const historyTotalPages = ref(0)
const historyHasMore = ref(false)
/** 服务端扫描触到上限时为 true，此时 total 是下界 —— 必须如实告诉玩家。 */
const historyTruncated = ref(false)
const historyTypeCounts = ref<Record<string, number>>({ ALL: 0 })

/** 日志类型筛选标签。key 与 investigation_records.action_type 对应。 */
const historyTypeFilters = [
  { key: 'ALL' as const, label: '全部', icon: ScrollText },
  { key: 'SEARCH' as const, label: '调查地点', icon: MapPinned },
  { key: 'REASONING' as const, label: '推理分析', icon: BrainCircuit },
  { key: 'PUZZLE' as const, label: '谜题校验', icon: PuzzleIcon },
]

/**
 * 类型计数来自服务端，统计口径是「日期 + 关键词」筛选之后、**类型筛选之前**。
 * 以前在前端按当前列表算，分页之后就会变成「当前页各类型有几条」，
 * 而且点进某个类型后其他标签的计数会全部变成 0。
 */
function historyCountFor(key: 'ALL' | 'SEARCH' | 'REASONING' | 'PUZZLE') {
  return historyTypeCounts.value[key] ?? 0
}

/** 只要填了任意一端就算「筛选中」。后端支持单边范围：只填起始 = 「这天之后」。 */
const historyFilterActive = computed(
  () =>
    historyFilter.value !== 'ALL' ||
    Boolean(historyKeyword.value.trim()) ||
    Boolean(historyFrom.value || historyTo.value),
)

const tabs = [
  { key: 'timeline' as const, label: '时间线', icon: Clock3 },
  { key: 'locations' as const, label: '地点', icon: MapPinned },
  { key: 'people' as const, label: '人物', icon: Users },
  { key: 'clues' as const, label: '线索', icon: Lightbulb },
  { key: 'evidence' as const, label: '证据链', icon: GitCompare },
  { key: 'files' as const, label: '文件', icon: FileText },
  { key: 'puzzles' as const, label: '谜题', icon: PuzzleIcon },
  { key: 'log' as const, label: '日志', icon: ScrollText },
]

const activeMessages = computed(() => (activeNpcId.value ? chatMemory[activeNpcId.value] || [] : []))
const activeLocation = computed(() =>
  detail.value?.locations.find((item) => item.locationKey === activeLocationKey.value),
)

/**
 * 节点状态文案与配色。状态来自后端，前端只负责翻译成玩家能读的文字。
 */
const locationStatusLabel: Record<LocationStatus, string> = {
  LOCKED: '未解锁',
  AVAILABLE: '可调查',
  INVESTIGATED: '已调查',
  COMPLETED: '已完成',
}

function locationStatusText(status: LocationStatus) {
  return locationStatusLabel[status] || status
}

/**
 * 来源可信度徽章的配色。
 * 这是「资料本身可不可靠」，不是 REAL/ADAPTED/FICTIONAL 那种内容分级，
 * 所以走 tag--ok / tag--warn / tag--danger 这组状态类名，别借用分级类名。
 */
function reliabilityClass(reliability: string) {
  const normalized = (reliability || '').toUpperCase()
  if (normalized === 'HIGH') return 'tag--ok'
  if (normalized === 'MEDIUM') return 'tag--warn'
  if (normalized === 'LOW') return 'tag--danger'
  return ''
}

/** 点击地图节点：未解锁的节点不进入，只提示解锁条件。 */
function selectLocation(location: LocationView) {
  if (location.status === 'LOCKED') {
    ElMessage.warning(location.lockedReason || '该地点尚未解锁')
    return
  }
  activeLocationKey.value = location.locationKey
  investigationNarrative.value = ''
}

/**
 * 调查写入后重新拉取节点状态。
 * 一次调查可能解锁后续地点，状态必须以后端为准，不能在前端推算。
 */
async function refreshNodeStates() {
  if (!detail.value) return
  try {
    const fresh = await caseApi.detail(caseId)
    if (!detail.value) return
    detail.value.locations = fresh.locations
    detail.value.progress = fresh.progress
  } catch {
    // 静默失败：状态刷新不应打断玩家已经完成的调查流程。
  }
}
/**
 * 综合完成度由后端加权计算（调查 30% + 线索 30% + 谜题 40%）。
 * 前端不再自己算——否则列表页和详情页会各显示一个不一样的百分比。
 */
const overallProgress = computed(() => detail.value?.progress.overallPercent ?? 0)

onMounted(async () => {
  await loadCase()
  audio.setTrack('investigation')
})

watch(activeNpcId, async (id) => {
  if (!id || !detail.value) return
  const npc = detail.value.npcs.find((item) => item.id === id)
  if (!chatMemory[id] && npc) {
    chatLoading.value = true
    try {
      const history = await caseApi.chatHistory(caseId, id)
      chatMemory[id] = history.length ? history : [{ role: 'assistant', content: npc.greeting }]
    } catch (error) {
      aiNotice.value = (error as Error).message
    } finally {
      chatLoading.value = false
    }
  }
})

async function loadCase() {
  loading.value = true
  try {
    detail.value = await caseApi.detail(caseId)
    // 默认选中第一个可进入的节点，避免开局停在未解锁地点上。
    activeLocationKey.value ||=
      detail.value.locations.find((item) => item.status !== 'LOCKED')?.locationKey || ''
    activeNpcId.value ||= detail.value.npcs[0]?.id
    await loadHistory()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    loading.value = false
  }
}

/**
 * 拉取玩家在本案的调查日志（时间倒序）。
 * 类型 / 关键词 / 日期范围筛选与分页**全部在服务端做**：
 * 只筛当前页会让人以为「就这么多」，而关键词又必须匹配展示文本
 * （推理记录的原文带着审计用的原始 AI JSON，按原文搜会搜出看不见的结果）。
 */
async function loadHistory(resetPage = false) {
  if (resetPage) historyPage.value = 1
  historyLoading.value = true
  try {
    const page = await caseApi.history(caseId, {
      type: historyFilter.value === 'ALL' ? undefined : historyFilter.value,
      keyword: historyKeyword.value.trim() || undefined,
      from: historyFrom.value || undefined,
      to: historyTo.value || undefined,
      page: historyPage.value,
      size: HISTORY_PAGE_SIZE,
    })
    historyEntries.value = page.entries
    historyTotal.value = page.total
    historyTotalPages.value = page.totalPages
    historyHasMore.value = page.hasMore
    historyTruncated.value = page.truncated
    historyTypeCounts.value = page.typeCounts || { ALL: 0 }
    // 筛选条件变化后当前页码可能越界（比如从第 3 页筛到只剩 1 页），
    // 回退到最后一页重取一次，避免玩家停在一片空白上。
    if (page.total > 0 && historyPage.value > page.totalPages) {
      historyPage.value = Math.max(1, page.totalPages)
      await loadHistory()
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    historyLoading.value = false
  }
}

/** 翻页。 */
function changeHistoryPage(next: number) {
  if (next === historyPage.value) return
  historyPage.value = next
  void loadHistory()
}

/**
 * 关键词输入做防抖：每次按键都打一次接口既浪费也容易乱序。
 * 所有会改变筛选条件的操作都走这一个入口，它会取消上一个待执行的定时器 ——
 * 否则「改类型」和「清空关键词」会各触发一次请求。
 * 定时器在组件卸载时清掉，避免离开页面后还触发请求。
 */
let queryTimer: ReturnType<typeof setTimeout> | undefined

function scheduleHistoryReload(delay = 0) {
  if (queryTimer) clearTimeout(queryTimer)
  queryTimer = setTimeout(() => void loadHistory(true), delay)
}

watch(historyKeyword, () => scheduleHistoryReload(300))

onUnmounted(() => {
  if (queryTimer) clearTimeout(queryTimer)
})

/** 类型切换与日期范围变化都是「筛选条件变了」，页码必须回到第 1 页。 */
function selectHistoryType(key: 'ALL' | 'SEARCH' | 'REASONING' | 'PUZZLE') {
  if (historyFilter.value === key) return
  historyFilter.value = key
  scheduleHistoryReload()
}

/**
 * 日期变化同样要重查，并回到第 1 页。
 * `from > to` 直接拦下不发请求：放任它发出去只会得到一份空列表，
 * 玩家会以为「这段时间真的没有记录」，而不是「我把范围填反了」。
 */
watch([historyFrom, historyTo], ([from, to]) => {
  if (from && to && from > to) {
    ElMessage.warning('起始日期不能晚于结束日期')
    return
  }
  scheduleHistoryReload()
})

/** 日志条目的中文标签与配色，未知类型回退为原值。 */
function actionLabel(actionType: string) {
  const map: Record<string, string> = {
    SEARCH: '调查地点',
    REASONING: '推理分析',
    PUZZLE: '谜题校验',
  }
  return map[actionType] || actionType
}

function formatTime(value: string) {
  return dayjs(value).format('YYYY-MM-DD HH:mm')
}

function resetHistoryFilter() {
  historyFilter.value = 'ALL'
  historyKeyword.value = ''
  historyFrom.value = ''
  historyTo.value = ''
  scheduleHistoryReload()
}

/** 导出时最多拉这么多页，避免把浏览器拖死。 */
const EXPORT_PAGE_CAP = 20

/**
 * 把当前筛选条件下的**全部**记录取回来，用于导出。
 * 不能直接导出当前页 —— 那样「筛完再导出」会得到一份比预期短得多的文件。
 * 同时要如实返回 truncated，让导出文件自己说明可能不完整。
 */
async function fetchAllFiltered(): Promise<{ rows: HistoryEntry[]; truncated: boolean }> {
  const rows: HistoryEntry[] = []
  let page = 1
  let truncated = false
  while (page <= EXPORT_PAGE_CAP) {
    const result = await caseApi.history(caseId, {
      type: historyFilter.value === 'ALL' ? undefined : historyFilter.value,
      keyword: historyKeyword.value.trim() || undefined,
      from: historyFrom.value || undefined,
      to: historyTo.value || undefined,
      page,
      size: 100,
    })
    rows.push(...result.entries)
    truncated = truncated || result.truncated
    if (!result.hasMore) break
    page += 1
  }
  return { rows, truncated }
}

/**
 * 把当前筛选结果导出成 Markdown「调查笔记」，让玩家能自己留档。
 * 导出的是**整个筛选结果**而不是当前页 —— 否则玩家筛完再导出会得到一份不匹配的文件。
 */
async function exportHistory() {
  if (historyLoading.value) return
  historyLoading.value = true
  let rows: HistoryEntry[] = []
  let truncated = false
  try {
    const fetched = await fetchAllFiltered()
    rows = fetched.rows
    truncated = fetched.truncated
  } catch (error) {
    ElMessage.error((error as Error).message)
    return
  } finally {
    historyLoading.value = false
  }

  if (!rows.length) {
    ElMessage.warning('当前筛选没有可导出的记录')
    return
  }

  const title = detail.value?.caseInfo.title || `案件 ${caseId}`
  const scope = historyFilterActive.value ? '（已按当前筛选导出）' : ''
  const lines = [
    `# 调查日志 · ${title}`,
    '',
    `导出时间：${dayjs().format('YYYY-MM-DD HH:mm')}`,
    `记录条数：${rows.length}${scope}`,
  ]
  if (historyFilterActive.value) {
    const parts: string[] = []
    if (historyFilter.value !== 'ALL') parts.push(`类型=${actionLabel(historyFilter.value)}`)
    if (historyKeyword.value.trim()) parts.push(`关键词=${historyKeyword.value.trim()}`)
    if (historyFrom.value || historyTo.value) {
      // 单边范围也要如实写出来，否则导出的文件会让人以为「这是全部记录」
      parts.push(`日期=${historyFrom.value || '不限'} ~ ${historyTo.value || '不限'}`)
    }
    lines.push(`筛选条件：${parts.join('，')}`)
  }
  if (truncated) {
    lines.push('> ⚠️ 记录数触到服务端扫描上限，本文件可能不完整。')
  }
  lines.push('', '---', '')

  rows.forEach((entry) => {
    const place = entry.locationName ? ` · ${entry.locationName}` : ''
    lines.push(`## ${formatTime(entry.createdAt)} · ${actionLabel(entry.actionType)}${place}`)
    lines.push('')
    lines.push(entry.resultText || '本次操作没有返回文字结果。')
    lines.push('')
    lines.push('---')
    lines.push('')
  })

  lines.push('> 本日志由 MindTrace AI 推理档案导出，内容为玩家在游戏中的调查记录。')
  lines.push('> 游戏区分 REAL / ADAPTED / FICTIONAL 内容，不对现实未结案件下结论。')

  const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `mindtrace-log-${caseId}-${dayjs().format('YYYYMMDD-HHmm')}.md`
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  // 立刻 revoke 有可能在部分浏览器里打断下载，延后释放更稳。
  setTimeout(() => URL.revokeObjectURL(url), 1000)

  ElMessage.success(`已导出 ${rows.length} 条记录`)
}

function mergeClues(clues: Clue[]) {
  if (!detail.value || !clues.length) return
  const existing = new Set(detail.value.discoveredClues.map((item) => item.id))
  clues.forEach((clue) => {
    if (!existing.has(clue.id)) {
      detail.value?.discoveredClues.unshift(clue)
    }
  })
}

async function investigate() {
  if (!activeLocation.value) return
  if (activeLocation.value.status === 'LOCKED') {
    ElMessage.warning(activeLocation.value.lockedReason || '该地点尚未解锁')
    return
  }
  investigating.value = true
  try {
    const result = await caseApi.investigate(caseId, {
      locationKey: activeLocation.value.locationKey,
      action: 'SEARCH',
    })
    investigationNarrative.value = result.narrative
    mergeClues(result.unlockedClues)
    // 节点状态（含本次调查解锁的新地点）一律以后端返回为准。
    await refreshNodeStates()
    if (result.unlockedClues.length) {
      audio.playCue('clue')
      ElMessage.success(`发现 ${result.unlockedClues.length} 条新线索`)
    }
  } catch (error) {
    audio.playCue('error')
    ElMessage.error((error as Error).message)
  } finally {
    investigating.value = false
  }
}

function selectNpc(id: number) {
  activeNpcId.value = id
  aiNotice.value = ''
}

async function sendMessage(message: string) {
  if (chatLoading.value || !activeNpcId.value || !detail.value) return
  const npcId = activeNpcId.value
  chatMemory[npcId] ||= []
  chatMemory[npcId].push({ role: 'user', content: message })
  chatLoading.value = true
  aiNotice.value = ''
  try {
    const result = await caseApi.chat(caseId, { npcId, message })
    chatMemory[npcId].push({ role: 'assistant', content: result.reply })
    aiNotice.value = result.aiNotice || ''
    if (result.unlockedClueIds.length) {
      await loadCase()
      audio.playCue('clue')
      ElMessage.success('对话内容解锁了新的档案线索')
    }
  } catch (error) {
    chatMemory[npcId].push({
      role: 'assistant',
      content: '调查终端暂时无法返回结果。你的问题没有丢失，可以稍后重试。',
    })
    aiNotice.value = (error as Error).message
  } finally {
    chatLoading.value = false
  }
}

async function submitPuzzle(puzzle: Puzzle, answer: string) {
  try {
    const result = await caseApi.solvePuzzle(caseId, puzzle.id, answer)
    if (result.correct) {
      audio.playCue('confirm')
      ElMessage.success(result.message)
    } else {
      audio.playCue('error')
      ElMessage.warning(result.message)
    }
    if (detail.value) {
      detail.value.progress = result.progress
    }
    mergeClues(result.unlockedClues)
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function analyzeReasoning(hypothesis: string) {
  reasoningLoading.value = true
  try {
    reasoning.value = await caseApi.reasoning(caseId, hypothesis)
    if (!reasoning.value.aiAvailable && reasoning.value.aiNotice) {
      aiNotice.value = reasoning.value.aiNotice
    }
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    reasoningLoading.value = false
  }
}

async function submitCase(payload: {
  hypothesis: string
  keyPeople: string
  keyTimeline: string
  evidenceClueIds: number[]
  reasoningText: string
  conclusion: string
}) {
  submitLoading.value = true
  try {
    submitResult.value = await caseApi.submit(caseId, payload)
    audio.setTrack('result')
    await auth.refresh()
    await loadCase()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    submitLoading.value = false
  }
}

function openSubmit() {
  submitVisible.value = true
}
</script>

<template>
  <div v-if="loading" class="case-loading">
    <LoaderCircle :size="28" class="spin" />
    <span>正在构建案件上下文...</span>
  </div>

  <div v-else-if="detail" class="page-wrap investigation-page">
    <section class="case-header panel">
      <div class="case-breadcrumb">
        <RouterLink to="/cases"><ArrowLeft :size="14" />案件档案</RouterLink>
        <span>/</span>
        <span class="mono">{{ detail.caseInfo.caseCode }}</span>
      </div>
      <div class="case-title-row">
        <div>
          <div class="case-tags">
            <MaterialTag type="REAL" />
            <span class="tag">{{ detail.caseInfo.caseType }}</span>
            <span class="tag">{{ detail.caseInfo.difficulty }}</span>
            <span v-if="detail.progress.completed" class="tag tag--ok"><CheckCircle2 :size="11" />已结案</span>
          </div>
          <h1>{{ detail.caseInfo.title }}</h1>
          <p>{{ detail.caseInfo.subtitle }} · {{ detail.caseInfo.realName }}</p>
        </div>
        <div class="overall-progress">
          <span>调查进度</span>
          <strong>{{ overallProgress }}%</strong>
          <div><i :style="{ width: `${overallProgress}%` }"></i></div>
        </div>
      </div>
      <div class="case-state-bar">
        <span><MapPinned :size="13" />地点 {{ detail.progress.investigatedLocations }}/{{ detail.progress.totalLocations }}</span>
        <span><Lightbulb :size="13" />线索 {{ detail.progress.discoveredClues }}/{{ detail.progress.totalDiscoverableClues }}</span>
        <span><PuzzleIcon :size="13" />谜题 {{ detail.progress.solvedPuzzles }}/{{ detail.progress.totalPuzzles }}</span>
        <span><Landmark :size="13" />来源 {{ detail.sources.length }}</span>
      </div>
    </section>

    <section class="investigation-layout">
      <aside class="case-sidebar panel">
        <div class="sidebar-file">
          <span class="mono">{{ detail.caseInfo.caseCode }}</span>
          <h3>{{ detail.caseInfo.title }}</h3>
          <p>{{ detail.caseInfo.era }} · {{ detail.caseInfo.location }}</p>
        </div>
        <nav class="case-tabs">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            :class="{ active: activeTab === tab.key }"
            @click="activeTab = tab.key"
          >
            <component :is="tab.icon" :size="16" />
            <span>{{ tab.label }}</span>
            <ChevronRight :size="14" />
          </button>
        </nav>
        <div class="data-policy">
          <ShieldQuestion :size="17" />
          <strong>资料分级</strong>
          <p>REAL 为公开事实，ADAPTED 为调查顺序改编，FICTIONAL 为游戏角色或谜题。</p>
        </div>
      </aside>

      <main class="case-workspace panel">
        <Transition name="fade" mode="out-in">
          <section v-if="activeTab === 'timeline'" key="timeline" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">CASE TIMELINE</span>
                <h2>公开时间线</h2>
                <p>点击节点查看来源、人物和地点。争议时间会保留区间表达。</p>
              </div>
              <BookOpenText :size="25" />
            </header>
            <TimelinePanel :items="detail.timeline" />
          </section>

          <section v-else-if="activeTab === 'locations'" key="locations" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">INVESTIGATION MAP</span>
                <h2>调查地点</h2>
                <p>调查行为由 Java 校验，只有数据库中绑定的线索会被发现。</p>
              </div>
              <MapPinned :size="25" />
            </header>
            <div class="location-layout">
              <div class="location-map">
                <button
                  v-for="location in detail.locations"
                  :key="location.id"
                  type="button"
                  class="map-node"
                  :class="[
                    `map-node--${location.status.toLowerCase()}`,
                    { active: location.locationKey === activeLocationKey },
                  ]"
                  :style="{ left: `${location.mapX}%`, top: `${location.mapY}%` }"
                  :aria-disabled="location.status === 'LOCKED'"
                  :title="location.lockedReason || `${location.name} · ${locationStatusText(location.status)}`"
                  @click="selectLocation(location)"
                >
                  <span></span>
                  <strong>{{ location.name }}</strong>
                  <em v-if="location.status !== 'LOCKED'">
                    {{ location.foundClueCount }}/{{ location.clueCount }}
                  </em>
                </button>
                <div class="map-crosshair"></div>
              </div>
              <article v-if="activeLocation" class="location-detail">
                <span class="eyebrow">LOCATION FILE</span>
                <div class="location-detail-head">
                  <h3>{{ activeLocation.name }}</h3>
                  <span class="node-badge" :class="`node-badge--${activeLocation.status.toLowerCase()}`">
                    {{ locationStatusText(activeLocation.status) }}
                  </span>
                </div>
                <p>{{ activeLocation.description }}</p>
                <dl class="location-metrics">
                  <div>
                    <dt>本地点线索</dt>
                    <dd>{{ activeLocation.foundClueCount }} / {{ activeLocation.clueCount }}</dd>
                  </div>
                  <div>
                    <dt>节点状态</dt>
                    <dd>{{ locationStatusText(activeLocation.status) }}</dd>
                  </div>
                </dl>
                <p v-if="activeLocation.status === 'LOCKED'" class="location-locked">
                  <ShieldQuestion :size="15" />
                  <span>{{ activeLocation.lockedReason || '该地点尚未解锁' }}</span>
                </p>
                <button
                  class="investigate-button"
                  type="button"
                  :disabled="investigating || activeLocation.status === 'LOCKED'"
                  @click="investigate"
                >
                  <LoaderCircle v-if="investigating" :size="16" class="spin" />
                  <Search v-else :size="16" />
                  {{ investigating ? '检查中...' : '调查此地点' }}
                </button>
                <div v-if="investigationNarrative" class="investigation-result">
                  <CheckCircle2 :size="17" />
                  <p>{{ investigationNarrative }}</p>
                </div>
              </article>
            </div>
          </section>

          <section v-else-if="activeTab === 'people'" key="people" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">PERSONS OF INTEREST</span>
                <h2>公开关系人</h2>
                <p>案件表中的 suspects 在界面中按“相关人物”呈现，不把被调查等同于有罪。</p>
              </div>
              <Users :size="25" />
            </header>
            <div class="people-grid">
              <article v-for="person in detail.suspects" :key="person.id" class="person-card">
                <div class="person-avatar">{{ person.name.slice(0, 1) }}</div>
                <MaterialTag :type="person.contentType" />
                <h3>{{ person.name }}</h3>
                <strong>{{ person.role }}</strong>
                <p>{{ person.description }}</p>
                <footer>
                  <span>{{ person.relationship }}</span>
                  <span>{{ person.status }}</span>
                </footer>
              </article>
            </div>
          </section>

          <section v-else-if="activeTab === 'clues'" key="clues" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">DISCOVERED EVIDENCE</span>
                <h2>已获得线索</h2>
                <p>未获得线索不会显示内容。每条线索保留来源类型和重要程度。</p>
              </div>
              <Lightbulb :size="25" />
            </header>
            <div v-if="detail.discoveredClues.length" class="clue-grid">
              <ClueCard v-for="clue in detail.discoveredClues" :key="clue.id" :clue="clue" />
            </div>
            <div v-else class="empty-workspace">
              <FolderOpen :size="30" />
              <strong>目前没有已获得线索</strong>
              <p>前往“地点”调查，或与 NPC 询问特定主题。</p>
            </div>
          </section>

          <section v-else-if="activeTab === 'evidence'" key="evidence" class="workspace-section">
            <EvidenceBoard :case-id="caseId" :clue-count="detail.discoveredClues.length" />
          </section>

          <section v-else-if="activeTab === 'files'" key="files" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">SOURCE REGISTRY</span>
                <h2>案件资料来源</h2>
                <p>优先展示官方机构、主流媒体、档案机构和高可靠性公开材料。</p>
              </div>
              <FileText :size="25" />
            </header>
            <div class="source-list">
              <a
                v-for="source in detail.sources"
                :key="source.id"
                :href="source.sourceUrl"
                target="_blank"
                rel="noreferrer"
              >
                <div>
                  <span class="tag">{{ source.sourceType }}</span>
                  <span class="tag" :class="reliabilityClass(source.sourceReliability)">{{ source.sourceReliability }}</span>
                </div>
                <h3>{{ source.sourceName }} <ExternalLink :size="14" /></h3>
                <p>{{ source.description }}</p>
                <small>{{ source.publishedAt || '发布时间未标注' }}</small>
              </a>
            </div>
            <article class="case-description">
              <h3>案件说明</h3>
              <p>{{ detail.caseInfo.description }}</p>
              <div>
                <span>REAL {{ detail.caseInfo.realRatio }}%</span>
                <span>ADAPTED {{ detail.caseInfo.adaptedRatio }}%</span>
                <span>FICTIONAL {{ detail.caseInfo.fictionalRatio }}%</span>
              </div>
            </article>
          </section>

          <section v-else-if="activeTab === 'puzzles'" key="puzzles" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">LOGIC MODULES</span>
                <h2>推理谜题</h2>
                <p>共有 {{ detail.puzzles.length }} 个逻辑模块，已完成 {{ detail.progress.solvedPuzzles }} 个。</p>
              </div>
              <BrainCircuit :size="25" />
            </header>
            <PuzzlePanel :puzzles="detail.puzzles" :solved-count="detail.progress.solvedPuzzles" @submit="submitPuzzle" />
          </section>

          <section v-else key="log" class="workspace-section">
            <header class="workspace-head">
              <div>
                <span class="eyebrow">INVESTIGATION LOG</span>
                <h2>调查日志</h2>
                <p>按时间倒序记录你在本案的每一次调查、推理与谜题校验，支持按类型、关键词与时间范围筛选。</p>
              </div>
              <div class="log-actions">
                <button
                  class="log-action"
                  type="button"
                  :disabled="historyLoading || !historyTotal"
                  @click="exportHistory"
                >
                  <Download :size="14" />
                  导出笔记
                </button>
                <!-- 必须写成 loadHistory()：直接写 loadHistory 会把 MouseEvent 当成 resetPage 传进去，
                     于是每次点刷新都悄悄跳回第 1 页。 -->
                <button class="log-action" type="button" :disabled="historyLoading" @click="loadHistory()">
                  <RefreshCw :size="14" :class="{ spin: historyLoading }" />
                  刷新
                </button>
              </div>
            </header>

            <div v-if="historyTotal > 0 || historyFilterActive" class="log-filters">
              <div class="log-type-tabs">
                <button
                  v-for="type in historyTypeFilters"
                  :key="type.key"
                  type="button"
                  :class="{ active: historyFilter === type.key }"
                  :aria-pressed="historyFilter === type.key"
                  @click="selectHistoryType(type.key)"
                >
                  <component :is="type.icon" :size="13" />
                  {{ type.label }}
                  <span class="log-count">{{ historyCountFor(type.key) }}</span>
                </button>
              </div>

              <div class="log-filter-row">
                <label class="log-search">
                  <Search :size="13" />
                  <input v-model="historyKeyword" type="search" placeholder="搜索记录内容或地点" />
                </label>

                <!-- 用原生 date 而不是 el-date-picker：项目是手工覆盖 Element Plus 变量适配暗色的，
                     日期弹层没被覆盖到，会掉出一块白底。 -->
                <div class="log-range" role="group" aria-label="按时间范围筛选">
                  <CalendarRange :size="13" />
                  <input v-model="historyFrom" type="date" aria-label="起始日期" />
                  <span class="log-range-sep">至</span>
                  <input v-model="historyTo" type="date" aria-label="结束日期" />
                </div>

                <button v-if="historyFilterActive" class="log-clear" type="button" @click="resetHistoryFilter">
                  <X :size="13" />
                  清除筛选
                </button>
              </div>
            </div>

            <div v-if="historyLoading" class="log-loading">
              <LoaderCircle :size="18" class="spin" />
              <span>正在读取调查日志…</span>
            </div>

            <template v-else-if="historyEntries.length">
              <p class="log-summary">
                第 {{ historyPage }} / {{ historyTotalPages }} 页 · 共 {{ historyTotal }} 条<template
                  v-if="historyFilterActive"
                >（已按当前筛选）</template>
              </p>
              <p v-if="historyTruncated" class="log-warn">
                记录较多，统计只扫描了最近一部分，实际条数可能更多。
              </p>

              <ol class="log-list">
                <li v-for="entry in historyEntries" :key="entry.id">
                  <time :datetime="entry.createdAt">{{ formatTime(entry.createdAt) }}</time>
                  <div class="log-body">
                    <div class="log-meta">
                      <span class="log-chip" :class="`log-chip--${entry.actionType.toLowerCase()}`">
                        {{ actionLabel(entry.actionType) }}
                      </span>
                      <strong v-if="entry.locationName">{{ entry.locationName }}</strong>
                    </div>
                    <p>{{ entry.resultText || '本次操作没有返回文字结果。' }}</p>
                  </div>
                </li>
              </ol>

              <div v-if="historyTotalPages > 1" class="log-pager">
                <button type="button" :disabled="historyPage <= 1" @click="changeHistoryPage(1)">首页</button>
                <button
                  type="button"
                  :disabled="historyPage <= 1"
                  @click="changeHistoryPage(historyPage - 1)"
                >
                  上一页
                </button>
                <span class="log-pager-pos">{{ historyPage }} / {{ historyTotalPages }}</span>
                <button
                  type="button"
                  :disabled="!historyHasMore"
                  @click="changeHistoryPage(historyPage + 1)"
                >
                  下一页
                </button>
                <!-- 截断时 totalPages 只是下界，跳「末页」可能落在一个不存在的页码上 -->
                <button
                  v-if="!historyTruncated"
                  type="button"
                  :disabled="!historyHasMore"
                  @click="changeHistoryPage(historyTotalPages)"
                >
                  末页
                </button>
              </div>
            </template>

            <div v-else-if="historyTotal > 0 || historyFilterActive" class="log-no-match">
              <Search :size="24" />
              <strong>没有匹配的记录</strong>
              <p>换一个关键词、放宽时间范围，或切回「全部」。</p>
              <button type="button" @click="resetHistoryFilter">清除筛选</button>
            </div>

            <div v-else class="empty-workspace">
              <ScrollText :size="30" />
              <strong>还没有调查记录</strong>
              <p>前往“地点”执行调查、向 NPC 提问，或提交一次推理，这里就会留下痕迹。</p>
            </div>
          </section>
        </Transition>
      </main>

      <InvestigationTerminal
        :npcs="detail.npcs"
        :active-npc-id="activeNpcId"
        :messages="activeMessages"
        :loading="chatLoading"
        :ai-notice="aiNotice"
        @select="selectNpc"
        @send="sendMessage"
        @quick="sendMessage"
      />
    </section>

    <ReasoningPanel
      class="reasoning-section"
      :result="reasoning"
      :loading="reasoningLoading"
      @analyze="analyzeReasoning"
      @submit="openSubmit"
    />

    <SubmitCaseDialog
      v-model="submitVisible"
      :clues="detail.discoveredClues"
      :loading="submitLoading"
      :result="submitResult"
      @submit="submitCase"
    />
  </div>
</template>

<style scoped>
.case-loading {
  min-height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--muted);
}

.spin {
  animation: spin 1s linear infinite;
}

.investigation-page {
  padding-top: 18px;
}

.case-header {
  margin-bottom: 14px;
  padding: 15px 18px;
}

.case-breadcrumb {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--faint);
  font-size: 10px;
}

.case-breadcrumb a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.case-title-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-top: 12px;
}

.case-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.case-title-row h1 {
  margin: 10px 0 4px;
  font-size: clamp(28px, 3.5vw, 48px);
}

.case-title-row p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.overall-progress {
  width: 250px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px;
}

.overall-progress span {
  color: var(--faint);
  font-size: 9px;
}

.overall-progress strong {
  color: var(--cyan);
  font-family: "Cascadia Mono", monospace;
}

.overall-progress > div {
  grid-column: 1 / -1;
  height: 6px;
  overflow: hidden;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.06);
}

.overall-progress i {
  height: 100%;
  display: block;
  background: linear-gradient(90deg, var(--red), var(--amber), var(--cyan));
}

.case-state-bar {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  margin-top: 15px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 10px;
}

.case-state-bar span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.investigation-layout {
  display: grid;
  grid-template-columns: 218px minmax(540px, 1fr) 350px;
  gap: 14px;
  align-items: start;
}

.case-sidebar,
.case-workspace {
  position: sticky;
  top: 82px;
}

.case-sidebar {
  padding: 13px;
}

.sidebar-file {
  padding: 11px 10px 14px;
  border-bottom: 1px solid var(--line);
}

.sidebar-file span {
  color: var(--cyan);
  font-size: 9px;
}

.sidebar-file h3 {
  margin: 6px 0 4px;
  font-size: 16px;
}

.sidebar-file p {
  margin: 0;
  color: var(--faint);
  font-size: 9px;
  line-height: 1.5;
}

.case-tabs {
  display: grid;
  gap: 4px;
  padding: 10px 0;
}

.case-tabs button {
  min-height: 40px;
  display: grid;
  grid-template-columns: 22px 1fr auto;
  align-items: center;
  gap: 7px;
  padding: 0 9px;
  border: 1px solid transparent;
  color: var(--muted);
  text-align: left;
  background: transparent;
  border-radius: 5px;
  cursor: pointer;
}

.case-tabs button:hover,
.case-tabs button.active {
  color: var(--text);
  border-color: var(--line);
  background: rgba(255, 255, 255, 0.03);
}

.case-tabs button.active {
  color: #9ee0d9;
  border-color: rgba(93, 183, 176, 0.34);
  background: var(--cyan-soft);
}

.data-policy {
  padding: 11px;
  color: var(--faint);
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 5px;
}

.data-policy svg {
  color: var(--amber);
}

.data-policy strong {
  display: block;
  margin-top: 7px;
  color: var(--muted);
  font-size: 10px;
}

.data-policy p {
  margin: 5px 0 0;
  font-size: 9px;
  line-height: 1.6;
}

.case-workspace {
  min-height: 680px;
  padding: 20px;
}

.workspace-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 22px;
  padding-bottom: 15px;
  border-bottom: 1px solid var(--line);
}

.workspace-head h2 {
  margin: 5px 0 5px;
  font-size: 23px;
}

.workspace-head p {
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.6;
}

.workspace-head > svg {
  color: var(--cyan);
}

.location-layout {
  display: grid;
  grid-template-columns: minmax(280px, 1.1fr) minmax(240px, 0.9fr);
  gap: 18px;
}

.location-map {
  position: relative;
  min-height: 460px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
  background:
    linear-gradient(rgba(93, 183, 176, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(93, 183, 176, 0.08) 1px, transparent 1px),
    radial-gradient(circle at 52% 48%, rgba(93, 183, 176, 0.1), transparent 34%),
    #0b1013;
  background-size: 32px 32px, 32px 32px, auto, auto;
}

.location-map button {
  position: absolute;
  z-index: 2;
  max-width: 150px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  color: var(--muted);
  text-align: left;
  background: rgba(10, 14, 16, 0.92);
  border-radius: 4px;
  transform: translate(-50%, -50%);
  cursor: pointer;
  font-size: 9px;
  transition: border-color 0.24s ease, color 0.24s ease, box-shadow 0.24s ease, opacity 0.24s ease;
}

.location-map button span {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--faint);
}

.location-map button em {
  flex: 0 0 auto;
  padding: 1px 5px;
  border-radius: 999px;
  background: rgba(166, 188, 196, 0.12);
  color: var(--muted);
  font-size: 8px;
  font-style: normal;
  letter-spacing: 0.04em;
}

/* 节点状态由后端计算，样式只做可视化：未解锁 / 可调查 / 已调查 / 已完成 */
.map-node--locked {
  opacity: 0.5;
  border-style: dashed;
  cursor: not-allowed;
}

.map-node--locked span {
  background: var(--faint);
}

.map-node--available span {
  background: var(--amber);
  box-shadow: 0 0 0 3px var(--amber-soft);
}

.map-node--investigated span {
  background: var(--cyan);
}

.map-node--completed {
  border-color: rgba(93, 183, 176, 0.42);
}

.map-node--completed span {
  background: var(--cyan);
  box-shadow: 0 0 0 3px var(--cyan-soft);
}

.map-node--completed em {
  background: var(--cyan-soft);
  color: var(--cyan);
}

.location-map button.active {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.6);
  box-shadow: 0 0 0 4px rgba(93, 183, 176, 0.08);
}

.location-map button.active span {
  background: var(--cyan);
}

.location-map button.active.map-node--locked {
  border-color: rgba(166, 188, 196, 0.4);
  box-shadow: none;
}

.map-crosshair {
  position: absolute;
  inset: 50% 0 auto;
  border-top: 1px dashed rgba(93, 183, 176, 0.2);
}

.location-detail {
  align-self: start;
  padding: 18px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 7px;
}

.location-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.location-detail h3 {
  margin: 8px 0 10px;
  font-size: 20px;
}

.node-badge {
  flex: 0 0 auto;
  padding: 3px 9px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.node-badge--locked {
  border-color: rgba(166, 188, 196, 0.3);
  color: var(--faint);
}

.node-badge--available {
  border-color: rgba(204, 155, 87, 0.45);
  color: var(--amber);
  background: var(--amber-soft);
}

.node-badge--investigated {
  border-color: rgba(93, 183, 176, 0.4);
  color: var(--cyan);
}

.node-badge--completed {
  border-color: rgba(93, 183, 176, 0.55);
  color: #bce9e4;
  background: var(--cyan-soft);
}

.location-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 14px 0 0;
  padding: 12px 0 0;
  border-top: 1px solid var(--line);
}

.location-metrics dt {
  margin-bottom: 4px;
  color: var(--faint);
  font-size: 9px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.location-metrics dd {
  margin: 0;
  color: var(--text);
  font-size: 13px;
}

.location-detail > p.location-locked {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 14px;
  padding: 10px;
  border: 1px dashed rgba(166, 188, 196, 0.3);
  color: var(--muted);
  background: rgba(166, 188, 196, 0.05);
  border-radius: 5px;
}

.location-detail > p.location-locked svg {
  flex: 0 0 auto;
  margin-top: 1px;
  color: var(--faint);
}

.location-detail > p {
  margin: 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.75;
}

.investigate-button {
  width: 100%;
  min-height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 16px;
  border: 1px solid rgba(93, 183, 176, 0.38);
  color: #bce9e4;
  background: var(--cyan-soft);
  border-radius: 5px;
  cursor: pointer;
}

.investigate-button:disabled {
  opacity: 0.45;
}

.investigation-result {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 14px;
  padding: 10px;
  color: #9ee0d9;
  border: 1px solid rgba(93, 183, 176, 0.25);
  background: var(--cyan-soft);
  border-radius: 5px;
}

.investigation-result p {
  margin: 0;
  font-size: 10px;
  line-height: 1.6;
}

.people-grid,
.clue-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 11px;
}

.person-card {
  padding: 15px;
  border: 1px solid var(--line);
  background: rgba(15, 21, 24, 0.84);
  border-radius: 7px;
}

.person-avatar {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  margin-bottom: 10px;
  color: #cce6e2;
  background: linear-gradient(145deg, #315c5a, #704840);
  border-radius: 6px;
  font-size: 16px;
}

.person-card h3 {
  margin: 10px 0 4px;
  font-size: 17px;
}

.person-card > strong {
  color: var(--cyan);
  font-size: 10px;
  font-weight: 400;
}

.person-card > p {
  min-height: 66px;
  margin: 10px 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.65;
}

.person-card footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 9px;
}

.empty-workspace {
  min-height: 380px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--faint);
  text-align: center;
}

.empty-workspace strong {
  margin-top: 12px;
  color: var(--muted);
}

.empty-workspace p {
  font-size: 10px;
}

/* ---------- 调查日志 ---------- */

.log-actions {
  display: flex;
  gap: 7px;
  flex: 0 0 auto;
}

.log-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  font-size: 10px;
}

.log-action:hover:not(:disabled) {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.42);
}

.log-action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.log-filters {
  display: grid;
  gap: 9px;
  margin-bottom: 14px;
}

.log-type-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.log-type-tabs button {
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--muted);
  background: transparent;
  cursor: pointer;
  font-size: 10px;
}

.log-type-tabs button:hover {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.34);
}

.log-type-tabs button.active {
  color: #bce9e4;
  border-color: rgba(93, 183, 176, 0.45);
  background: var(--cyan-soft);
}

.log-count {
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(166, 188, 196, 0.14);
  font-size: 9px;
}

.log-search {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--faint);
  background: rgba(255, 255, 255, 0.02);
}

.log-search:focus-within {
  border-color: rgba(93, 183, 176, 0.42);
}

.log-search input {
  flex: 1;
  min-width: 0;
  height: 34px;
  border: 0;
  outline: none;
  color: var(--text);
  background: transparent;
  font-size: 11px;
}

.log-search input::placeholder {
  color: var(--faint);
}

/* 搜索框 + 日期范围 + 清除按钮同一行；窄屏自动换行。 */
.log-filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.log-filter-row .log-search {
  flex: 1 1 200px;
}

.log-range {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--faint);
  background: rgba(255, 255, 255, 0.02);
}

.log-range:focus-within {
  border-color: rgba(93, 183, 176, 0.42);
}

/*
 * color-scheme 必须显式声明：否则浏览器给原生日期弹层用亮色配色，
 * 在暗色界面里会掉出一块白底 —— 和 el-date-picker 没被主题变量覆盖是同一个坑。
 */
.log-range input[type='date'] {
  height: 32px;
  border: 0;
  outline: none;
  color: var(--text);
  background: transparent;
  font-family: inherit;
  font-size: 11px;
  color-scheme: dark;
  cursor: text;
}

.log-range input[type='date']::-webkit-calendar-picker-indicator {
  opacity: 0.5;
  cursor: pointer;
}

.log-range input[type='date']::-webkit-calendar-picker-indicator:hover {
  opacity: 0.9;
}

.log-range-sep {
  color: var(--faint);
  font-size: 10px;
}

.log-clear {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: transparent;
  cursor: pointer;
  font-size: 10px;
}

.log-clear:hover {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.34);
}

.log-summary {
  margin: 0 0 9px;
  color: var(--faint);
  font-size: 10px;
}

/* 扫描上限提示：警示色，但压住亮度，不抢正文。 */
.log-warn {
  margin: 0 0 9px;
  color: #d9a55f;
  font-size: 10px;
}

.log-pager {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.log-pager button {
  min-height: 30px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: transparent;
  cursor: pointer;
  font-size: 10px;
}

.log-pager button:hover:not(:disabled) {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.34);
}

.log-pager button:disabled {
  opacity: 0.38;
  cursor: not-allowed;
}

.log-pager-pos {
  padding: 0 4px;
  color: var(--faint);
  font-size: 10px;
}

.log-no-match {
  min-height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--faint);
  text-align: center;
}

.log-no-match strong {
  color: var(--muted);
  font-size: 12px;
}

.log-no-match p {
  margin: 0;
  font-size: 10px;
}

.log-no-match button {
  margin-top: 4px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  font-size: 10px;
}

.log-no-match button:hover {
  color: var(--text);
  border-color: rgba(93, 183, 176, 0.42);
}

.log-loading {
  min-height: 240px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: var(--muted);
  font-size: 11px;
}

.log-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.log-list > li {
  position: relative;
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr);
  gap: 16px;
  padding: 13px 0 13px 18px;
  border-bottom: 1px solid var(--line);
}

.log-list > li::before {
  content: '';
  position: absolute;
  top: 19px;
  left: 3px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--faint);
}

.log-list > li:first-child::before {
  background: var(--cyan);
  box-shadow: 0 0 0 4px rgba(93, 183, 176, 0.14);
}

.log-list time {
  padding-top: 3px;
  color: var(--faint);
  font-size: 9px;
  letter-spacing: 0.04em;
}

.log-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.log-meta strong {
  font-size: 11px;
}

.log-chip {
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: 0.06em;
}

.log-chip--search {
  color: var(--cyan);
  border-color: rgba(93, 183, 176, 0.4);
}

.log-chip--reasoning {
  color: var(--amber);
  border-color: rgba(204, 155, 87, 0.42);
}

.log-chip--puzzle {
  color: #8fb8f0;
  border-color: rgba(143, 184, 240, 0.42);
}

.log-body p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.65;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
}

.source-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.source-list > a {
  padding: 14px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
  border-radius: 6px;
}

.source-list > a:hover {
  border-color: rgba(93, 183, 176, 0.36);
}

.source-list > a > div {
  display: flex;
  gap: 5px;
}

.source-list h3 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 12px 0 7px;
  font-size: 14px;
}

.source-list p {
  min-height: 48px;
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.6;
}

.source-list small {
  display: block;
  margin-top: 10px;
  color: var(--faint);
  font-size: 9px;
}

.case-description {
  margin-top: 15px;
  padding: 14px;
  border-left: 2px solid var(--amber);
  background: var(--amber-soft);
}

.case-description h3 {
  margin: 0 0 7px;
}

.case-description p {
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 1.7;
}

.case-description div {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  color: var(--amber);
  font-family: "Cascadia Mono", monospace;
  font-size: 9px;
}

.reasoning-section {
  margin-top: 14px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1320px) {
  .investigation-layout {
    grid-template-columns: 190px minmax(470px, 1fr) 320px;
  }
}

@media (max-width: 1120px) {
  .investigation-layout {
    grid-template-columns: 188px 1fr;
  }

  .investigation-layout > :last-child {
    grid-column: 1 / -1;
  }

  .case-sidebar,
  .case-workspace {
    position: static;
  }
}

@media (max-width: 800px) {
  .case-title-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .overall-progress {
    width: 100%;
  }

  .investigation-layout {
    grid-template-columns: 1fr;
  }

  .case-tabs {
    grid-template-columns: repeat(3, 1fr);
  }

  .case-sidebar,
  .case-workspace {
    min-height: auto;
  }

  .location-layout,
  .people-grid,
  .clue-grid,
  .source-list {
    grid-template-columns: 1fr;
  }

  .location-map {
    min-height: 340px;
  }

  .log-list > li {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .log-list time {
    padding-top: 0;
  }
}
</style>
