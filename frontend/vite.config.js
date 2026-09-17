import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：只打包模板里真正用到的 el-* 组件，样式随之一起引入。
    // 此前是 main.js 里 app.use(ElementPlus) 全量注册，主包 1179.7KB。
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  server: {
    // 5173 在本机已被其他项目占用，固定用一个冷门端口，避免被自动换掉后找不到地址
    port: 5273,
    strictPort: true,
    open: false,
    proxy: {
      // 开发期由 Vite 代理到后端，前端只请求同源的 /api，天然没有跨域问题
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
