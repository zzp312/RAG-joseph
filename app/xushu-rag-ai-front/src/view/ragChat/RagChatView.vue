<template>
  <div class="chat-layout">
    <!-- 会话列表侧边栏 -->
    <el-aside class="conversation-sidebar" width="240px">
      <div class="sidebar-header">
        <span class="sidebar-title">历史会话</span>
        <el-button type="primary" size="small" :icon="Plus" @click="startNewConversation"
                   :title="'新会话'">新建</el-button>
      </div>
      <div class="conversation-list" v-loading="conversationStore.loadingList">
        <div v-for="conv in conversationStore.conversationList"
             :key="conv.conversationId"
             :class="['conversation-item',
                      conversationStore.currentConversationId === conv.conversationId ? 'active' : '']"
             @click="switchConversation(conv.conversationId)">
          <div class="conv-title">{{ conv.title || conv.firstMessage || '新会话' }}</div>
          <div class="conv-meta">
            <span>{{ conv.messageCount }} 条</span>
            <span>{{ formatTime(conv.updateTime) }}</span>
          </div>
          <el-icon class="conv-delete" @click.stop="archiveConv(conv.conversationId)">
            <Delete />
          </el-icon>
        </div>
        <div v-if="!conversationStore.loadingList && conversationStore.conversationList.length === 0"
             class="empty-tip">
          暂无历史会话
        </div>
      </div>
    </el-aside>

    <el-card class="box-card">
      <div class="chat-messages" ref="messageContainer">
        <div v-for="(message, index) in messages" :key="index">
          <!-- 分界线消息 -->
          <div v-if="message.role === 'divider'"
               :class="['divider-message', 'divider-' + (message.dividerType || '')]">
            <div class="divider-line"></div>
            <span class="divider-text">{{ message.content }}</span>
            <div class="divider-line"></div>
          </div>

          <!-- 普通对话消息 -->
          <div v-else :class="['message', message.role === 'user' ? 'user-message' : 'assistant-message']">
            <div class="message-wrapper">
            <!-- 工作流步骤块（内联在答案上方） -->
            <div v-if="message.role === 'assistant' && !(message as any).humanMode && (message.steps?.length || message.isTyping)"
                 class="workflow-steps">
              <div class="workflow-steps-header" @click="toggleMessageSteps(message)">
                <span class="toggle-icon">{{ message.stepsCollapsed ? '▸' : '▾' }}</span>
                <span class="toggle-text">
                  {{ message.stepsCollapsed ? '查看运行过程' : '隐藏运行过程' }}
                </span>
              </div>
              <div v-show="!message.stepsCollapsed" class="workflow-steps-body">
                <div class="step-bubbles">
                  <div v-for="(step, idx) in message.steps" :key="idx"
                       class="step-bubble" :class="['type-' + step.type]"
                       :style="{ animationDelay: (idx * 80) + 'ms' }">
                    <span class="step-icon">{{ getStepIcon(step.type) }}</span>
                    <span class="step-content">{{ step.content }}</span>
                    <span v-if="step.durationMs" class="step-duration">
                      {{ formatDuration(step.durationMs) }}
                    </span>
                  </div>
                  <!-- 第一个步骤到达前的占位提示 -->
                  <div v-if="message.isTyping && (!message.steps || message.steps.length === 0)"
                       class="step-bubble type-thinking thinking-pulse">
                    <span class="step-icon">💭</span>
                    <span class="step-content">思考中</span>
                  </div>
                  <!-- fallback 占位:后端 SSE 攒批时,显示"正在执行 [上一节点]..." -->
                  <div v-else-if="message.isTyping && message.fallbackVisible && message.fallbackText"
                       class="step-bubble type-thinking thinking-pulse">
                    <span class="step-icon">⏳</span>
                    <span class="step-content">{{ message.fallbackText }}</span>
                  </div>
                  <!-- 所有步骤完成，等待答案生成 -->
                  <div v-else-if="message.isTyping"
                       class="step-bubble type-thinking thinking-pulse"
                       style="margin-top: 8px;">
                    <span class="step-icon">💭</span>
                    <span class="step-content">思考中...</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- CoT 深度思考折叠块（在步骤和答案之间）：任意角色只要带 cotContent 都展示，保留 TOOL 样式 -->
            <div v-if="message.cotContent"
                 class="cot-block">
              <div class="cot-header" @click="toggleCot(message)">
                <span class="toggle-icon">{{ message.cotCollapsed ? '▸' : '▾' }}</span>
                <span class="toggle-text">
                  💭 {{ message.cotCollapsed ? '查看深度思考' : '隐藏深度思考' }}
                </span>
              </div>
              <div v-show="!message.cotCollapsed" class="cot-body">
                <pre class="cot-text">{{ message.cotContent }}</pre>
              </div>
            </div>

            <!-- 答案内容：直接展示，无打字机效果 -->
            <div class="message-content"
                 v-show="message.content || !message.isTyping"
                 v-html="renderMarkdown(message.content)">
            </div>

            <!-- 总耗时（含LLM生成时间），展示在答案气泡底部 -->
            <div v-if="message.stepsCompleted && message.totalDurationMs"
                 class="answer-footer-time">
              <el-icon class="time-icon"><CircleCheckFilled /></el-icon>
              <span>耗时 {{ formatDuration(message.totalDurationMs) }}</span>
            </div>

            <el-button
              class="copy-button"
              type="text"
              size="small"
              @click="copyMessage(message.content)"
            >
              <el-icon><Document /></el-icon>
            </el-button>
          </div>
          </div>
        </div>
      </div>
      <!-- 转人工按钮（输入框下方） -->
      <div class="escalate-bar" v-if="!isHumanMode">
        <el-button class="escalate-btn" @click="handleEscalate">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
            <circle cx="12" cy="7" r="4"></circle>
          </svg>
          <span>转人工</span>
        </el-button>
      </div>
      <div class="input-container">

        <el-input
          v-model="userInput"
          type="textarea"
          :rows="3"
          placeholder="请输入您的问题..."
          @keyup.enter="handleRagSend"
        />
      </div>



      <div class="button-group">
        <div class="selection-area">
          <div class="selection-item">
            <span class="selection-label">选择知识库：</span>
            <el-select
              v-model="selectedKbIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择知识库"
              style="width: 200px; margin-right: 10px;"
              size="small"
            >
              <el-option
                v-for="kb in knowledgeBases"
                :key="kb.id"
                :label="kb.name"
                :value="kb.id"
              />
            </el-select>
            <el-button
              v-if="selectedKbIds.length > 0"
              type="text"
              size="small"
              @click="selectedKbIds = []"
            >
              清空
            </el-button>
          </div>

          <div class="selection-item">
            <span class="selection-label">选择文件：</span>
            <el-select
              v-model="selectedFiles"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择文件"
              style="width: 200px; margin-right: 10px;"
              size="small"
              @change="handleFileSelectionChange"
            >
              <el-option
                v-for="file in knowledgeFiles"
                :key="file.id"
                :label="file.fileName + (file.version ? ' (' + file.version + ')' : '')"
                :value="file.id"
              />
            </el-select>
          </div>
        </div>

        <div class="action-buttons">
          <el-button type="primary" @click="handleRagSend" :loading="isLoading">发送</el-button>
          <el-button type="warning" @click="clearMessages">清空对话</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { marked } from 'marked'
