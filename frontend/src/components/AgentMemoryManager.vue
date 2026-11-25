<!-- 展示当前账号的 Agent 长期记忆并提供编辑和删除操作。 -->

<script setup lang="ts">
import { Select as TSelect } from 'tdesign-vue-next'
import { computed, ref, watch } from 'vue'
import {
  listAgentMemories, correctAgentMemory, deleteAgentMemory,
  type AgentMemoryDTO, type AgentMemoryKind, type AgentMemoryListDTO,
} from '../api/agent'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [value: boolean] }>()
const dialogVisible = computed({ get: () => props.visible, set: (value: boolean) => { if (!saving.value) emit('update:visible', value) } })
const data = ref<AgentMemoryListDTO | null>(null)
const loading = ref(false)
const saving = ref(false)
const errorText = ref('')
const editingId = ref<string | null>(null)
const content = ref('')
const kind = ref<AgentMemoryKind>('FACT')
let loadVersion = 0

watch(() => props.visible, (visible) => { if (visible) void load() })

/** 从失败对象提取用户可读消息，缺少消息时使用当前操作提示。接收失败原因，返回文本。由 load、save 触发。 */
function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : '长期记忆操作失败，请重试。'
}

/** 加载长期记忆列表并重置已失效的编辑选择。由 当前模板的 @click 事件 触发。下游调用 agent 的 listAgentMemories、messageOf。 */
async function load() {
  const version = ++loadVersion
  loading.value = true
  errorText.value = ''
  data.value = null
  editingId.value = null
  try {
    const result = await listAgentMemories()
    if (version === loadVersion) data.value = result
  } catch (cause) {
    if (version === loadVersion) errorText.value = messageOf(cause)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

/** 把选中的记忆内容和类型装入编辑表单。接收item。由 当前模板的 @click 事件 触发。 */
function edit(item: AgentMemoryDTO) {
  editingId.value = item.id
  content.value = item.content
  kind.value = item.kind
  errorText.value = ''
}

/** 提交修改并以服务端返回的记忆替换列表项。由 当前模板的 @click 事件 触发。下游调用 agent 的 correctAgentMemory、messageOf。 */
async function save() {
  if (saving.value || !data.value?.enabled || !editingId.value) return

  const text = content.value.trim()

  if (!text || text.length > data.value.maxContentChars) {
    errorText.value = `内容须为 1 到 ${data.value.maxContentChars} 个字符。`
    return
  }
  saving.value = true
  errorText.value = ''
  try {
    const corrected = await correctAgentMemory(editingId.value, { content: text, kind: kind.value })
    data.value.items = data.value.items.map((item) => item.id === corrected.id ? corrected : item)
    editingId.value = null
  } catch (cause) { errorText.value = messageOf(cause) }
  finally { saving.value = false }
}

/**
 * 删除指定记忆并退出对应的编辑状态。
 * 接收记录 ID。
 * 由 当前模板的 @confirm 事件 触发。
 * 下游调用 agent 的 deleteAgentMemory、messageOf。
 */
async function remove(id: string) {
  if (saving.value || !data.value) return
  saving.value = true
  errorText.value = ''
  try {
    await deleteAgentMemory(id)
    data.value.items = data.value.items.filter((item) => item.id !== id)
    if (editingId.value === id) editingId.value = null
  } catch (cause) { errorText.value = messageOf(cause) }
  finally { saving.value = false }
}
</script>

<template>
  <t-dialog v-model:visible="dialogVisible" header="管理长期记忆" :footer="false" width="720px" :close-on-overlay-click="false">
    <div class="memory-manager">
      <p class="hint">这些条目来自用户明确要求保存的自述，可能已过期；与知识库冲突时以知识库为准。纠正和删除会影响后续回合，已有会话文字仍保留。</p>
      <t-alert v-if="data && !data.enabled" theme="warning">长期记忆已全局停用，模型不会读取或保存；已有数据仍保留，可查看和删除。纠正写入已禁用。</t-alert>
      <p v-if="data" class="hint">{{ data.items.length }} / {{ data.maxItems }} 条，每条最多 {{ data.maxContentChars }} 字符。在对话中以“记住：内容”或“请记住：内容”明确保存。</p>
      <t-alert v-if="errorText" theme="error">{{ errorText }}</t-alert>
      <t-button variant="outline" size="small" :loading="loading" :disabled="saving" @click="load">刷新列表</t-button>
      <p v-if="data && !data.items.length" class="hint">暂无已保存的长期记忆。</p>
      <div class="memory-list">
        <article v-for="item in data?.items ?? []" :key="item.id" class="memory-item">
          <template v-if="editingId === item.id">
            <t-select v-model:value="kind" :disabled="saving" :options="[{ value: 'FACT', label: '事实' }, { value: 'PREFERENCE', label: '偏好' }, { value: 'PROFILE', label: '用户资料' }]" />
            <t-textarea v-model:value="content" :maxlength="data?.maxContentChars ?? 500" :disabled="saving" :autosize="{ minRows: 2, maxRows: 6 }" />
            <div class="actions">
              <t-button size="small" :loading="saving" :disabled="!data?.enabled" @click="save">保存纠正</t-button>
              <t-button size="small" variant="text" :disabled="saving" @click="editingId = null">取消</t-button>
            </div>
          </template>
          <template v-else>
            <t-tag size="small" variant="light-outline">{{ item.kind }} · 用户自述，可能过期</t-tag>
            <p class="content">{{ item.content }}</p>
            <small class="hint">保存于 {{ new Date(item.createdAt).toLocaleString() }}<span v-if="item.updatedAt"> · 纠正于 {{ new Date(item.updatedAt).toLocaleString() }}</span></small>
            <div class="actions">
              <t-button size="small" variant="text" :disabled="saving || !data?.enabled" @click="edit(item)">纠正</t-button>
              <t-popconfirm content="删除这条记忆后无法恢复，确定删除？" @confirm="remove(item.id)">
                <t-button size="small" variant="text" theme="danger" :disabled="saving">删除</t-button>
              </t-popconfirm>
            </div>
          </template>
        </article>
      </div>
    </div>
  </t-dialog>
</template>

<style scoped>
.memory-manager,
.memory-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.memory-list {
  display: grid;
  gap: var(--space-2);
  max-height: 55vh;
  overflow: auto;
}

.memory-item {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--bg-inset);
}

.hint {
  margin: 0;
  color: var(--text-dim);
  font-size: var(--fs-sm);
  line-height: var(--lh-base);
}

.content {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.actions {
  display: flex;
  gap: var(--space-2);
}
</style>
