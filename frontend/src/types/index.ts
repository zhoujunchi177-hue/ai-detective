export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
}

export interface UserProfile {
  id: number
  username: string
  nickname: string
  avatar: string
  level: number
  exp: number
  nextLevelExp: number
  coins: number
  completedCases: number
  streakDays: number
  totalScore: number
  lastLoginAt: string
  achievements: AchievementView[]
  history: GameHistory[]
}

export type AchievementRarity = 'COMMON' | 'RARE' | 'EPIC' | 'LEGENDARY'

export interface AchievementView {
  id: number
  code: string
  name: string
  description: string
  icon: string
  /** 稀有度只用于展示与排序，不参与解锁判定（判定在 Java 侧）。 */
  rarity?: AchievementRarity
  rewardExp?: number
  rewardCoins?: number
  unlocked: boolean
  unlockedAt?: string
}

export interface GameHistory {
  recordId: number
  caseId: number
  caseTitle: string
  caseCode: string
  totalScore: number
  status: string
  completedAt?: string
}

export interface CaseSummary {
  id: number
  caseCode: string
  title: string
  subtitle: string
  realName: string
  summary: string
  caseType: string
  difficulty: string
  era: string
  location: string
  coverUrl: string
  status: string
  /** 案件资料完整度（静态内容量），不是玩家进度。 */
  completion: number
  players: number
  contentRating: number
  /**
   * 当前玩家在本案的存档进度。未开始的案件也会返回这个对象，
   * 只是 started 为 false、各项计数为 0；只有匿名请求时这个字段才整体缺席。
   */
  playerProgress?: PlayerProgress
}

export interface CaseInfo extends CaseSummary {
  description: string
  realRatio: number
  adaptedRatio: number
  fictionalRatio: number
}

export interface TimelineItem {
  id: number
  caseId: number
  eventTime?: string
  eventDateText: string
  title: string
  description: string
  people?: string
  location?: string
  sourceName?: string
  sourceUrl?: string
  contentType: 'REAL' | 'ADAPTED' | 'FICTIONAL'
  sortOrder: number
}

/** 调查地图节点状态。由后端 Java 计算，前端只渲染，不得自行推断。 */
export type LocationStatus = 'LOCKED' | 'AVAILABLE' | 'INVESTIGATED' | 'COMPLETED'

export interface LocationView {
  id: number
  caseId?: number
  locationKey: string
  name: string
  description: string
  icon: string
  mapX: number
  mapY: number
  unlockCondition?: string
  status: LocationStatus
  /** 本地点非隐藏线索总数（隐藏线索属于额外奖励，不参与完成度判定）。 */
  clueCount: number
  /** 其中已被玩家发现的条数。 */
  foundClueCount: number
  /** 仅 status === 'LOCKED' 时有值，后端给出的中文解锁说明。 */
  lockedReason?: string
}

export interface Npc {
  id: number
  caseId: number
  npcKey: string
  name: string
  avatar: string
  description: string
  personality: string
  identity: string
  location: string
  greeting: string
  relationship: string
  contentType: string
}

export interface Clue {
  id: number
  caseId: number
  clueCode: string
  title: string
  content: string
  type: string
  importance: number
  sourceType: 'REAL' | 'ADAPTED' | 'FICTIONAL'
  sourceName?: string
  sourceUrl?: string
  unlockCondition?: string
  locationKey?: string
  isReal: boolean
  isHidden: boolean
  createdAt: string
}

export interface CaseSource {
  id: number
  caseId: number
  sourceName: string
  sourceUrl: string
  sourceType: string
  publishedAt?: string
  description: string
  sourceReliability: string
}

export interface Suspect {
  id: number
  caseId: number
  name: string
  alias?: string
  role: string
  description: string
  relationship: string
  status: string
  evidenceLevel: string
  contentType: 'REAL' | 'ADAPTED' | 'FICTIONAL'
}

export interface Puzzle {
  id: number
  caseId: number
  puzzleKey: string
  title: string
  description: string
  type: 'TIME_SORT' | 'EVIDENCE_LINK' | 'PERSON_RELATION'
  payload: string
  importance: number
}

