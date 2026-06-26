import service from "@/http";
import { getStreamChat } from "./StreamApi";

export const ChatApi = {
  Chat: "/chat/stream",
  RagChat: "/ai/rag",
  RagWithKb: "/ai/rag-with-kb",
};

// 聊天消息接口
export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  isTyping?: boolean;
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