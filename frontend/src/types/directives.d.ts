import type { vReveal } from '@/directives/reveal'

/**
 * 全局指令 v-reveal 的类型声明。
 * 在 main.ts 里通过 app.directive('reveal', vReveal) 注册，
 * 这里补上类型，模板里写错参数时 vue-tsc 才能报出来。
 */
declare module 'vue' {
  interface GlobalDirectives {
    vReveal: typeof vReveal
  }
}

export {}
