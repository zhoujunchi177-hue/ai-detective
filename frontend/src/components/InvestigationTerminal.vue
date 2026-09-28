<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { AlertCircle, Bot, CornerDownLeft, Send, Sparkles } from 'lucide-vue-next'
import type { ChatMessage, Npc } from '@/types'

const props = defineProps<{
  npcs: Npc[]
  activeNpcId?: number
  messages: ChatMessage[]
  loading: boolean
  aiNotice?: string
}>()

const emit = defineEmits<{
  select: [id: number]
  send: [message: string]
  quick: [message: string]
}>()

const input = ref('')
const scrollBox = ref<HTMLElement | null>(null)
const quickQuestions = ['询问时间线', '询问人物', '询问地点', '质疑证词', '重新分析']
const activeNpc = computed(() => props.npcs.find((item) => item.id === props.activeNpcId))

watch(
  () => props.messages.length,
  async () => {
    await nextTick()
    if (scrollBox.value) {
      scrollBox.value.scrollTop = scrollBox.value.scrollHeight
    }
  },
)

function send() {
  const value = input.value.trim()
  if (props.loading || !props.activeNpcId || !value) return
  emit('send', value)
  input.value = ''
}
</script>

<template>
  <section class="terminal panel">
    <header class="terminal-head">
      <div>
        <span class="terminal-label mono">AI INTERROGATION TERMINAL</span>
        <strong>调查终端</strong>
      </div>
      <span class="live-dot"></span>
    </header>

    <div class="npc-selector">
      <button
        v-for="npc in npcs"
        :key="npc.id"
        type="button"
        :class="{ active: npc.id === activeNpcId }"
        :disabled="loading"
        @click="emit('select', npc.id)"
      >
        <span class="npc-avatar">{{ npc.name.slice(0, 1) }}</span>
        <span>
          <strong>{{ npc.name }}</strong>
          <small>{{ npc.identity }}</small>
        </span>
      </button>
    </div>

    <div v-if="activeNpc" class="npc-identity">
      <Bot :size="16" />
      <span>
        <strong>{{ activeNpc.name }}</strong>
        <small>{{ activeNpc.location }} · {{ activeNpc.personality }}</small>
      </span>
    </div>

    <div ref="scrollBox" class="message-stream">
      <div v-if="!messages.length" class="terminal-empty">
        <Sparkles :size="20" />
        <strong>{{ activeNpc?.name || '选择一名 NPC' }}</strong>
        <p>{{ activeNpc?.greeting || '从上方选择对象后开始询问。NPC 只能回答其知识边界内的内容。' }}</p>
      </div>
      <article
        v-for="(message, index) in messages"
        :key="message.id || index"
        class="message"
        :class="message.role"
      >
        <span class="message-role">{{ message.role === 'user' ? '调查员' : activeNpc?.name }}</span>
        <p>{{ message.content }}</p>
      </article>
      <div v-if="loading" class="message assistant typing">
        <span></span><span></span><span></span>
      </div>
    </div>

    <div v-if="aiNotice" class="ai-notice">
      <AlertCircle :size="13" />
      {{ aiNotice }}
    </div>

    <div class="quick-grid">
      <button v-for="question in quickQuestions" :key="question" type="button" :disabled="loading || !activeNpcId" @click="emit('quick', question)">
        {{ question }}
      </button>
    </div>

    <div class="terminal-input">
      <textarea
        v-model="input"
        rows="3"
        maxlength="1000"
        placeholder="输入你的问题..."
        @keydown.ctrl.enter.prevent="send"
        @keydown.meta.enter.prevent="send"
      ></textarea>
      <button type="button" :disabled="loading || !input.trim()" @click="send">
        <Send :size="15" />
        <span>发送</span>
        <CornerDownLeft :size="12" />
      </button>
    </div>
  </section>
</template>

<style scoped>
.terminal {
  height: 100%;
  min-height: 680px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.34);
}

.terminal-head {
  height: 58px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 15px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.018);
}

.terminal-head strong,
.terminal-label {
  display: block;
}

.terminal-head strong {
  margin-top: 3px;
  font-size: 14px;
}

.terminal-label {
  color: var(--cyan);
  font-size: 8px;
  letter-spacing: 0.1em;
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cyan);
  box-shadow: 0 0 0 5px rgba(93, 183, 176, 0.09);
}

