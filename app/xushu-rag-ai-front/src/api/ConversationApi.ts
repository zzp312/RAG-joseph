import service from "@/http";

// 后端基础路径（service 已配置 baseURL=/api/v1，这里用相对路径，避免双重 /api/v1 前缀）
const BASE = "/conversations";

/** 会话列表项 */
export interface ConversationListItem {
  conversationId: string;
  title: string;
  firstMessage: string | null;
  lastMessage: string | null;
  status: string;
  updateTime: string;
  messageCount: number;
}

/** 对话消息 */
export interface ChatMessageDTO {
  id: number;
  role: string;
  content: string;
  cotContent: string | null;
  category: string | null;
  toolName: string | null;
  retrievalSources: string | null;
  createTime: string;
}

/** 会话详情（含全量消息） */
export interface ConversationDetail {
  conversationId: string;
  title: string;
  status: string;
  kbIds: string | null;
  createTime: string;
  updateTime: string;
  messageCount: number;
  messages: ChatMessageDTO[];
}

/** 新建会话 */
export const createConversation = async (kbIds?: string): Promise<any> => {
  const params: any = {};
  if (kbIds) params.kbIds = kbIds;
  return await service.post(BASE, null, { params });
};

/** 会话列表 */
export const listConversations = async (
  page: number = 1,
  size: number = 50
): Promise<any> => {
  return await service.get(BASE, { params: { page, size } });
};

/** 会话详情（含全量消息） */
export const getConversationDetail = async (
  conversationId: string
): Promise<any> => {
  return await service.get(`${BASE}/${conversationId}`);
};

/** 更新标题 */
export const updateConversationTitle = async (
  conversationId: string,
  title: string
): Promise<any> => {
  return await service.patch(`${BASE}/${conversationId}`, null, {
    params: { title },
  });
};

/** 归档会话（软删除） */
export const archiveConversation = async (
  conversationId: string
): Promise<any> => {
  return await service.delete(`${BASE}/${conversationId}`);
};
