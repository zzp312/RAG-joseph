<template>
  <div class="chat-container">
    <el-card class="box-card">
      <div class="chat-messages" ref="messageContainer">
        <div v-for="(message, index) in messages" :key="index" 
             :class="['message', message.role === 'user' ? 'user-message' : 'assistant-message']">
          <div class="message-wrapper">
            <div class="message-content" :class="{ 'typing': message.isTyping }" v-html="renderMarkdown(message.content)">
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
          <el-button type="warning" @click="clearMessages">清空对话</el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { marked } from 'marked'
import { Document } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ChatApi, type ChatMessage } from '@/api/ChatApi'
import { getStreamChat } from '@/api/StreamApi'
import { queryFileApi, listKnowledgeBasesApi } from '@/api/KnowHubApi'


const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isLoading = ref(false)
const messageContainer = ref<HTMLElement | null>(null)
const knowledgeFiles = ref<any[]>([])
const selectedFiles = ref<string[]>([])

const knowledgeBases = ref<any[]>([])
const selectedKbIds = ref<number[]>([])

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

  messages.value.push({
    role: 'user',
    content: userInput.value
  })

  const currentInput = userInput.value
  userInput.value = ''
  isLoading.value = true

  messages.value.push({
    role: 'assistant',
    content: '',
    isTyping: true
  })

  const lastIndex = messages.value.length - 1
  const reactiveMessage = messages.value[lastIndex]
  let isFirstChunk = true;

  const fileSources = selectedFiles.value.map(id => {
    const file = knowledgeFiles.value.find(f => f.id === id)
    return file ? file.fileName : ''
  }).filter(name => name !== '')

  const useKbApi = selectedKbIds.value.length > 0 || fileSources.length > 0
  const ragUrl = useKbApi ? ChatApi.RagWithKb : ChatApi.RagChat

  if (useKbApi) {
    getStreamChat(currentInput, ragUrl, (value) => {
      const text = value.data;
      
      if (isFirstChunk) {
        reactiveMessage.content = '';
        isFirstChunk = false;
      }

      reactiveMessage.content += text 
      
      scrollToBottom()

    }, (error) => {
      window.console.error('Error:', error)
      reactiveMessage.content = '抱歉，发生了错误，请稍后重试。'
    }, () => { 
      isLoading.value = false
      reactiveMessage.isTyping = false
    }, fileSources, selectedKbIds.value)
  } else {
    getStreamChat(currentInput, ragUrl, (value) => {
      const text = value.data;
      
      if (isFirstChunk) {
        reactiveMessage.content = '';
        isFirstChunk = false;
      }

      reactiveMessage.content += text 
      
      scrollToBottom()

    }, (error) => {
      window.console.error('Error:', error)
      reactiveMessage.content = '抱歉，发生了错误，请稍后重试。'
    }, () => { 
      isLoading.value = false
      reactiveMessage.isTyping = false
    })
  }
};

const scrollToBottom = () => {
  if (!messageContainer.value) return
  
  const container = messageContainer.value
  container.scrollTop = container.scrollHeight
  
  setTimeout(() => {
    container.scrollTop = container.scrollHeight
  }, 100)
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
    return marked(content, {
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
  align-items: flex-start;
  gap: 8px;
}

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