import { Document, CircleCheckFilled, Plus, Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ChatApi, type ChatMessage } from '@/api/ChatApi'
import { getStreamChat } from '@/api/StreamApi'
import { queryFileApi, listKnowledgeBasesApi, getLatestDocumentsBatchApi } from '@/api/KnowHubApi'
import { useWorkflowSteps, type WorkflowStep } from '@/composables/useWorkflowSteps'
import { useConversationStore } from '@/store/conversation'

const conversationStore = useConversationStore()


const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isLoading = ref(false)
const isHumanMode = ref(false)
const messageContainer = ref<HTMLElement | null>(null)
const knowledgeFiles = ref<any[]>([])
const selectedFiles = ref<string[]>([])

const knowledgeBases = ref<any[]>([])
const selectedKbIds = ref<number[]>([])

// 每个浏览器标签页生成唯一会话ID
const sessionId = ref(Date.now().toString(36) + Math.random().toString(36).slice(2, 8))

// ===== 工作流步骤状态 =====
const {
  steps,
  startSession,
  addStep,
  endSession,
  getStepIcon,
  formatDuration,
  totalDurationMs,
  fallbackVisible,
  fallbackText
} = useWorkflowSteps()

const loadKnowledgeFiles = () => {
  const params = {
    page: 0,
    pageSize: 200,
    fileName: ""
  }

  queryFileApi(params)
    .then((res) => {
      if (res.code == 0) {
        const data = res.data;
        knowledgeFiles.value = data.records || [];
      } else {
        ElMessage({
          type: 'error',
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: 'error',
        message: err,
      });
    });
};

const loadKnowledgeBases = () => {
  listKnowledgeBasesApi()
    .then((res) => {
      if (res.code == 0) {
        knowledgeBases.value = res.data;
      } else {
        ElMessage({
          type: 'error',
          message: res.message,
        });
      }
    })
    .catch((err) => {
      ElMessage({
        type: 'error',
        message: err,
      });
    });
};

const handleRagSend = async () => {
  if (!userInput.value.trim() || isLoading.value) return
  sendMessage(ChatApi.RagGraph)
};

/** 旧版检索接口，用于对比召回策略 */
const handleOldRagSend = async () => {
  if (!userInput.value.trim() || isLoading.value) return
  sendMessage(ChatApi.RagWithKb)
};

/** 点击转人工按钮 */
const handleEscalate = () => {
  if (isLoading.value || isHumanMode.value) return
  isLoading.value = true
  isHumanMode.value = true

  // 插入系统消息
  messages.value.push({
    role: 'divider',
    content: '人工客服已接入',
    dividerType: 'human_start'
  } as ChatMessage)

    getStreamChat('转人工', ChatApi.RagGraph, (event) => {
      if (event.event === 'conversation' && event.data) {
        conversationStore.setCurrentConversation(event.data)
        return
      }
      if (event.event === 'divider' && event.data) {
        try {
          const d = JSON.parse(event.data)
          if (d.type === 'human_start') isHumanMode.value = true
        } catch (e) {}
      }
    }, (error) => {
      window.console.error('转人工失败:', error)
      isLoading.value = false
    }, () => {
      isLoading.value = false
      conversationStore.loadConversationList()
    }, undefined, undefined, sessionId.value, true, conversationStore.currentConversationId || undefined)
    scrollToBottom()
}

const sendMessage = (ragUrl: string) => {
  messages.value.push({
    role: 'user',
    content: userInput.value
  })

  const currentInput = userInput.value
  userInput.value = ''
  isLoading.value = true

  // 人工模式下：不创建AI气泡，但收到divider(human_end)时动态创建气泡接收回复
  if (isHumanMode.value) {
    let returnReactiveMessage: any = null
    getStreamChat(currentInput, ragUrl, (event: any) => {
      const eventName = event.event || ''
      const rawData = event.data || ''
      // conversation 事件：后端懒创建后回传 conversationId
      if (eventName === 'conversation') {
        if (rawData) conversationStore.setCurrentConversation(rawData)
        return
      }
      // 回AI分界线
      if (eventName === 'divider') {
        try {
          const d = JSON.parse(rawData)
          if (d.type === 'human_end') {
            isHumanMode.value = false
            messages.value.push({ role: 'divider', content: d.content || 'AI已恢复服务', dividerType: 'human_end' } as ChatMessage)
            startSession()
            messages.value.push({ role: 'assistant', content: '', isTyping: true, steps: [] as WorkflowStep[], stepsCollapsed: false, stepsCompleted: false, totalDurationMs: 0, fallbackVisible: false, fallbackText: null } as any)
            const idx = messages.value.length - 1
            returnReactiveMessage = messages.value[idx]
            scrollToBottom()
          }
        } catch (e) { }
        return
      }
      if (!returnReactiveMessage || eventName === 'done' || rawData === '[DONE]') return
      if (eventName === 'step') {
        try {
          const stepData = JSON.parse(rawData)
          addStep(stepData.type || 'thinking', stepData.content || '', stepData.ts)
          returnReactiveMessage.steps = [...steps.value]
          returnReactiveMessage.stepsCollapsed = false
          scrollToBottom()
        } catch (e) { }
      }
      if (eventName === 'thinking') {
        try {
          const thinkingData = JSON.parse(rawData)
          returnReactiveMessage.cotContent = thinkingData.content || ''
          returnReactiveMessage.cotCollapsed = false
          scrollToBottom()
        } catch (e) { }
      }
      if (eventName === 'message') {
        const text = rawData.replace(/\\n/g, '\n')
        if (returnReactiveMessage.isTyping && returnReactiveMessage.content === '') returnReactiveMessage.stepsCollapsed = true
        returnReactiveMessage.content += text
        scrollToBottom()
      }
    }, (error: any) => {
      window.console.error('Error:', error)
    }, () => {
      isLoading.value = false
      if (returnReactiveMessage) {
        returnReactiveMessage.isTyping = false
        endSession()
        returnReactiveMessage.stepsCompleted = true
        returnReactiveMessage.totalDurationMs = totalDurationMs.value
        returnReactiveMessage.stepsCollapsed = true
      }
      conversationStore.loadConversationList()
    }, undefined, undefined, sessionId.value, false, conversationStore.currentConversationId || undefined)
    isLoading.value = false
    scrollToBottom()
    return
  }

  // 开始一轮新的步骤收集
  startSession()

  messages.value.push({
    role: 'assistant',
    content: '',
    isTyping: true,
    steps: [] as WorkflowStep[],
    stepsCollapsed: false,
    stepsCompleted: false,
    totalDurationMs: 0,
    fallbackVisible: false,
    fallbackText: null,
    humanMode: isHumanMode.value
  } as any)

  const lastIndex = messages.value.length - 1
  const reactiveMessage = messages.value[lastIndex]

  // 后端仍处于人工模式时会返回 HUMAN_MODE 占位信号，用此标记在收尾时移除空气泡
  let humanModeInterrupted = false

  // source过滤用原始文件名（匹配Milvus metadata.source），非OSS存储名
  const fileSources = selectedFiles.value.map(id => {
    const file = knowledgeFiles.value.find(f => f.id === id)
    return file ? (file.originalName || file.fileName) : ''
  }).filter(name => name !== '')

  getStreamChat(currentInput, ragUrl, (event) => {
    // 后端已使用 ServerSentEvent 规范发送 event 与 data，按 event 名分发
    const eventName = event.event || ''
    const rawData = event.data || ''

    // conversation 事件：后端懒创建后回传 conversationId，前端同步侧边栏
    if (eventName === 'conversation') {
      if (rawData) conversationStore.setCurrentConversation(rawData)
      return
    }

    if (eventName === 'done' || rawData === '[DONE]') {
      return
    }

    // 周期性把 composable 的 fallback 状态同步到当前消息(因为定时器在 composable 内部)
    reactiveMessage.fallbackVisible = fallbackVisible.value
    reactiveMessage.fallbackText = fallbackText.value

    if (eventName === 'step') {
      try {
        const stepData = JSON.parse(rawData)
        // 写入composable（去重+耗时计算）
        // 透传后端节点完成时间戳,作为该 step 的真实完成时间,
        // 避免后端攒批时所有 step durationMs 都退化成"首字节到当前"的错误值
        addStep(stepData.type || 'thinking', stepData.content || '', stepData.ts)
        // 同步到消息对象上（用最新副本，触发响应式更新）
        reactiveMessage.steps = [...steps.value]
        // 步骤开始时自动展开，用户看到实时进度
        reactiveMessage.stepsCollapsed = false
        // 收到新 step 后,fallback 占位立即清掉(直到下一次超时才再次出现)
        reactiveMessage.fallbackVisible = false
        reactiveMessage.fallbackText = null
        scrollToBottom()
      } catch (e) {
        // JSON解析失败，静默忽略
      }
      return
    }

    if (eventName === 'message') {
      // 后端仍在人工模式：以后端为准把前端切回人工模式，并标记稍后移除占位空气泡
      if (rawData === 'HUMAN_MODE') {
        humanModeInterrupted = true
        isHumanMode.value = true
        return
      }
      const text = rawData.replace(/\\n/g, '\n')
      // 首次收到答案 token 时：自动收起步骤块，展示最终答案
      if (reactiveMessage.isTyping && reactiveMessage.content === '') {
        reactiveMessage.stepsCollapsed = true
      }
      reactiveMessage.content += text
      scrollToBottom()
      return
    }

    if (eventName === 'divider') {
      try {
        const dividerData = JSON.parse(rawData)
        const dtype = dividerData.type || 'human_start'
        const dcontent = dividerData.content || ''

        if (dtype === 'human_start') {
          isHumanMode.value = true
        } else if (dtype === 'human_end') {
          isHumanMode.value = false
        }

        messages.value.push({
          role: 'divider',
          content: dcontent,
          dividerType: dtype
        } as ChatMessage)
        scrollToBottom()
      } catch (e) {
        // JSON解析失败，静默忽略
      }
      return
    }

    if (eventName === 'thinking') {
      try {
        const thinkingData = JSON.parse(rawData)
        reactiveMessage.cotContent = thinkingData.content || ''
        reactiveMessage.cotCollapsed = false  // 首次收到时展开，让用户看到
        scrollToBottom()
      } catch (e) {
        // JSON解析失败，静默忽略
      }
      return
    }

  }, (error) => {
    window.console.error('Error:', error)
    reactiveMessage.content = '抱歉，发生了错误，请稍后重试。'
    endSession()
    reactiveMessage.isTyping = false
    reactiveMessage.stepsCompleted = true
    reactiveMessage.totalDurationMs = totalDurationMs.value
  }, () => {
    isLoading.value = false
    endSession()
    // 人工模式：后端不生成回复，移除占位空气泡，不展示耗时
    if (humanModeInterrupted) {
      const idx = messages.value.indexOf(reactiveMessage)
      if (idx !== -1) messages.value.splice(idx, 1)
      conversationStore.loadConversationList()
      return
    }
    reactiveMessage.isTyping = false
    reactiveMessage.stepsCompleted = true
    reactiveMessage.totalDurationMs = totalDurationMs.value
    reactiveMessage.stepsCollapsed = true
    // 刷新侧边栏会话列表（新会话已由后端懒创建并写入 MySQL）
    conversationStore.loadConversationList()
  }, fileSources, selectedKbIds.value, sessionId.value, false, conversationStore.currentConversationId || undefined)
};

/** 切换单条消息的步骤折叠状态 */
function toggleMessageSteps(message: ChatMessage) {
  message.stepsCollapsed = !message.stepsCollapsed
}

/** 切换 CoT 深度思考块的折叠状态 */
function toggleCot(message: any) {
  message.cotCollapsed = !message.cotCollapsed
}

const scrollToBottom = () => {
  if (!messageContainer.value) return
  const container = messageContainer.value
  // 立即滚动
  container.scrollTop = container.scrollHeight

  // 大图片异步加载时持续跟踪底部，用 requestAnimationFrame 避免抖动
  let frames = 0
  const keepScroll = () => {
    if (!container || frames > 30) return // 最多15帧(约250ms)
    container.scrollTop = container.scrollHeight
    frames++
    requestAnimationFrame(keepScroll)
  }
  requestAnimationFrame(keepScroll)
}

const copyMessage = async (content: string) => {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage({
      message: '复制成功',
      type: 'success',
      duration: 2000
    })
  } catch (err) {
    ElMessage({
      message: '复制失败',
      type: 'error',
      duration: 2000
    })
  }
}