export interface Progress {
  investigatedLocations: number
  totalLocations: number
  discoveredClues: number
  totalDiscoverableClues: number
  solvedPuzzles: number
  totalPuzzles: number
  investigationPercent: number
  cluePercent: number
  /** 综合完成度，由后端加权计算（调查 30% + 线索 30% + 谜题 40%）。 */
  overallPercent: number
  completed: boolean
}

/**
 * 玩家在本案的存档进度。未开始调查时为 null。
 * 注意与 CaseSummary.completion（案件资料完整度）区分：那是静态内容量，不是玩家进度。
 */
export interface PlayerProgress {
  started: boolean
  completed: boolean
  percent: number
  discoveredClues: number
  totalClues: number
  investigatedLocations: number
  totalLocations: number
  solvedPuzzles: number
  totalPuzzles: number
}

/** 玩家在本案的调查日志条目。locationName 可能为空（如推理记录）。 */
export interface HistoryEntry {
  id: number
  actionType: string
  locationName?: string
  resultText: string
  createdAt: string
}

/** 日志筛选条件。from / to 是日期（yyyy-MM-dd），to 含当天整天。 */
export interface HistoryQuery {
  type?: string
  keyword?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

/**
 * 日志分页结果。
 * typeCounts 是「日期 + 关键词」筛选下（不含类型）的各类型条数，供类型标签显示计数。
 * truncated 为 true 表示服务端扫描触到了上限，total 是下界而非精确值。
 */
export interface HistoryPage {
  entries: HistoryEntry[]
  page: number
  size: number
  total: number
  totalPages: number
  hasMore: boolean
  truncated: boolean
  typeCounts: Record<string, number>
}

export interface CaseDetail {
  caseInfo: CaseInfo
  timeline: TimelineItem[]
  locations: LocationView[]
  npcs: Npc[]
  suspects: Suspect[]
  sources: CaseSource[]
  discoveredClues: Clue[]
  puzzles: Puzzle[]
  progress: Progress
}

export interface ChatMessage {
  id?: number
  role: 'user' | 'assistant'
  content: string
  createdAt?: string
}

/** 证据板节点：玩家已发现线索的精简视图。 */
export interface EvidenceNode {
  clueId: number
  clueCode: string
  title: string
  type: string
  importance: number
  sourceType?: string
}

/** 证据板连线。两端线索的摘要由后端一并返回。 */
export interface EvidenceLink {
  id: number
  from: EvidenceNode
  to: EvidenceNode
  relationType: string
  relationLabel: string
  note?: string
  createdAt: string
}

/** 关系类型选项由后端给出，前端不硬编码标签。 */
export interface RelationTypeOption {
  value: string
  label: string
  hint: string
}

/** 证据板全量数据：节点 + 连线 + 可选关系类型。 */
export interface EvidenceBoardData {
  nodes: EvidenceNode[]
  links: EvidenceLink[]
  relationTypes: RelationTypeOption[]
}

export interface ChatResponse {
  reply: string
  aiAvailable: boolean
  aiNotice?: string
  unlockedClueIds: number[]
}

export interface ReasoningResult {
  summary: string
  supportingEvidence: string[]
  contradictions: string[]
  missingEvidence: string[]
  suggestions: string[]
  confidence: number
  confidenceLabel: string
  aiAvailable: boolean
  aiNotice?: string
}

export interface SubmitResult {
  recordId: number
  investigationScore: number
  clueScore: number
  timelineScore: number
  logicScore: number
  totalScore: number
  expReward: number
  coinReward: number
  rating: string
  aiReport: string
  aiAvailable: boolean
  aiNotice?: string
  newAchievements: string[]
}

export interface RankingEntry {
  rank: number
  userId: number
  nickname: string
  avatar: string
  level: number
  exp: number
  completedCases: number
  totalScore: number
  currentUser: boolean
}

export interface RankingBoard {
  type: string
  title: string
  entries: RankingEntry[]
}

