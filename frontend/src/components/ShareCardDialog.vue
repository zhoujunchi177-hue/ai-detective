<script setup lang="ts">
import { ref, watch } from 'vue'
import { Download, ImageDown, LoaderCircle } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import {
  downloadBlob,
  renderShareCard,
  shareCardBlob,
  shareCardFilename,
  type ShareCardBadge,
} from '@/utils/share-card'

const props = defineProps<{
  modelValue: boolean
  nickname: string
  userId: number
  level: number
  badges: ShareCardBadge[]
  totalBadges: number
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const previewUrl = ref('')
const previewSize = ref('')
const loading = ref(false)
const error = ref('')
let blob: Blob | null = null

function releasePreview() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  blob = null
  previewSize.value = ''
}

/**
 * 每次打开都重新画。看起来有点浪费，但这样图里的数字一定和当前进度一致 ——
 * 缓存下来的旧图被当成「现在的进度」转发出去，比多画一次糟糕得多。
 */
async function build() {
  loading.value = true
  error.value = ''
  releasePreview()
  try {
    const canvas = renderShareCard({
      nickname: props.nickname,
      userId: props.userId,
      level: props.level,
      badges: props.badges,
      totalBadges: props.totalBadges,
    })
    blob = await shareCardBlob(canvas)
    previewUrl.value = URL.createObjectURL(blob)
    previewSize.value = `${Math.round(blob.size / 1024)} KB`
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void build()
    } else {
      releasePreview()
    }
  },
)

function download() {
  if (!blob) {
    ElMessage.error('分享图还没生成好，请稍候。')
    return
  }
  downloadBlob(blob, shareCardFilename(props.nickname))
  ElMessage.success('分享图已下载')
}

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    width="min(880px, 94vw)"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="share-title">
        <ImageDown :size="20" />
        <span>
          <strong>成就分享图</strong>
          <small>在本地绘制并导出 PNG，不上传任何数据</small>
        </span>
      </div>
    </template>

    <div class="share-body">
      <div v-if="loading" class="share-loading">
        <LoaderCircle :size="26" class="spin" />
        <span>正在绘制…</span>
      </div>
      <p v-else-if="error" class="share-error">{{ error }}</p>
      <img v-else-if="previewUrl" class="share-preview" :src="previewUrl" alt="成就分享图预览" />
    </div>

    <div class="share-actions">
      <span v-if="previewSize" class="share-meta">
        1200 × 720 PNG · {{ previewSize }}
      </span>
      <el-button @click="close">关闭</el-button>
      <el-button type="primary" :disabled="!previewUrl" @click="download">
        <Download :size="14" />
        下载 PNG
      </el-button>
    </div>
  </el-dialog>
</template>

<style scoped>
.share-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--cyan);
}

.share-title strong,
.share-title small {
  display: block;
}

.share-title strong {
  color: var(--text);
  font-size: 16px;
}

.share-title small {
  margin-top: 3px;
  color: var(--faint);
  font-size: 10px;
}

.share-body {
  display: grid;
  place-items: center;
  min-height: 240px;
}

.share-preview {
  width: 100%;
  display: block;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.share-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--muted);
  font-size: 12px;
}

.share-error {
  color: var(--red);
  font-size: 12px;
}

.share-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.share-meta {
  margin-right: auto;
  color: var(--faint);
  font-family: "Cascadia Mono", monospace;
  font-size: 10px;
}

.spin {
  animation: share-spin 1s linear infinite;
}

@keyframes share-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
