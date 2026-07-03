<template>
  <div class="chat-container">
    <el-card class="box-card">
      <div class="chat-messages" ref="messageContainer">
        <div v-for="(message, index) in messages" :key="index"
             :class="['message', message.role === 'user' ? 'user-message' : 'assistant-message']">
          <div class="message-wrapper">
            <!-- 工作流步骤块（内联在答案上方） -->
            <div v-if="message.role === 'assistant' && (message.steps?.length || message.isTyping)"
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
                </div>
              </div>
            </div>

            <!-- 答案内容：直接展示，无打字机效果 -->
            <div class="message-content"
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
          <el-button type="primary" @click="handleRagSend" :loading="isLoading">RAG回答</el-button>
          <el-button type="success" @click="handleOldRagSend" :loading="isLoading">旧版检索</el-button>
          <el-button type="warning" @click="clearMessages">清空对话</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { marked } from 'marked'
import { Document, CircleCheckFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ChatApi, type ChatMessage } from '@/api/ChatApi'
import { getStreamChat } from '@/api/StreamApi'
import { queryFileApi, listKnowledgeBasesApi, getLatestDocumentsBatchApi } from '@/api/KnowHubApi'
import { useWorkflowSteps, type WorkflowStep } from '@/composables/useWorkflowSteps'


const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isLoading = ref(false)
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

const sendMessage = (ragUrl: string) => {
  messages.value.push({
    role: 'user',
    content: userInput.value
  })

  const currentInput = userInput.value
  userInput.value = ''
  isLoading.value = true

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
    fallbackText: null
  })

  const lastIndex = messages.value.length - 1
  const reactiveMessage = messages.value[lastIndex]

  // source过滤用原始文件名（匹配Milvus metadata.source），非OSS存储名
  const fileSources = selectedFiles.value.map(id => {
    const file = knowledgeFiles.value.find(f => f.id === id)
    return file ? (file.originalName || file.fileName) : ''
  }).filter(name => name !== '')

  getStreamChat(currentInput, ragUrl, (event) => {
    // 后端已使用 ServerSentEvent 规范发送 event 与 data，按 event 名分发
    const eventName = event.event || ''
    const rawData = event.data || ''

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
      const text = rawData.replace(/\\n/g, '\n')
      reactiveMessage.content += text
      scrollToBottom()
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
    reactiveMessage.isTyping = false
    // 结束本轮：固化总耗时
    endSession()
    reactiveMessage.stepsCompleted = true
    reactiveMessage.totalDurationMs = totalDurationMs.value
    // 自动折叠
    reactiveMessage.stepsCollapsed = true
  }, fileSources, selectedKbIds.value, sessionId.value)
};

/** 切换单条消息的步骤折叠状态 */
function toggleMessageSteps(message: ChatMessage) {
  message.stepsCollapsed = !message.stepsCollapsed
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

onMounted(() => {
  messages.value.push({
    role: 'assistant',
    content: '你好！我是AI助手，请问有什么可以帮助你的吗？'
  })

  loadKnowledgeFiles()
  loadKnowledgeBases()
})
</script>

<style scoped lang="less">
.chat-container {
  height: 100vh;
  padding: 20px;
  box-sizing: border-box;
  overflow: hidden;

  .box-card {
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
</style>
