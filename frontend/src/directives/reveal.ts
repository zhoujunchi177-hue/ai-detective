import type { Directive, DirectiveBinding } from 'vue'

/**
 * v-reveal —— 滚动进入视口时淡入上移。
 *
 * 为什么用 IntersectionObserver 而不是监听 scroll 事件：
 * scroll 回调在主线程上每帧都要跑，列表一长就会掉帧；IntersectionObserver 由浏览器在
 * 合成线程上判定，回调只在「进入/离开」时触发一次。
 *
 * 用法：
 *   <div v-reveal>…</div>                 立即触发
 *   <div v-reveal="120">…</div>           延迟 120ms（做逐项错峰）
 *   <div v-reveal="{ delay: 120 }">…</div>
 *
 * 只播一次：进入视口后立刻 unobserve，来回滚动不会反复闪。
 */

export type RevealValue = number | { delay?: number } | undefined

const REVEAL_CLASS = 'reveal'
const REVEAL_VISIBLE_CLASS = 'reveal--visible'

/** 进入视口后延迟多久开始播（毫秒），挂在元素上等回调时再读。 */
const delays = new WeakMap<Element, number>()

let observer: IntersectionObserver | null = null

function prefersReducedMotion() {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

function ensureObserver(): IntersectionObserver {
  if (observer) return observer
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue
        const element = entry.target as HTMLElement
        // 底部留 12% 余量：元素刚探进屏幕一点点就开始播，不要等它完全露出来
        element.style.setProperty('--reveal-delay', `${delays.get(element) ?? 0}ms`)
        element.classList.add(REVEAL_VISIBLE_CLASS)
        observer?.unobserve(element)
        delays.delete(element)
      }
    },
    { threshold: 0.08, rootMargin: '0px 0px -12% 0px' },
  )
  return observer
}

function resolveDelay(binding: DirectiveBinding<RevealValue>) {
  const value = binding.value
  if (typeof value === 'number') return Number.isFinite(value) ? Math.max(0, value) : 0
  if (value && typeof value.delay === 'number' && Number.isFinite(value.delay)) {
    return Math.max(0, value.delay)
  }
  return 0
}

/** 直接显示，不做动画。用于「减少动态效果」偏好和浏览器不支持 IntersectionObserver 的兜底。 */
function showImmediately(element: HTMLElement) {
  element.classList.add(REVEAL_CLASS, REVEAL_VISIBLE_CLASS)
}

export const vReveal: Directive<HTMLElement, RevealValue> = {
  mounted(element, binding) {
    // 无障碍优先：用户明确要求减少动态效果时，内容必须立刻可见，而不是藏在 opacity:0 后面
    if (prefersReducedMotion() || typeof IntersectionObserver === 'undefined') {
      showImmediately(element)
      return
    }
    element.classList.add(REVEAL_CLASS)
    delays.set(element, resolveDelay(binding))
    ensureObserver().observe(element)
  },
  updated(element, binding) {
    // 已经播过的元素不再改延迟，避免滚动中反复触发样式变更
    if (element.classList.contains(REVEAL_VISIBLE_CLASS)) return
    delays.set(element, resolveDelay(binding))
  },
  unmounted(element) {
    observer?.unobserve(element)
    delays.delete(element)
  },
}

export default vReveal
