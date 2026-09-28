import client, { unwrap } from './client'
import type {
  CaseDetail,
  CaseSummary,
  ChatMessage,
  ChatResponse,
  EvidenceBoardData,
  EvidenceLink,
  HistoryPage,
  HistoryQuery,
  Progress,
  RankingBoard,
  ReasoningResult,
  SubmitResult,
  UserProfile,
} from '@/types'

export const authApi = {
  login: (payload: { username: string; password: string }) =>
    unwrap<{ token: string; profile: UserProfile }>(client.post('/auth/login', payload)),
  register: (payload: { username: string; nickname: string; password: string }) =>
    unwrap<{ token: string; profile: UserProfile }>(client.post('/auth/register', payload)),
  logout: () => unwrap<void>(client.post('/auth/logout')),
}

export const userApi = {
  profile: () => unwrap<UserProfile>(client.get('/user/profile')),
}

export const caseApi = {
  list: () => unwrap<CaseSummary[]>(client.get('/cases')),
  detail: (id: number) => unwrap<CaseDetail>(client.get(`/cases/${id}`)),
  progress: (id: number) => unwrap<Progress>(client.get(`/cases/${id}/progress`)),
  // 筛选与分页都在服务端做：本地只筛当前页会让人以为「就这么多」，
  // 而且关键词必须匹配**展示文本**（推理记录原文带着审计用的原始 AI JSON）。
  history: (id: number, params: HistoryQuery = {}) =>
    unwrap<HistoryPage>(client.get(`/cases/${id}/history`, { params })),
  investigate: (id: number, payload: { locationKey: string; action?: string }) =>
    unwrap<{
      location: CaseDetail['locations'][number]
      narrative: string
      unlockedClues: CaseDetail['discoveredClues']
      progress: Progress
    }>(client.post(`/cases/${id}/investigate`, payload)),
  solvePuzzle: (id: number, puzzleId: number, answer: string) =>
    unwrap<{
      correct: boolean
      message: string
      unlockedClues: CaseDetail['discoveredClues']
      progress: Progress
    }>(client.post(`/cases/${id}/puzzles/${puzzleId}`, { answer })),
  chat: (id: number, payload: { npcId: number; message: string }) =>
    unwrap<ChatResponse>(client.post(`/cases/${id}/chat`, payload)),
  chatHistory: (id: number, npcId: number) =>
    unwrap<ChatMessage[]>(client.get(`/cases/${id}/chat`, { params: { npcId } })),
  reasoning: (id: number, hypothesis: string) =>
    unwrap<ReasoningResult>(client.post(`/cases/${id}/reasoning`, { hypothesis })),
  submit: (
    id: number,
    payload: {
      hypothesis: string
      keyPeople: string
      keyTimeline: string
      evidenceClueIds: number[]
      reasoningText: string
      conclusion: string
    },
  ) => unwrap<SubmitResult>(client.post(`/cases/${id}/submit`, payload)),
  evidenceBoard: (id: number) => unwrap<EvidenceBoardData>(client.get(`/cases/${id}/evidence-links`)),
  createEvidenceLink: (
    id: number,
    payload: { fromClueId: number; toClueId: number; relationType?: string; note?: string },
  ) => unwrap<EvidenceLink>(client.post(`/cases/${id}/evidence-links`, payload)),
  deleteEvidenceLink: (id: number, linkId: number) =>
    unwrap<void>(client.delete(`/cases/${id}/evidence-links/${linkId}`)),
}

export const rankingApi = {
  list: (type = 'score') => unwrap<RankingBoard>(client.get('/ranking', { params: { type } })),
}

export const healthApi = {
  check: () => unwrap<{ status: string; deepSeekConfigured: boolean }>(client.get('/health')),
}
