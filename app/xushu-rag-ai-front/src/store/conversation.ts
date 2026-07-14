import { defineStore } from "pinia";
import {
  ConversationListItem,
  ConversationDetail,
  listConversations,
  getConversationDetail,
  createConversation,
  archiveConversation,
  updateConversationTitle,
} from "@/api/ConversationApi";
import { Message } from "@/api/dto";

/**
 * 会话状态管理
 * <p>管理当前活跃会话ID、历史会话列表、会话切换（Coze 式体验）。</p>
 */
export const useConversationStore = defineStore("conversation", {
  state: () => ({
    /** 当前活跃会话ID（null = 新会话，首次提问时懒创建） */
    currentConversationId: null as string | null,
    /** 历史会话列表 */
    conversationList: [] as ConversationListItem[],
    /** 是否正在加载列表 */
    loadingList: false,
    /** 是否正在切换会话 */
    switching: false,
  }),
  getters: {
    /** 当前是否有活跃会话 */
    hasActiveConversation(): boolean {
      return this.currentConversationId !== null;
    },
  },
  actions: {
    /** 设置当前会话ID（切换到历史会话时调用） */
    setCurrentConversation(conversationId: string | null) {
      this.currentConversationId = conversationId;
    },

    /** 新会话：清空当前 conversationId，首次提问时后端懒创建 */
    startNewConversation() {
      this.currentConversationId = null;
    },

    /** 加载会话列表 */
    async loadConversationList() {
      this.loadingList = true;
      try {
        const res: any = await listConversations(1, 50);
        if (res && res.code === 0) {
          this.conversationList = res.data || [];
        }
      } catch (e) {
        console.error("[会话列表] 加载失败", e);
      } finally {
        this.loadingList = false;
      }
    },

    /**
     * 切换到历史会话（Coze 式体验核心）
     * <p>1. 设置当前 conversationId</p>
     * <p>2. 拉取该会话全量消息</p>
     * <p>3. 返回消息列表供前端渲染</p>
     * <p>4. 后端在下次提问时自动回填 ChatMemory</p>
     */
    async switchToConversation(
      conversationId: string
    ): Promise<Message[]> {
      this.switching = true;
      try {
        const res: any = await getConversationDetail(conversationId);
        if (res && res.code === 0) {
          const detail: ConversationDetail = res.data;
          this.currentConversationId = conversationId;
          // 转换后端 DTO 为前端 Message 格式
          return detail.messages.map((m) => ({
            role: m.role.toLowerCase(),
            content: m.content,
            cotContent: m.cotContent || undefined,
          })) as Message[];
        }
        return [];
      } catch (e) {
        console.error("[会话切换] 加载详情失败", e);
        return [];
      } finally {
        this.switching = false;
      }
    },

    /** 显式新建会话（可选，也可在后端懒创建） */
    async createNewConversation(kbIds?: string): Promise<string | null> {
      try {
        const res: any = await createConversation(kbIds);
        if (res && res.code === 0) {
          const newConv = res.data;
          this.currentConversationId = newConv.conversationId;
          // 刷新列表
          this.loadConversationList();
          return newConv.conversationId;
        }
      } catch (e) {
        console.error("[新建会话] 失败", e);
      }
      return null;
    },

    /** 归档会话 */
    async archiveConversation(conversationId: string) {
      try {
        await archiveConversation(conversationId);
        // 从列表中移除
        this.conversationList = this.conversationList.filter(
          (c) => c.conversationId !== conversationId
        );
        // 如果归档的是当前会话，清空 currentConversationId
        if (this.currentConversationId === conversationId) {
          this.currentConversationId = null;
        }
      } catch (e) {
        console.error("[归档会话] 失败", e);
      }
    },

    /** 更新标题 */
    async updateTitle(conversationId: string, title: string) {
      try {
        await updateConversationTitle(conversationId, title);
        // 更新列表中的标题
        const conv = this.conversationList.find(
          (c) => c.conversationId === conversationId
        );
        if (conv) conv.title = title;
      } catch (e) {
        console.error("[更新标题] 失败", e);
      }
    },
  },
});