.npc-selector {
  max-height: 174px;
  overflow-y: auto;
  padding: 8px;
  border-bottom: 1px solid var(--line);
}

.npc-selector button {
  width: 100%;
  display: grid;
  grid-template-columns: 33px 1fr;
  gap: 9px;
  align-items: center;
  padding: 8px;
  border: 1px solid transparent;
  color: var(--muted);
  text-align: left;
  background: transparent;
  border-radius: 5px;
  cursor: pointer;
}

.npc-selector button:hover,
.npc-selector button.active {
  border-color: var(--line);
  background: rgba(255, 255, 255, 0.025);
}

.npc-selector button.active {
  border-color: rgba(93, 183, 176, 0.35);
  color: var(--text);
}

.npc-avatar {
  width: 33px;
  height: 33px;
  display: grid;
  place-items: center;
  border-radius: 5px;
  color: #dce9e7;
  background: linear-gradient(145deg, #2e5554, #69453f);
  font-size: 12px;
}

.npc-selector strong,
.npc-selector small {
  display: block;
}

.npc-selector strong {
  font-size: 12px;
}

.npc-selector small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--faint);
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.npc-identity {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 13px;
  color: var(--cyan);
  border-bottom: 1px solid var(--line);
}

.npc-identity strong,
.npc-identity small {
  display: block;
}

.npc-identity strong {
  color: var(--text);
  font-size: 11px;
}

.npc-identity small {
  margin-top: 2px;
  color: var(--faint);
  font-size: 9px;
}

.message-stream {
  min-height: 280px;
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}

.terminal-empty {
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--faint);
}

.terminal-empty strong {
  margin-top: 10px;
  color: var(--muted);
  font-size: 13px;
}

.terminal-empty p {
  max-width: 310px;
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.7;
}

.message {
  max-width: 92%;
  margin-bottom: 12px;
  animation: message-in 0.25s ease;
}

.message.user {
  margin-left: auto;
}

.message-role {
  display: block;
  margin-bottom: 4px;
  color: var(--faint);
  font-size: 9px;
}

.message.user .message-role {
  text-align: right;
}

.message p {
  margin: 0;
  padding: 10px 11px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.025);
  font-size: 11px;
  line-height: 1.68;
  white-space: pre-wrap;
}

.message.user p {
  color: #cce6e2;
  border-color: rgba(93, 183, 176, 0.24);
  background: var(--cyan-soft);
}

.typing {
  display: flex;
  gap: 4px;
  padding: 11px;
}

.typing span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--cyan);
  animation: blink 1s infinite alternate;
}

.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }

.ai-notice {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: 0 10px 8px;
  padding: 8px;
  color: #e4bd7f;
  border: 1px solid rgba(204, 155, 87, 0.24);
  background: var(--amber-soft);
  border-radius: 5px;
  font-size: 9px;
  line-height: 1.5;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 5px;
  padding: 8px 10px;
  border-top: 1px solid var(--line);
}

.quick-grid button {
  padding: 6px 4px;
  border: 1px solid var(--line);
  color: var(--faint);
  background: rgba(255, 255, 255, 0.02);
  border-radius: 4px;
  cursor: pointer;
  font-size: 9px;
}

.quick-grid button:hover {
  color: var(--cyan);
  border-color: rgba(93, 183, 176, 0.3);
}

.terminal-input {
  padding: 10px;
  border-top: 1px solid var(--line);
  background: rgba(7, 10, 12, 0.48);
}

.terminal-input textarea {
  width: 100%;
  resize: none;
  padding: 10px;
  border: 1px solid var(--line);
  color: var(--text);
  outline: 0;
  background: rgba(7, 10, 12, 0.72);
  border-radius: 5px;
  font-size: 11px;
  line-height: 1.5;
}

.terminal-input textarea:focus {
  border-color: rgba(93, 183, 176, 0.5);
}

.terminal-input button {
  width: 100%;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 7px;
  border: 1px solid rgba(93, 183, 176, 0.42);
  color: #bce9e4;
  background: var(--cyan-soft);
  border-radius: 5px;
  cursor: pointer;
}

.terminal-input button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

@keyframes message-in {
  from { opacity: 0; transform: translateY(5px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes blink {
  from { opacity: 0.2; transform: translateY(0); }
  to { opacity: 1; transform: translateY(-2px); }
}
</style>