const clearMessages = () => {
  messages.value = [{
    role: 'assistant',
    content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
  }]
}

const renderMarkdown = (content: string) => {
  try {
    // 预处理：LLM有时把标题正文和表格开头挤一行（marked不认），拆开
    const fixed = content.replace(/([：:]) ?\|/g, '$1\n|')
    return marked(fixed, {
      breaks: true,
      gfm: true
    })
  } catch (error) {
    console.error('Markdown parsing error:', error)
    return content
  }
}

const handleFileSelectionChange = (value: string[]) => {
  selectedFiles.value = value
}

// 按知识库IDS加载文件（级联选择）
const loadFilesByKbIds = (kbIds: number[]) => {
  if (kbIds.length === 0) {
    loadKnowledgeFiles()
    return
  }
  getLatestDocumentsBatchApi(kbIds)
    .then((res) => {
      if (res.code == 0) {
        knowledgeFiles.value = res.data || []
      } else {
        ElMessage.error(res.message)
      }
    })
    .catch((err) => {
      ElMessage.error(err)
    })
}

// 监听知识库选择变化，级联更新文件列表
watch(selectedKbIds, (newIds) => {
  selectedFiles.value = []
  loadFilesByKbIds(newIds as number[])
}, { deep: true })

onMounted(async () => {
  loadKnowledgeFiles()
  loadKnowledgeBases()
  // 加载历史会话列表
  await conversationStore.loadConversationList()

  if (conversationStore.conversationList.length > 0) {
    // 默认进入最新（第一条）会话，直接调用 store 方法绕过 switchConversation 的同 ID 守卫
    const firstConv = conversationStore.conversationList[0]
    const restoredMessages = await conversationStore.switchToConversation(firstConv.conversationId)
    messages.value = restoredMessages.length > 0
      ? restoredMessages.map(m => ({
          role: m.role,
          content: m.content,
          cotContent: (m as any).cotContent,
          cotCollapsed: true,
          stepsCollapsed: true,
          stepsCompleted: true,
        } as ChatMessage))
      : [{
          role: 'assistant',
          content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
        } as ChatMessage]
    scrollToBottom()
  } else {
    // 没有任何历史会话，显示欢迎消息，等待用户首次提问时后端懒创建
    conversationStore.startNewConversation()
    messages.value = [{
      role: 'assistant',
      content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
    } as ChatMessage]
  }
})

