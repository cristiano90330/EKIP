/** 初始化 Vue、状态存储、路由和主题，挂载产品页面。 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  CollapsePanel,
  Dialog,
  Dropdown,
  Input,
  Popconfirm,
  Radio,
  RadioGroup,
  Tag,
  Textarea,
} from 'tdesign-vue-next'
import 'tdesign-vue-next/es/style/index.css'
// 样式分层：令牌 → 基础层 → TDesign 桥接 → 页面共用件。顺序不可调换：
// 后三者都依赖 tokens.css 的语义变量，而桥接层必须晚于组件库自身样式才能覆盖生效。
import './styles/tokens.css'
import './styles/base.css'
import './styles/tdesign.css'
import './styles/chat.css'
import App from './App.vue'
import router from './router'
import { useAuth } from './stores/auth'
import { initTheme } from './theme'

// 主题跟随系统：必须在挂载前设置，否则首帧 TDesign 会先按亮色渲染一瞬
initTheme()

const app = createApp(App)
app.use(createPinia())
app.use(router)
// 各页共用的组件在这里注册，较重的库页和 Agent 控件由对应路由引入。
for (const component of [
  Alert, Button, Checkbox, Collapse, CollapsePanel, Dialog,
  Dropdown, Input, Popconfirm, Radio, RadioGroup, Tag, Textarea,
]) app.use(component)
app.mount('#app')

// 跨标签页令牌同步：必须在 pinia 装好之后调用（store 需要活跃的 pinia 实例）
useAuth().startStorageSync()
