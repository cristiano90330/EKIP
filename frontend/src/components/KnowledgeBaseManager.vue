<!-- 为知识库所有者提供资料编辑、成员授权和整库删除弹窗。 -->

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import {
  addKnowledgeBaseMember,
  deleteKnowledgeBase,
  listKnowledgeBaseMembers,
  removeKnowledgeBaseMember,
  searchKnowledgeBaseUsers,
  updateKnowledgeBase,
  type KnowledgeBaseMember,
  type KnowledgeBaseRemoval,
  type KnowledgeBaseUser,
} from '../api/library'
import type { KnowledgeBaseDTO } from '../types'

const props = defineProps<{
  knowledgeBase: KnowledgeBaseDTO
  isOwner: boolean
  deletionBlocked: boolean
}>()
const emit = defineEmits<{
  updated: [id: number, name: string, description: string]
  removed: [id: number, name: string, result: KnowledgeBaseRemoval]
}>()

type Dialog = 'details' | 'sharing' | 'delete'
const dialog = ref<Dialog | null>(null)
const modal = ref<HTMLDialogElement | null>(null)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const name = ref('')
const description = ref('')
const confirmation = ref('')
const members = ref<KnowledgeBaseMember[] | null>(null)
const loadingMembers = ref(false)
const memberError = ref('')
const searchName = ref('')
const searchResults = ref<KnowledgeBaseUser[] | null>(null)
const searching = ref(false)
const pendingRemoval = ref<KnowledgeBaseMember | null>(null)

const titles: Record<Dialog, string> = { details: '编辑知识库', sharing: '管理共享', delete: '删除知识库' }
watch(dialog, async () => {
  await nextTick()
  const element = modal.value
  if (!element) return
  if (dialog.value && !element.open) element.showModal()
  else if (!dialog.value && element.open) element.close()
})
const sharingStatus = computed(() => {
  if (!props.isOwner) return '与我共享 · 只读'
  if (loadingMembers.value) return '正在读取共享状态'
  if (members.value === null) return '共享状态暂不可用'
  return members.value.length ? `已共享 · ${members.value.length} 位成员` : '仅自己可见'
})
const candidates = computed(() => {
  if (searchResults.value === null) return null
  const existing = new Set(members.value?.map(member => String(member.userId)))
  return searchResults.value.filter(user => String(user.id) !== String(props.knowledgeBase.creatorId)
    && !existing.has(String(user.id)))
})

/** 从当前异常中选择可展示的消息。接收失败原因、兜底文案。由 loadMembers、saveDetails 触发。 */
function message(cause: unknown, fallback: string) {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

/** 读取当前库的共享成员并刷新弹窗列表。由 open、changeMember 触发。下游调用 library 的 listKnowledgeBaseMembers、message。 */
async function loadMembers() {
  if (!props.isOwner || loadingMembers.value) return
  loadingMembers.value = true
  memberError.value = ''
  try {
    members.value = await listKnowledgeBaseMembers(props.knowledgeBase.id)
  } catch (cause) {
    members.value = null
    memberError.value = message(cause, '无法读取成员名单，请重试。')
  } finally {
    loadingMembers.value = false
  }
}

/**
 * 按管理模式初始化弹窗和当前库的资料。
 * 接收mode。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 loadMembers。
 */
function open(mode: Dialog) {
  if (!props.isOwner || saving.value) return
  error.value = ''
  notice.value = ''
  dialog.value = mode
  name.value = props.knowledgeBase.name
  description.value = props.knowledgeBase.description ?? ''
  confirmation.value = ''
  searchName.value = ''
  searchResults.value = null
  pendingRemoval.value = null
  if (mode === 'sharing') void loadMembers()
}

/** 清除当前弹窗模式。由 当前模板的 @close 事件 触发。 */
function closeDialog() {
  if (!saving.value) dialog.value = null
}

/** 处理原生取消事件并保护尚在提交的操作。接收页面事件。由 当前模板的 @cancel 事件 触发。 */
function onDialogCancel(event: Event) {
  if (saving.value) event.preventDefault()
}

/** 提交库名和描述，并把更新事件发给文档主页。由 当前模板的 @submit.prevent 事件 触发。下游调用 library 的 updateKnowledgeBase、message。 */
async function saveDetails() {
  const title = name.value.trim()
  const summary = description.value.trim()
  if (!props.isOwner || saving.value || !title) return
  saving.value = true
  error.value = ''
  try {
    await updateKnowledgeBase(props.knowledgeBase.id, title, summary)
    emit('updated', props.knowledgeBase.id, title, summary)
    dialog.value = null
  } catch (cause) {
    error.value = message(cause, '知识库更新失败，请重试。')
  } finally {
    saving.value = false
  }
}

/** 按输入的用户名读取成员候选。由 当前模板的 @submit.prevent 事件 触发。下游调用 library 的 searchKnowledgeBaseUsers、message。 */
async function search() {
  const username = searchName.value.trim()
  if (!props.isOwner || searching.value || saving.value || !username) return
  searching.value = true
  searchResults.value = null
  error.value = ''
  notice.value = ''
  try {
    const found = await searchKnowledgeBaseUsers(username)
    if (dialog.value === 'sharing' && searchName.value.trim() === username) searchResults.value = found
  } catch (cause) {
    if (dialog.value === 'sharing') error.value = message(cause, '用户查找失败，请重试。')
  } finally {
    searching.value = false
  }
}

/**
 * 添加或删除指定成员，并重新读取服务端成员列表。
 * 接收账号 ID、add。
 * 由 当前模板的 @click 事件 触发。
 * 下游调用 loadMembers、message。
 */
async function changeMember(userId: number, add: boolean) {
  if (!props.isOwner || saving.value || loadingMembers.value || members.value === null) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const change = add ? addKnowledgeBaseMember : removeKnowledgeBaseMember
    await change(props.knowledgeBase.id, userId)
    pendingRemoval.value = null
    searchResults.value = null
    notice.value = add ? '已共享给该成员。' : '已移除该成员的访问权限。'
    await loadMembers()
  } catch (cause) {
    error.value = message(cause, '成员变更失败，请重试。')
  } finally {
    saving.value = false
  }
}

