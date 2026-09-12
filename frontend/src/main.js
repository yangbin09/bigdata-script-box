import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

// Icons are registered by name so templates (and the string-based
// `<component :is="'Promotion'">` lookups in AppLayout's nav config) can use
// them without importing each one. Only the icons this app actually renders are
// registered: `import * as ElementPlusIconsVue` + a registration loop defeats
// tree-shaking and pulled all ~293 icons (~200 KB minified) into the entry chunk.
//
// When you add an `<ElIconName />` tag to a template, either import the icon in
// that component (preferred) or add its name here.
import {
  Bottom, CircleCheck, CircleClose, Clock, Close, Connection, CopyDocument,
  Delete, Document, DocumentCopy, Download, Edit, EditPen, Failed, Files,
  InfoFilled, Loading, MoreFilled, Open, Plus, Promotion, QuestionFilled,
  Refresh, RefreshLeft, RefreshRight, Right, Search, Select, Setting, Timer,
  Tools, Top, UploadFilled, User, VideoPlay, View, Warning, WarningFilled
} from '@element-plus/icons-vue'

const app = createApp(App)

// Element Plus default size; we keep a flat, dense dev-tool aesthetic.
app.use(ElementPlus, { size: 'default' })
app.use(createPinia())
app.use(router)

for (const icon of [
  Bottom, CircleCheck, CircleClose, Clock, Close, Connection, CopyDocument,
  Delete, Document, DocumentCopy, Download, Edit, EditPen, Failed, Files,
  InfoFilled, Loading, MoreFilled, Open, Plus, Promotion, QuestionFilled,
  Refresh, RefreshLeft, RefreshRight, Right, Search, Select, Setting, Timer,
  Tools, Top, UploadFilled, User, VideoPlay, View, Warning, WarningFilled
]) {
  app.component(icon.name, icon)
}

app.mount('#app')
