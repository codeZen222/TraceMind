import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts',
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    /**
     * 把 REST 和 WebSocket 都代理到 Java 后端（localhost:8080）。
     *
     * 为什么必须加：.env.development 里 VITE_API_BASE=/api 是【相对路径】，
     * 没有代理的话请求会打到 http://127.0.0.1:5173/api/... 然后 404。
     * 有了代理后浏览器视角是同源请求，连 CORS 都不会触发——
     * 后端那份 CORS 配置留着，是给 Postman / 直连 8080 调试兜底的。
     *
     * 路径不用 rewrite：后端接口本身就带 /api 前缀（/api/projects 等）。
     *
     * /ws 这条代理目前用不上——stores/diagnosis.ts 是拿 VITE_WS_BASE
     * 直连 ws://127.0.0.1:8080 的。留着是为了以后想改成同源连接时
     * 把 VITE_WS_BASE 换成 ws://127.0.0.1:5173 就能走通。
     */
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts', 'vue-echarts'],
          highlight: ['highlight.js'],
        },
      },
    },
  },
})