/**
 * 核对输入的确认名称后提交整库删除并通知外层页面。
 * 由 当前模板的 @submit.prevent 事件 触发。
 * 下游调用 library 的 deleteKnowledgeBase、message。
 */
async function removeLibrary() {
  if (!props.isOwner || props.deletionBlocked || saving.value
      || confirmation.value.trim() !== props.knowledgeBase.name.trim()) return
  saving.value = true
  error.value = ''
  try {
    const result = await deleteKnowledgeBase(props.knowledgeBase.id)
    dialog.value = null
    emit('removed', props.knowledgeBase.id, props.knowledgeBase.name, result)
  } catch (cause) {
    error.value = message(cause, '删除结果未确认，请刷新知识库列表后再试。')
  } finally {
    saving.value = false
  }
}

onMounted(() => { void loadMembers() })
</script>

<template>
  <div class="library-management">
    <span class="library-status" :class="{ 'shared-status': !isOwner || members?.length }" role="status">{{ sharingStatus }}</span>
    <div v-if="isOwner" class="library-actions">
      <t-button size="small" variant="outline" theme="primary" @click="open('sharing')">管理共享</t-button>
      <t-button size="small" variant="text" @click="open('details')">编辑知识库</t-button>
      <t-button size="small" variant="text" theme="danger" :disabled="deletionBlocked" @click="open('delete')">
        删除知识库
      </t-button>
    </div>
  </div>

  <dialog v-if="isOwner" ref="modal" class="library-modal" aria-labelledby="library-modal-title"
    @cancel="onDialogCancel" @close="closeDialog">
    <header class="library-modal-head">
      <h2 id="library-modal-title">{{ dialog ? titles[dialog] : '' }}</h2>
      <t-button size="small" variant="text" :disabled="saving" @click="closeDialog">关闭</t-button>
    </header>
    <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form v-if="dialog === 'details'" class="library-dialog" @submit.prevent="saveDetails">
      <label>知识库名称<input v-model="name" maxlength="100" required autofocus :disabled="saving"></label>
      <label>知识库描述<textarea v-model="description" maxlength="500" rows="3" :disabled="saving" /></label>
      <div class="library-actions">
        <t-button variant="outline" :disabled="saving" @click="closeDialog">取消</t-button>
        <t-button type="submit" theme="primary" :loading="saving" :disabled="!name.trim()">保存</t-button>
      </div>
    </form>
    <div v-else-if="dialog === 'sharing'" class="library-dialog">
      <p>共享成员可以查阅文档并使用该知识库问答。</p>
      <form class="member-search" @submit.prevent="search">
        <label>按用户名查找<input v-model="searchName" maxlength="50" autofocus :disabled="saving"
          @input="searchResults = null"></label>
        <t-button type="submit" variant="outline" :loading="searching" :disabled="saving || !searchName.trim()">
          查找
        </t-button>
      </form>
      <template v-if="candidates !== null">
        <p v-if="!candidates.length">没有找到可添加的用户。</p>
        <ul v-else class="member-list" aria-label="可添加用户">
          <li v-for="user in candidates" :key="user.id">
            <span>{{ user.username }}</span>
            <t-button size="small" variant="outline" :disabled="saving || loadingMembers || members === null"
              @click="changeMember(user.id, true)">添加</t-button>
          </li>
        </ul>
      </template>
      <h3>共享成员</h3>
      <p v-if="loadingMembers" role="status">正在读取成员…</p>
      <div v-else-if="memberError" class="member-list-error">
        <p class="inline-error" role="alert">{{ memberError }}</p>
        <t-button size="small" variant="outline" :disabled="saving" @click="loadMembers">重试</t-button>
      </div>
      <p v-else-if="!members?.length">暂无共享成员。</p>
      <ul v-else class="member-list" role="list" aria-label="共享成员">
        <li v-for="member in members" :key="member.userId" role="listitem">
          <span>{{ member.username }}</span>
          <t-button size="small" variant="outline" theme="danger" :disabled="saving"
            @click="pendingRemoval = member">移除</t-button>
        </li>
      </ul>
      <div v-if="pendingRemoval" class="member-confirmation" aria-label="确认移除成员" role="group">
        <p role="status">移除「{{ pendingRemoval.username }}」后，该成员将无法访问这个知识库。</p>
        <div class="library-actions">
          <t-button size="small" variant="outline" :disabled="saving" @click="pendingRemoval = null">取消</t-button>
          <t-button size="small" theme="danger" :loading="saving"
            @click="changeMember(pendingRemoval.userId, false)">确认移除</t-button>
        </div>
      </div>
    </div>
    <form v-else-if="dialog === 'delete'" class="library-dialog" @submit.prevent="removeLibrary">
      <p>将永久删除「{{ knowledgeBase.name }}」及其文档和索引，并移除所有成员的访问权限。</p>
      <label>输入知识库名称以确认<input v-model="confirmation" maxlength="100" :disabled="saving"></label>
      <div class="library-actions">
        <t-button variant="outline" autofocus :disabled="saving" @click="closeDialog">取消</t-button>
        <t-button type="submit" theme="danger" :loading="saving"
          :disabled="deletionBlocked || confirmation.trim() !== knowledgeBase.name.trim()">确认删除</t-button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.library-management { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--space-3); padding-top: var(--space-3); border-top: 1px solid var(--border); }
