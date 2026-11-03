import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import Components from 'unplugin-vue-components/vite'
import { AntDesignVueResolver } from 'unplugin-vue-components/resolvers'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // ant-design-vue 按需引入：根据模板中实际用到的 <a-xxx> 组件自动导入，
    // 不再 app.use(Antd) 全量注册，显著减小 vendor 体积。
    Components({
      resolvers: [
        AntDesignVueResolver({ importStyle: 'css-in-js' })
      ]
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 把体积大且改动频率低的三方库拆成独立 chunk，利于浏览器长效缓存，
    // 也避免它们被全部打进入口 index.js。
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-http': ['axios', 'dayjs'],
          'vendor-antd': ['ant-design-vue'],
          'vendor-icons': ['@ant-design/icons-vue'],
          'vendor-echarts': ['echarts'],
          'vendor-charts': ['lightweight-charts'],
          'vendor-utils': ['gsap', 'html2canvas']
        }
      }
    },
    // echarts 单库约 530KB（minified），属三方库正常体积，放宽阈值。
    chunkSizeWarningLimit: 1000
  }
})