// ========== 会话切换（Coze 式体验） ==========

/** 切换到历史会话 */
async function switchConversation(conversationId: string) {
  if (conversationStore.switching) return
  if (conversationStore.currentConversationId === conversationId) return

  const restoredMessages = await conversationStore.switchToConversation(conversationId)
  // 退出人工模式：切换会话后回到正常 AI 模式，避免卡在人工分支导致提问不过 LLM
  isHumanMode.value = false
  // 清空当前消息，用历史消息替换
  messages.value = restoredMessages.map(m => ({
    role: m.role,
    content: m.content,
    cotContent: (m as any).cotContent,
    cotCollapsed: true,
    stepsCollapsed: true,
    stepsCompleted: true,
  } as ChatMessage))
  scrollToBottom()
  ElMessage.success('已切换到历史会话，AI 已恢复记忆')
}

/** 新建会话 */
function startNewConversation() {
  conversationStore.startNewConversation()
  // 新建会话同样退出人工模式，回到正常 AI 模式
  isHumanMode.value = false
  messages.value = [{
    role: 'assistant',
    content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
  } as ChatMessage]
  ElMessage.success('已创建新会话')
}

/** 归档会话 */
async function archiveConv(conversationId: string) {
  await conversationStore.archiveConversation(conversationId)
  if (conversationStore.currentConversationId === null) {
    messages.value = [{
      role: 'assistant',
      content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
    } as ChatMessage]
  }
}