.library-status { padding: var(--space-1) var(--space-2); border-radius: var(--radius-full); background: var(--bg-inset); color: var(--text-dim); font-size: var(--fs-sm); }
.shared-status { background: var(--accent-soft); color: var(--accent); }
.library-actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.library-modal { width: min(560px, calc(100% - 2rem)); max-height: calc(100dvh - 2rem); padding: var(--space-5); overflow: auto; overscroll-behavior: contain; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elevated); color: var(--text); box-shadow: var(--shadow-dialog); }
.library-modal::backdrop { background: rgb(18 20 28 / 52%); overflow: hidden; overscroll-behavior: contain; }
.library-modal-head { display: flex; justify-content: space-between; align-items: center; gap: var(--space-3); margin-bottom: var(--space-3); }
.library-modal-head h2 { margin: 0; }
.library-dialog { display: grid; gap: var(--space-3); }
.library-dialog p, .library-dialog h3 { margin: 0; }
.library-dialog label { display: grid; gap: var(--space-1); }
.library-dialog input, .library-dialog textarea { width: 100%; padding: var(--space-2); color: var(--text); background: var(--bg-panel); border: 1px solid var(--border); border-radius: var(--radius-sm); }
.member-search { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: var(--space-2); }
.member-list { list-style: none; display: grid; gap: var(--space-2); padding: 0; margin: 0; max-height: 240px; overflow: auto; }
.member-list li, .member-list-error { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
.member-list li span { overflow-wrap: anywhere; }
.member-confirmation { display: grid; gap: var(--space-2); padding: var(--space-3); border-radius: var(--radius-sm); background: var(--bg-inset); overflow-wrap: anywhere; }
</style>
