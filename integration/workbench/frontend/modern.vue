<template>
  <section class="modern-workspace">
    <header v-if="selectedKey" class="modern-toolbar">
      <div class="modern-current">
        <strong>{{ selectedKey.split('/').slice(-1)[0] }}</strong>
        <span :title="selectedKey">{{ selectedKey }}</span>
      </div>
      <div class="modern-toolbar-actions">
        <el-button type="primary" @click="saveEditor">保存</el-button>
        <el-button @click="openFilePicker">打开其他流程</el-button>
        <el-button :loading="loading" @click="reloadEditor">刷新</el-button>
      </div>
    </header>

    <div v-if="errorMessage" class="modern-error" role="alert">
      <span>{{ errorMessage }}</span>
      <el-button link type="primary" :loading="loading" @click="retryError">重试</el-button>
    </div>

    <div v-if="!selectedKey" class="modern-empty">
      <div class="modern-empty-icon"><el-icon :size="32"><FolderOpened /></el-icon></div>
      <strong>打开数据流程</strong>
      <p>从工作台已配置的 Hop 项目中选择流程，查看真实节点与连线。</p>
      <el-button type="primary" size="large" :loading="loading" @click="openFilePicker">
        打开流程
      </el-button>
    </div>

    <div v-show="Boolean(selectedKey)" ref="editorHost" class="modern-editor-host" />

    <el-dialog v-model="filePickerVisible" title="打开数据流程" width="min(680px, 94vw)" append-to-body>
      <el-input v-model="fileFilter" clearable placeholder="搜索项目、文件夹或 .hpl 文件名" />
      <div class="modern-picker-list" v-loading="loading">
        <el-tree
          v-if="!loading && treeData.length"
          :key="fileFilter + ':' + catalogVersion"
          :data="treeData"
          node-key="id"
          :default-expanded-keys="expandedTreeKeys"
          :expand-on-click-node="true"
          :highlight-current="true"
          @node-click="handleTreeNodeClick"
        >
          <template #default="{ data }">
            <span class="modern-tree-node" :class="{ 'modern-tree-file': data.kind === 'file' }" :title="data.label">
              <el-icon v-if="data.kind === 'file'"><Document /></el-icon>
              <el-icon v-else><Folder /></el-icon>
              <span class="modern-tree-label">{{ data.label }}</span>
            </span>
          </template>
        </el-tree>
        <div v-else-if="!loading" class="modern-picker-empty">
          {{ errorMessage || (fileFilter ? '没有匹配的流程' : '当前项目目录没有可选的 .hpl 文件') }}
          <div><el-button link type="primary" @click="loadFiles">重新读取目录</el-button></div>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, nextTick, ref, watch } from 'vue'