/** 格式化时间 */
function formatTime(timeStr: string): string {
  if (!timeStr) return ''
  const d = new Date(timeStr)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toTimeString().slice(0, 5)
  }
  return `${d.getMonth() + 1}/${d.getDate()}`
}
</script>

<style scoped lang="less">
.chat-layout {
  height: 100vh;
  padding: 20px;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  gap: 12px;

  .conversation-sidebar {
    height: 100%;
    background: #fff;
    border-radius: 8px;
    border: 1px solid #e4e7ed;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .sidebar-header {
      padding: 12px;
      border-bottom: 1px solid #e4e7ed;
      display: flex;
      align-items: center;
      justify-content: space-between;

      .sidebar-title {
        font-weight: 600;
        font-size: 14px;
        color: #303133;
      }
    }

    .conversation-list {
      flex: 1;
      overflow-y: auto;

      .conversation-item {
        padding: 10px 12px;
        border-bottom: 1px solid #f0f0f0;
        cursor: pointer;
        position: relative;
        transition: background 0.2s;

        &:hover {
          background: #f5f7fa;
        }

        &.active {
          background: #ecf5ff;
          border-left: 3px solid #409eff;
        }

        .conv-title {
          font-size: 13px;
          color: #303133;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          margin-bottom: 4px;
        }

        .conv-meta {
          font-size: 11px;
          color: #909399;
          display: flex;
          justify-content: space-between;
        }

        .conv-delete {
          position: absolute;
          right: 8px;
          top: 50%;
          transform: translateY(-50%);
          opacity: 0;
          transition: opacity 0.2s;
          color: #f56c6c;
          cursor: pointer;
        }

        &:hover .conv-delete {
          opacity: 1;
        }
      }

      .empty-tip {
        text-align: center;
        color: #909399;
        padding: 40px 0;
        font-size: 13px;
      }
    }
  }

  .box-card {
    flex: 1;
    height: 100%;
    display: flex;
    flex-direction: column;

    :deep(.el-card__body) {
      flex: 1;
      display: flex;
      flex-direction: column;
      padding: 20px;
      overflow: hidden;
    }
  }
}

