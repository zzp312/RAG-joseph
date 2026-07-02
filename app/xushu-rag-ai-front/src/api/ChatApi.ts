import service from "@/http";
import { getStreamChat } from "./StreamApi";

export const ChatApi = {
  Chat: "/chat/stream",
  RagChat: "/ai/rag",
  RagWithKb: "/ai/rag-with-kb",
  RagGraph: "/chat/rag-graph",  // Graph Agent 工作流模式
};

// 聊天消息接口
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  isTyping?: boolean;
  /** 工作流步骤列表（内联展示，仅assistant消息使用） */
  steps?: Array<{
    type: 'thinking' | 'tool' | 'error';
    content: string;
    timestamp: number;
    durationMs?: number;
  }>;
  /** 步骤块是否折叠 */
  stepsCollapsed?: boolean;
  /** 本轮步骤是否全部完成 */
  stepsCompleted?: boolean;
  /** 本轮总耗时（毫秒） */
  totalDurationMs?: number;
  /** 后端 SSE 攒批时的 fallback 占位是否可见(后端长时间不发新 step 时为 true) */
  fallbackVisible?: boolean;
  /** fallback 占位展示文本,如 "正在执行 检索..." */
  fallbackText?: string | null;
}

// 发送消息接口 (传统fetch方式)
export const sendChatMessageApi = async (message: string): Promise<Response> => {
  return fetch(`${service.defaults.baseURL}${ChatApi.Chat}?message=${encodeURIComponent(message)}`, {
    headers: {
      'Authorization': `Bearer ${localStorage.getItem('token')}`
    }
  }).then(response => {
    if (response.status === 401) {
      // 处理401未授权错误
      import('@/api/authUtils').then(module => {
        module.default();
      });
    }
    return response;
  });
};

// 发送RAG消息接口
export const sendRagChatMessageApi = async (message: string, sources: string[] = []): Promise<Response> => {
  const formData = new FormData();
  formData.append('message', message);
  
  if (sources && sources.length > 0) {
    sources.forEach(source => {
      formData.append('sources', source);
    });
  }
  
  return fetch(`${service.defaults.baseURL}${ChatApi.RagChat}`, {
    method: 'POST',
    body: formData,
    headers: {
      'Authorization': `Bearer ${localStorage.getItem('token') || ''}`
    }
  }).then(response => {
    if (response.status === 401) {
      // 处理401未授权错误
      import('@/api/authUtils').then(module => {
        module.default();
      });
    }
    return response;
  });
};

// 发送消息接口 (SSE方式)
export const sendChatMessageWithSSE = (
  message: string,
  onMessage: (event: any) => void,
  onError: (error: any) => void,
  onClose: () => void
) => {
  return getStreamChat(
    encodeURIComponent(message),
    "/chat/stream",
    onMessage,
    onError,
    onClose
  );
};