import { FolderOpened, Folder, Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { buildHopFileTree, expandedHopTreeKeys } from '@/utils/hopFileTree'

const route = useRoute()
const files = ref([])
const loading = ref(false)
const errorMessage = ref('')
const errorSource = ref('')
const selectedKey = ref('')
const editorHost = ref(null)
const filePickerVisible = ref(false)
const fileFilter = ref('')
const catalogVersion = ref(0)
const treeData = computed(() => buildHopFileTree(files.value, fileFilter.value))
const expandedTreeKeys = computed(() => expandedHopTreeKeys(treeData.value, Boolean(fileFilter.value.trim())))
let editorElement

async function initializeEditor() {
  if (editorElement) return
  // Import the graph editor only when a real file was selected.
  const editorModuleUrl = '/workbench-modern/editor.js'
  await import(/* @vite-ignore */ editorModuleUrl)
  if (!customElements.get('hop-workbench-editor')) {
    throw new Error('编辑器脚本已加载，但自定义组件未注册')
  }
  await nextTick()
  if (!editorHost.value) throw new Error('编辑器容器不存在')
  editorElement = document.createElement('hop-workbench-editor')
  editorElement.style.cssText = 'display:block;width:100%;height:100%'
  editorElement.addEventListener('workbench-modern-save-result', onSaveResult)
  editorHost.value.appendChild(editorElement)
}

function saveEditor() {
  if (!editorElement?.save) {
    ElMessage.warning('编辑器尚未准备好，请稍后重试')
    return
  }
  editorElement.save()
}
function onSaveResult(event) {
  const { ok, message } = event.detail || {}
  if (ok) ElMessage.success(message || '保存成功')
  else ElMessage.error(message || '流程保存失败')
}

async function openSelected() {
  if (!selectedKey.value) return
  try {
    await initializeEditor()
    if (editorElement) {
      if (editorElement.getAttribute('path') === selectedKey.value) editorElement.reload?.()
      else editorElement.setAttribute('path', selectedKey.value)
    }
  } catch (error) {
    console.error('[Workbench] Graph editor loading failed', error)
    errorSource.value = 'editor'
    const detail = error instanceof Error ? error.message : String(error)
    errorMessage.value = '流程编辑器组件加载失败：' + detail.slice(0, 180)
  }
}

function retryError() {
  if (errorSource.value === 'editor' && selectedKey.value) {
    errorMessage.value = ''
    void openSelected()
  } else {
    void loadFiles()
  }
}

function chooseFile(file) {
  selectedKey.value = file.project + '/' + file.file
  filePickerVisible.value = false
  errorMessage.value = ''
  void openSelected()
}

function handleTreeNodeClick(node) {
  // Folder/project clicks only expand; only pipeline leaves open the editor.
  if (node.kind === 'file' && node.file) chooseFile(node.file)
}

function openFilePicker() {
  fileFilter.value = ''
  filePickerVisible.value = true
  // Always read the live catalog when opening, even if a previous list exists.
  // Pipelines created directly in Hop Web should appear without page refresh.
  void loadFiles()
}

function matchLinkedFile() {
  const project = typeof route.query.project === 'string' ? route.query.project : ''
  const file = typeof route.query.file === 'string' ? route.query.file : ''
  const key = project && file && file.toLowerCase().endsWith('.hpl') ? project + '/' + file : ''
  if (key && files.value.some(row => row.project + '/' + row.file === key)) {
    if (selectedKey.value !== key) {
      selectedKey.value = key
      void openSelected()
    }
  }
}

function catalogError(error) {
  const message = String(error?.message || error || '')
  const status = error?.response?.status
  if (status === 403 || /无权限|权限不足|forbidden|403/i.test(message)) {
    return '当前工作台账号没有读取流程目录的权限，请检查 Workbench 数据开发权限。'
  }
  if (status === 401 || /会话已过期|无效的会话|401/i.test(message)) {
    return '工作台会话已过期，请重新进行工作台统一身份认证。'
  }
  if (/timeout|超时/i.test(message)) return '工作台文件目录接口响应超时，请稍后重试。'
  return '工作台文件目录接口请求失败；这与 Hop Web 的登录状态无关。'
}

async function loadFiles() {
  if (loading.value) return
  loading.value = true
  errorMessage.value = ''
  errorSource.value = 'catalog'
  files.value = []
  try {
    const response = await request({
      url: '/workbench/hop-files',
      method: 'get',
      params: { _: Date.now() }
    })
    const catalog = response?.data || {}
    files.value = Array.isArray(catalog.files)
      ? catalog.files.filter(f => f && f.type === 'pipeline' && typeof f.project === 'string' && typeof f.file === 'string')
      : []
    if (catalog.available === false) {
      errorMessage.value = 'Workbench 后端无法访问 Hop 项目目录；Hop Web 本身无需重新登录。'
    } else if (files.value.length === 0) {
      errorMessage.value = 'Workbench 可访问项目目录，但未找到可打开的 .hpl 流程。'
    }
    matchLinkedFile()
  } catch (error) {
    errorMessage.value = catalogError(error)
  } finally {
    catalogVersion.value += 1
    loading.value = false
  }
}

function reloadEditor() {
  if (editorElement && selectedKey.value) editorElement.reload?.()
}

// A direct deep link to a pipeline still needs one catalog lookup. Ordinary
// visits do not fetch anything until the user clicks "打开流程".
function hasLinkedFile() {
  return typeof route.query.project === 'string' && typeof route.query.file === 'string' &&
    route.query.file.toLowerCase().endsWith('.hpl')
}
watch(() => [route.query.project, route.query.file], () => {
  if (hasLinkedFile()) void loadFiles()
})
onMounted(() => { if (hasLinkedFile()) void loadFiles() })
onBeforeUnmount(() => {
  editorElement?.removeEventListener('workbench-modern-save-result', onSaveResult)
  editorElement?.remove()
  editorElement = undefined
})
</script>

<style scoped>
.modern-workspace{display:flex;flex-direction:column;height:100%;min-height:0;background:#f5f7fb;color:#253a52;font-family:"Microsoft YaHei UI","PingFang SC",sans-serif}
.modern-toolbar{min-height:58px;flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:8px 22px;background:#fff;border-bottom:1px solid #e7edf4}
.modern-current{display:flex;flex-direction:column;gap:3px;min-width:0}
.modern-current strong{font-size:14px}
.modern-current span{color:#8898a9;font-size:12px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;max-width:60vw}
.modern-toolbar-actions{display:flex;gap:8px;flex-shrink:0}
.modern-empty{display:flex;flex-direction:column;align-items:center;gap:14px;max-width:520px;margin:auto;padding:36px 24px;text-align:center}
.modern-empty-icon{width:76px;height:76px;display:grid;place-items:center;border-radius:22px;background:#e7f0ff;color:#3774c1}
.modern-empty strong{font-size:21px;font-weight:600}
.modern-empty p{font-size:13px;color:#8190a4;line-height:1.7;margin:0 0 8px}
.modern-error{display:flex;align-items:center;gap:18px;justify-content:space-between;padding:9px 20px;font-size:12px;color:#ac5040;background:#fff1ed}
.modern-editor-host{flex:1;min-height:0;overflow:hidden}
.modern-picker-list{max-height:380px;overflow:auto;margin-top:12px;border:1px solid #e5eaf1;border-radius:9px}
.modern-tree-node{display:flex;align-items:center;gap:9px;min-width:0;width:100%;font-size:13px;color:#38516b}
.modern-tree-node .el-icon{flex-shrink:0;color:#6489b3}
.modern-tree-node.modern-tree-file{color:#294a79;cursor:pointer}
.modern-tree-file .el-icon{color:#4382d3}
.modern-tree-label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.modern-picker-list :deep(.el-tree-node__content){height:36px;border-radius:5px}
.modern-picker-list :deep(.el-tree-node__content:hover){background:#edf5ff}
.modern-picker-list :deep(.el-tree){padding:8px}
.modern-picker-empty{padding:30px 18px;text-align:center;color:#8b98a9;font-size:13px}
@media(max-width:700px){.modern-toolbar{padding:8px 12px}.modern-current span{max-width:44vw}}
</style>