.selection-area {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  align-items: center;
}

.selection-item {
  display: flex;
  align-items: center;
}

.selection-label {
  margin-right: 10px;
  font-weight: 500;
  color: #606266;
  font-size: 14px;
}

.action-buttons {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 15px;
  padding: 10px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  min-height: 0;
  scroll-behavior: smooth;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    background-color: #909399;
    border-radius: 3px;
  }

  &::-webkit-scrollbar-track {
    background-color: #f0f2f5;
  }
}

.message {
  margin-bottom: 15px;
  max-width: 80%;

  &.user-message {
    margin-left: auto;
    text-align: right;

    .message-wrapper {
      flex-direction: row-reverse;
      justify-content: flex-start;
    }

    .message-content {
      background-color: #007AFF;
      color: white;
    }
  }

  &.assistant-message {
    margin-right: auto;
    text-align: left;
  }
}

.message-wrapper {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

/* ===== 工作流步骤块（内联） ===== */
.workflow-steps {
  width: 100%;
  max-width: 600px;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 0;
  font-size: 13px;
  overflow: hidden;
}

.workflow-steps-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  color: #606266;
  font-weight: 500;

  &:hover {
    background: #f0f2f5;
  }
}

.toggle-icon {
  font-size: 11px;
  color: #909399;
}

