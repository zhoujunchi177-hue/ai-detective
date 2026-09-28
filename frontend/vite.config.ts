import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：只打包实际用到的组件与 API，避免整库进入产物。
    // 模板里的 <el-input /> 等由 Components 解析；ElMessage 等函数式 API 由 AutoImport 解析。
    // 自动生成的类型声明放在 src/types/ 下，便于 IDE 提示，无需改动 tsconfig。
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 只把必然整体用到的运行时依赖单独成块，便于并行加载与长期缓存。
        // 注意：不要把 element-plus 整体列进来，否则会绕过 tree-shaking，
        // 让按需引入失效（实测会把 900KB+ 全量打进产物）。
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
        },
      },
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  // 用 `npm run preview` 本地预览生产构建时，同样把 /api 代理到后端。
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
