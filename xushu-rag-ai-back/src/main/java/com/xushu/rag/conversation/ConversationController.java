package com.xushu.rag.conversation;

import com.xushu.rag.common.ApplicationConstant;
import com.xushu.rag.common.BaseResponse;
import com.xushu.rag.common.ErrorCode;
import com.xushu.rag.context.BaseContext;
import com.xushu.rag.conversation.dto.ChatMessageDTO;
import com.xushu.rag.conversation.dto.ConversationDetailDTO;
import com.xushu.rag.conversation.dto.ConversationListItemDTO;
import com.xushu.rag.entity.ChatMessage;
import com.xushu.rag.entity.Conversation;
import com.xushu.rag.mapper.ChatMessageMapper;
import com.xushu.rag.mapper.ConversationMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 会话管理接口
 * <p>提供会话列表、详情、新建、归档等接口，支持前端历史会话切换。</p>
 *
 * @author Joseph
 */
@Tag(name = "ConversationController", description = "会话管理接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ConversationPersistenceService persistenceService;

    @Operation(summary = "新建会话", description = "创建一个新会话，返回 conversationId")
    @PostMapping
    public BaseResponse<ConversationListItemDTO> createConversation(
            @RequestParam(required = false) String kbIds) {
        Long userId = BaseContext.getCurrentId();
        String conversationId = userId + "_" + UUID.randomUUID().toString().substring(0, 8);

        persistenceService.createConversationIfAbsent(conversationId, userId, kbIds);
        persistenceService.saveEvent(conversationId, "CREATED", null, userId, "USER");

        Conversation conv = conversationMapper.selectByConversationId(conversationId);
        return new BaseResponse<>(0, toListItem(conv));
    }

    @Operation(summary = "会话列表", description = "当前用户会话列表（排除 ARCHIVED，按 update_time 倒序）")
    @GetMapping
    public BaseResponse<List<ConversationListItemDTO>> listConversations(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        Long userId = BaseContext.getCurrentId();
        int offset = (page - 1) * size;
        List<Conversation> conversations = conversationMapper.selectByUserId(userId, offset, size);
        List<ConversationListItemDTO> list = conversations.stream()
                .map(this::toListItem)
                .collect(Collectors.toList());
        return new BaseResponse<>(0, list);
    }

    @Operation(summary = "会话详情", description = "含全量消息（按 create_time 正序）")
    @GetMapping("/{conversationId}")
    public BaseResponse<ConversationDetailDTO> getConversationDetail(
            @PathVariable String conversationId) {
        Long userId = BaseContext.getCurrentId();
        Conversation conv = conversationMapper.selectByConversationId(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return new BaseResponse<>(ErrorCode.NOT_FOUND_ERROR);
        }

        List<ChatMessage> messages = chatMessageMapper.selectByConversationId(conversationId);
        List<ChatMessageDTO> messageDTOs = messages.stream()
                .map(this::toMessageDTO)
                .collect(Collectors.toList());

        ConversationDetailDTO detail = ConversationDetailDTO.builder()
                .conversationId(conv.getConversationId())
                .title(conv.getTitle())
                .status(conv.getStatus())
                .kbIds(conv.getKbIds())
                .createTime(conv.getCreateTime())
                .updateTime(conv.getUpdateTime())
                .messageCount(conv.getMessageCount())
                .messages(messageDTOs)
                .build();
        return new BaseResponse<>(0, detail);
    }

    @Operation(summary = "更新标题", description = "更新会话标题")
    @PatchMapping("/{conversationId}")
    public BaseResponse<String> updateTitle(
            @PathVariable String conversationId,
            @RequestParam String title) {
        Long userId = BaseContext.getCurrentId();
        Conversation conv = conversationMapper.selectByConversationId(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return new BaseResponse<>(ErrorCode.NOT_FOUND_ERROR);
        }
        conv.setTitle(title);
        conversationMapper.updateById(conv);
        return new BaseResponse<>(0, "ok");
    }

    @Operation(summary = "归档会话", description = "软删除（status → ARCHIVED）")
    @DeleteMapping("/{conversationId}")
    public BaseResponse<String> archiveConversation(@PathVariable String conversationId) {
        Long userId = BaseContext.getCurrentId();
        Conversation conv = conversationMapper.selectByConversationId(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return new BaseResponse<>(ErrorCode.NOT_FOUND_ERROR);
        }
        persistenceService.archiveConversation(conversationId);
        persistenceService.saveEvent(conversationId, "ARCHIVED", null, userId, "USER");
        return new BaseResponse<>(0, "ok");
    }

    private ConversationListItemDTO toListItem(Conversation conv) {
        return ConversationListItemDTO.builder()
                .conversationId(conv.getConversationId())
                .title(conv.getTitle() != null ? conv.getTitle()
                        : (conv.getFirstMessage() != null ? conv.getFirstMessage() : "新会话"))
                .firstMessage(conv.getFirstMessage())
                .lastMessage(conv.getLastMessage())
                .status(conv.getStatus())
                .updateTime(conv.getUpdateTime())
                .messageCount(conv.getMessageCount())
                .build();
    }

    private ChatMessageDTO toMessageDTO(ChatMessage m) {
        return ChatMessageDTO.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .cotContent(m.getCotContent())
                .category(m.getCategory())
                .toolName(m.getToolName())
                .retrievalSources(m.getRetrievalSources())
                .createTime(m.getCreateTime())
                .build();
    }
}