.toggle-text {
  font-size: 12px;
}

.workflow-steps-body {
  padding: 8px 12px 10px;
  animation: stepsExpand 0.2s ease;
}

@keyframes stepsExpand {
  from { opacity: 0; max-height: 0; }
  to   { opacity: 1; max-height: 500px; }
}

.step-bubbles {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.step-bubble {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid #e4e7ed;
  color: #606266;
  font-size: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: all 0.2s ease;
  max-width: 100%;
  opacity: 0;
  transform: translateY(6px);
  animation: bubbleIn 0.25s ease forwards;

  &.type-thinking {
    background: #f4f4f5;
    border-color: #e4e7ed;
  }

  &.type-tool {
    background: #ecf5ff;
    border-color: #d9ecff;
    color: #409eff;
  }

  &.type-error {
    background: #fef0f0;
    border-color: #fde2e2;
    color: #f56c6c;
  }

  &.thinking-pulse {
    animation: bubbleIn 0.25s ease forwards, pulse 1.5s ease-in-out infinite;
  }
}

@keyframes bubbleIn {
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes pulse {
  0%, 100% { opacity: 0.7; }
  50% { opacity: 1; }
}

.step-icon {
  font-size: 13px;
  flex-shrink: 0;
  width: 16px;
  text-align: center;
}

.step-content {
  flex: 1;
  min-width: 0;
  word-break: break-word;
  line-height: 1.4;
}

.step-duration {
  font-size: 11px;
  color: #909399;
  background: #f5f7fa;
  padding: 1px 6px;
  border-radius: 8px;
  flex-shrink: 0;
  font-family: Consolas, Monaco, monospace;
}

/* ===== CoT 深度思考折叠块 ===== */
.cot-block {
  width: 100%;
  max-width: 600px;
  background: #faf9f6;
  border: 1px solid #e8e4dc;
  border-radius: 8px;
  padding: 0;
  font-size: 13px;
  overflow: hidden;
}

.cot-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  color: #8b7355;
  font-weight: 500;

  &:hover {
    background: #f5f0e8;
  }
}

.cot-body {
  padding: 10px 14px;
  border-top: 1px solid #e8e4dc;
  animation: cotExpand 0.2s ease;
}

@keyframes cotExpand {
  from { opacity: 0; max-height: 0; }
  to   { opacity: 1; max-height: 600px; }
}

.cot-text {
  margin: 0;
  padding: 0;
  background: transparent;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.7;
  color: #6b5e4e;
  font-family: inherit;
  border: none;
  overflow-x: visible;
}

/* ===== 答案气泡底部耗时 ===== */
.answer-footer-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  padding: 2px 8px;
  color: #909399;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;

  .time-icon {
    font-size: 12px;
    color: #67c23a;
  }
}

