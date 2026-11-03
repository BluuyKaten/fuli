import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'ant-design-vue/dist/reset.css'
import './styles/tokens.css'
import './styles/global.css'
import App from './App.vue'
import router from './router'
import { injectThemeVars } from './theme/tokens'

// 令牌先落地，保证首帧就是新配色（tokens.css 只做兜底）
injectThemeVars()

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