/* ===== 消息内容 ===== */
.message-content {
  display: inline-block;
  padding: 10px 15px;
  border-radius: 10px;
  background-color: #f0f0f0;
  word-break: break-word;
  font-size: 14px;

  :deep(p) {
    margin: 0;
    line-height: 1.5;
  }

  :deep(pre) {
    background-color: #f8f8f8;
    padding: 10px;
    border-radius: 4px;
    overflow-x: auto;
    font-size: 13px;
  }

  :deep(code) {
    font-family: Consolas, Monaco, 'Andale Mono', monospace;
    background-color: #f8f8f8;
    padding: 2px 4px;
    border-radius: 3px;
    font-size: 13px;
  }

  :deep(ul), :deep(ol) {
    padding-left: 20px;
    margin: 8px 0;
  }

  :deep(blockquote) {
    margin: 8px 0;
    padding-left: 10px;
    border-left: 4px solid #ddd;
    color: #666;
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
    border-radius: 6px;
    margin: 8px 0;
    display: block;
    box-shadow: 0 2px 8px rgba(0,0,0,0.1);
    object-fit: contain;
    min-height: 20px;
    background: #f8f8f8; /* 加载前占位色，减少抖动 */
  }

  :deep(table) {
    width: 100%;
    border-collapse: collapse;
    margin: 10px 0;
    font-size: 13px;
    border-radius: 6px;
    overflow: hidden;
    box-shadow: 0 1px 4px rgba(0,0,0,0.08);
  }

  :deep(th) {
    background: #409eff;
    color: #fff;
    padding: 8px 12px;
    text-align: left;
    font-weight: 600;
    white-space: nowrap;
  }

  :deep(td) {
    padding: 8px 12px;
    border-bottom: 1px solid #ebeef5;
    background: #fff;
  }

  :deep(tr:nth-child(even) td) {
    background: #f5f7fa;
  }

  :deep(tr:hover td) {
    background: #ecf5ff;
  }

  &.typing {
    &::after {
      content: '...';
      animation: ellipsis 1.5s infinite;
    }
  }
}

@keyframes ellipsis {
  0% { content: '.'; }
  33% { content: '..'; }
  66% { content: '...'; }
  100% { content: '.'; }
}

.copy-button {
  opacity: 0;
  transition: opacity 0.3s;
  padding: 4px;
  height: auto;

  &:hover {
    opacity: 1;
  }
}

.input-container {
  margin-top: auto;
  display: flex;
  gap: 10px;
  padding: 10px;
  background-color: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  min-height: 100px;

  .el-textarea {
    flex: 1;
  }
}

.button-group {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 10px 0;
  flex-wrap: wrap;

  .el-button {
    width: 120px;
    height: 40px;
    font-size: 14px;
  }
}
/* ===== 分界线消息 ===== */
.divider-message {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 12px 20px;
  margin: 8px 0;
}

.divider-line {
  flex: 1;
  height: 1px;
  background: #dcdfe6;
}

.divider-text {
  flex-shrink: 0;
  font-size: 13px;
  color: #909399;
  font-weight: 500;
  white-space: nowrap;
}

/* 人工接入分界线 */
.divider-human_start .divider-line {
  background: #f56c6c;
}

.divider-human_start .divider-text {
  color: #f56c6c;
}

/* AI恢复分界线 */
.divider-human_end .divider-line {
  background: #67c23a;
}

.divider-human_end .divider-text {
  color: #67c23a;
}

/* 转人工按钮 */
/* 转人工小按钮 */
.escalate-bar {
  display: flex;
  align-items: center;
  padding: 2px 0;
}

.escalate-btn {
  height: 32px !important;
  padding: 0 8px !important;
  border: none !important;
  background: none !important;
  font-size: 13px !important;
  color: #409eff;
  transition: color 0.2s;
}

.escalate-btn:hover {
  color: #337ecc;
}

.escalate-btn svg {
  flex-shrink: 0;
  margin-right: 4px;
}


</style>
