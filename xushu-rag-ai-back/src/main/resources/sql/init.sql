-- MySQL 5.7 版本适配

-- ----------------------------
-- Table structure for vector_store
-- ----------------------------
DROP TABLE IF EXISTS `vector_store`;
CREATE TABLE `vector_store` (
                                `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                `content` TEXT,
                                `metadata` JSON,
                                `embedding` JSON COMMENT '向量数据，存储为JSON数组，原PostgreSQL为vector(1536)',
                                PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user` (
                           `id` INT NOT NULL AUTO_INCREMENT,
                           `name` VARCHAR(255) NOT NULL COMMENT '姓名',
                           `user_name` VARCHAR(255) NOT NULL COMMENT '用户名',
                           `password` VARCHAR(255) NOT NULL COMMENT '密码',
                           `phone` VARCHAR(255) NOT NULL COMMENT '手机号',
                           `sex` VARCHAR(255) NOT NULL COMMENT '性别',
                           `id_number` VARCHAR(255) NOT NULL COMMENT '身份证号',
                           `status` INT NOT NULL DEFAULT 1 COMMENT '状态 0：禁用 1：启用',
                           `create_time` DATE COMMENT '创建时间',
                           `update_time` DATE COMMENT '更新时间',
                           `create_user` BIGINT COMMENT '创建人',
                           `update_user` BIGINT COMMENT '修改人',
                           PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=666498 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` (`id`, `name`, `user_name`, `password`, `phone`, `sex`, `id_number`, `status`, `create_time`, `update_time`, `create_user`, `update_user`)
VALUES (666497, '管理员', 'admin', '21232f297a57a5a743894a0e4a801fc3', '13800138000', '男', '11010519491231002X', 1, '2025-03-03', '2025-03-03', NULL, NULL);


-- ----------------------------
-- Table structure for ali_oss_file
-- ----------------------------
DROP TABLE IF EXISTS `ali_oss_file`;
CREATE TABLE `ali_oss_file` (
                                `id` BIGINT NOT NULL AUTO_INCREMENT,
                                `file_name` VARCHAR(255) COMMENT '文件名',
                                `url` VARCHAR(500) COMMENT '链接地址',
                                `vector_id` TEXT COMMENT '该文件分割出的多段向量文本ID',
                                `create_time` TIMESTAMP NULL DEFAULT NULL COMMENT '创建时间',
                                `update_time` TIMESTAMP NULL DEFAULT NULL COMMENT '更新时间',
                                PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阿里云OSS文件表';


-- ----------------------------
-- Table structure for log_info
-- ----------------------------
DROP TABLE IF EXISTS `log_info`;
CREATE TABLE `log_info` (
                            `id` BIGINT NOT NULL AUTO_INCREMENT,
                            `method_name` VARCHAR(255) COMMENT '方法名',
                            `class_name` VARCHAR(255) COMMENT '类目',
                            `request_time` DATE COMMENT '请求时间戳',
                            `request_params` TEXT COMMENT '请求参数',
                            `response` TEXT COMMENT '响应结果',
                            PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志信息表';


-- ----------------------------
-- Table structure for sensitive_word
-- ----------------------------
DROP TABLE IF EXISTS `sensitive_word`;
CREATE TABLE `sensitive_word` (
                                  `id` INT NOT NULL AUTO_INCREMENT,
                                  `word` VARCHAR(255) COMMENT '敏感词内容',
                                  `category` VARCHAR(255) COMMENT '敏感词类别',
                                  `status` VARCHAR(50) COMMENT '敏感词状态',
                                  `created_at` VARCHAR(50) COMMENT '创建时间戳',
                                  `updated_at` VARCHAR(50) COMMENT '更新时间戳',
                                  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词表';


-- ----------------------------
-- Table structure for word_frequency
-- ----------------------------
DROP TABLE IF EXISTS `word_frequency`;
CREATE TABLE `word_frequency` (
                                  `id` INT NOT NULL AUTO_INCREMENT,
                                  `word` VARCHAR(255) COMMENT '分词',
                                  `count_num` INT COMMENT '出现频次',
                                  `business_type` VARCHAR(255) COMMENT '业务类型',
                                  `create_time` DATE COMMENT '创建时间',
                                  `update_time` DATE COMMENT '更新时间',
                                  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='词频统计表';


-- ----------------------------
-- Table structure for sensitive_category
-- ----------------------------
DROP TABLE IF EXISTS `sensitive_category`;
CREATE TABLE `sensitive_category` (
                                      `id` INT NOT NULL AUTO_INCREMENT,
                                      `category_name` VARCHAR(255) COMMENT '分类名',
                                      `created_time` DATE COMMENT '创建时间',
                                      `update_time` DATE COMMENT '更新时间',
                                      `status` VARCHAR(50) COMMENT '状态',
                                      PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词分类表';


-- ----------------------------
-- Table structure for knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_base`;
CREATE TABLE `knowledge_base` (
                                  `id` BIGINT NOT NULL AUTO_INCREMENT,
                                  `name` VARCHAR(255) NOT NULL COMMENT '知识库名称',
                                  `description` VARCHAR(500) COMMENT '知识库描述',
                                  `parent_id` BIGINT DEFAULT NULL COMMENT '父知识库ID（预留多级分类）',
                                  `status` VARCHAR(50) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用，INACTIVE-禁用',
                                  `create_time` TIMESTAMP NULL DEFAULT NULL COMMENT '创建时间',
                                  `update_time` TIMESTAMP NULL DEFAULT NULL COMMENT '更新时间',
                                  PRIMARY KEY (`id`),
                                  INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库表';


-- ----------------------------
-- Table structure for document
-- ----------------------------
DROP TABLE IF EXISTS `document`;
CREATE TABLE `document` (
                            `id` BIGINT NOT NULL AUTO_INCREMENT,
                            `kb_id` BIGINT NOT NULL COMMENT '知识库ID',
                            `file_name` VARCHAR(255) NOT NULL COMMENT '文件名',
                            `original_name` VARCHAR(255) COMMENT '原始文件名',
                            `file_path` VARCHAR(500) COMMENT '文件存储路径',
                            `file_type` VARCHAR(50) COMMENT '文件类型：pdf、doc、docx、xlsx、xls、txt、md',
                            `version` VARCHAR(50) NOT NULL COMMENT '版本号：v{major}.{minor}.{timestamp}',
                            `status` VARCHAR(50) DEFAULT 'PROCESSING' COMMENT '状态：UPLOADING-上传中，PROCESSING-处理中，COMPLETED-完成，FAILED-失败',
                            `vector_id` TEXT COMMENT '该文件分割出的多段向量文本ID',
                            `url` VARCHAR(500) COMMENT '文件访问链接',
                            `create_time` TIMESTAMP NULL DEFAULT NULL COMMENT '创建时间',
                            `update_time` TIMESTAMP NULL DEFAULT NULL COMMENT '更新时间',
                            PRIMARY KEY (`id`),
                            INDEX `idx_kb_id` (`kb_id`),
                            INDEX `idx_version` (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';


-- ----------------------------
-- Table structure for prompt_template
-- ----------------------------
DROP TABLE IF EXISTS `prompt_template`;
CREATE TABLE `prompt_template` (
                                   `id` BIGINT NOT NULL AUTO_INCREMENT,
                                   `kb_id` BIGINT COMMENT '知识库ID（NULL表示全局模板）',
                                   `name` VARCHAR(255) NOT NULL COMMENT '模板名称',
                                   `template_content` TEXT NOT NULL COMMENT '模板内容',
                                   `description` VARCHAR(500) COMMENT '模板描述（用于意图分类语义匹配）',
                                   `template_type` VARCHAR(50) DEFAULT 'default' COMMENT '模板类型：calculation/reference/operation/chitchat/default',
                                   `variables` JSON COMMENT '支持的变量列表',
                                   `status` VARCHAR(50) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用，INACTIVE-禁用',
                                   `is_default` TINYINT(1) DEFAULT 0 COMMENT '是否默认模板：0-否，1-是',
                                   `create_time` TIMESTAMP NULL DEFAULT NULL COMMENT '创建时间',
                                   `update_time` TIMESTAMP NULL DEFAULT NULL COMMENT '更新时间',
                                   PRIMARY KEY (`id`),
                                   INDEX `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提示词模板表';


-- ----------------------------
-- Table structure for mcp_server_config
-- （替代原 mcp_tool_registry，工具由MCP Server自动暴露，无需手动注册）
-- ----------------------------
DROP TABLE IF EXISTS `mcp_server_config`;
CREATE TABLE `mcp_server_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `server_name` VARCHAR(255) NOT NULL COMMENT 'MCP服务名称（如：amap-maps、ziniu-local-server）',
    `description` VARCHAR(500) COMMENT '描述（用于提示词工具推荐，如：高德地图服务：地理编码+路线规划）',
    `server_category` VARCHAR(50) DEFAULT 'reference' COMMENT '服务分类：reference/operation/calculation',
    `config_json` JSON COMMENT 'MCP连接配置JSON（stdio: command+args+env / http: url+type）',
    `disabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否禁用：0-启用，1-禁用',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    `create_time` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_server_name` (`server_name`),
    INDEX `idx_enabled` (`enabled`),
    INDEX `idx_category` (`server_category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP服务器配置表';


-- ----------------------------
-- Insert default data
-- ----------------------------
INSERT INTO `knowledge_base` (`id`, `name`, `description`, `parent_id`, `status`, `create_time`, `update_time`)
VALUES (1, '通用知识库', '默认通用知识库，存放未分类文档', NULL, 'ACTIVE', NOW(), NOW());

INSERT INTO `prompt_template` (`id`, `kb_id`, `name`, `template_content`, `description`, `template_type`, `variables`, `status`, `is_default`, `create_time`, `update_time`)
VALUES (1, NULL, '通用模板', '你是{kb_name}知识库的智能助手，请根据以下上下文回答问题：\n\n上下文：{context}\n\n问题：{question}\n\n请仅基于上下文回答，不要引入外部知识。', '默认模板，用于知识检索类问答', 'default', '[\"context\", \"question\", \"kb_name\"]', 'ACTIVE', 1, NOW(), NOW());

INSERT INTO `prompt_template` (`kb_id`, `name`, `template_content`, `description`, `template_type`, `variables`, `status`, `is_default`, `create_time`, `update_time`)
VALUES (NULL, '操作执行模板', '你是操作助手，请根据用户的要求判断需要执行什么操作。\n\n如果用户尚未明确确认执行，请描述操作详情和所需参数，在回答末尾征求用户确认。\n如果用户已确认（如回复\"是\"\"确定\"\"执行\"），请直接整理操作参数。\n\n用户请求：{question}', '用于执行操作类任务，如信息查询、合同签署、流程办理等', 'operation', '[\"question\"]', 'ACTIVE', 1, NOW(), NOW());

-- 规划类模板（planning意图专用：基于知识库素材推理整合，支持多方案输出，主动推荐工具）
INSERT INTO `prompt_template` (`kb_id`, `name`, `template_content`, `description`, `template_type`, `variables`, `status`, `is_default`, `create_time`, `update_time`)
VALUES (NULL, '规划类模板', '你是"Joseph.zhou"知识库系统的规划助手。用户希望基于知识库内容进行规划、整合或方案设计。\n\n以下是知识库中检索到的素材：\n{context}\n\n【你的职责】\n基于上述素材进行规划、整合、推理，给出符合用户要求的方案。\n\n【工作规范】\n1. 素材使用：\n   - 优先使用知识库素材中的具体信息（景点介绍、政策规定、产品参数等）\n   - 引用素材时在末尾标注来源：`📚 参考来源：文件名 (版本xxx)`\n   - 素材中明确没有的信息，标注"[需调用工具确认]"，不要自行编造\n\n2. 工具推荐：\n   - 涉及路线/距离/时间/天气等实时数据时，主动推荐可用服务列表中的相应工具\n   - 推荐格式：「涉及具体路程，建议我调用路线规划服务查询，需要您提供：起点、终点」\n   - 不要在本次回复中直接执行工具，等用户确认后再调用\n\n3. 多方案输出：\n   - 如果用户要求多个方案，给出 2-3 个差异化方案\n   - 每个方案标注差异点（如"文化历史向"/"自然风光向"）\n   - 列出每个方案的理由和权衡点\n\n4. 图片输出：\n   - 按指令输出Markdown图片语法 ![描述](URL)\n\n5. 诚实原则：\n   - 素材完全无法支撑的规划，明确告知"知识库素材不足以支撑该规划"\n   - 不要用自身知识编造具体数据（距离、价格、时间）', '规划类意图专用模板：基于知识库素材推理整合，支持多方案输出，主动推荐工具', 'planning', '["context"]', 'ACTIVE', 0, NOW(), NOW());

-- MCP Server 示例数据
-- 高德地图（stdio模式，Windows需cmd /c 包裹）
INSERT INTO `mcp_server_config` (`server_name`, `description`, `server_category`, `config_json`, `disabled`)
VALUES ('amap-maps', '高德地图服务：支持地理编码、路线规划、周边搜索', 'reference',
        '{"command":"cmd","args":["/c","npx","-y","@amap/amap-maps-mcp-server"],"env":{"AMAP_MAPS_API_KEY":"your_key_here"}}', 0);

-- 紫牛本地业务服务（HTTP模式）
INSERT INTO `mcp_server_config` (`server_name`, `description`, `server_category`, `config_json`, `disabled`)
VALUES ('ziniu-local-server', '紫牛业务系统：支持公积金查询、员工信息查询、流程办理', 'operation',
        '{"url":"http://localhost:8081","type":"http"}', 0);

-- ==================== MCP Server 暴露（Phase 5b） ====================

-- Table: mcp_secret_key（MCP Server鉴权密钥表）
DROP TABLE IF EXISTS `mcp_secret_key`;
CREATE TABLE `mcp_secret_key` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `secret_key` VARCHAR(128) NOT NULL COMMENT 'MCP调用密钥',
  `name` VARCHAR(100) DEFAULT NULL COMMENT '密钥名称/描述',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/INACTIVE',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_secret_key` (`secret_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Server鉴权密钥表';

-- Table: mcp_upload_task（MCP异步上传任务表）
DROP TABLE IF EXISTS `mcp_upload_task`;
CREATE TABLE `mcp_upload_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` VARCHAR(64) NOT NULL COMMENT '任务UUID',
  `file_name` VARCHAR(500) NOT NULL COMMENT '文件名',
  `file_size` BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
  `file_url` VARCHAR(1000) DEFAULT NULL COMMENT '文件URL(原始URL或OSS地址)',
  `kb_id` BIGINT DEFAULT NULL COMMENT '目标知识库ID',
  `kb_name` VARCHAR(200) DEFAULT NULL COMMENT '知识库名称',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/PROCESSING/SUCCESS/FAILED',
  `stage` VARCHAR(30) DEFAULT NULL COMMENT '处理阶段: PARSING/CHUNKING/EMBEDDING/IMAGE_PROCESSING',
  `progress` INT DEFAULT 0 COMMENT '进度0-100',
  `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
  `error_stage` VARCHAR(30) DEFAULT NULL COMMENT '出错阶段',
  `caller_secret_key_id` BIGINT DEFAULT NULL COMMENT '调用方密钥ID',
  `vector_ids` TEXT DEFAULT NULL COMMENT '向量ID列表JSON',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `complete_time` DATETIME DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_status` (`status`),
  KEY `idx_kb_id` (`kb_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP异步上传任务表';

-- Table: mcp_call_log（MCP调用审计日志表）
DROP TABLE IF EXISTS `mcp_call_log`;
CREATE TABLE `mcp_call_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `tool_name` VARCHAR(100) NOT NULL COMMENT '工具名称',
  `caller_secret_key_id` BIGINT DEFAULT NULL COMMENT '调用方密钥ID',
  `arguments` TEXT DEFAULT NULL COMMENT '调用参数(截断2000字符)',
  `result` TEXT DEFAULT NULL COMMENT '返回结果(截断2000字符)',
  `duration_ms` BIGINT DEFAULT NULL COMMENT '耗时(毫秒)',
  `status` VARCHAR(20) NOT NULL COMMENT '状态: SUCCESS/FAILED',
  `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tool_name` (`tool_name`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP调用审计日志表';

-- 初始密钥数据（开发测试用）
INSERT INTO `mcp_secret_key` (`secret_key`, `name`, `status`) VALUES
('ziniu-mcp-dev-key-2026', '开发测试密钥', 'ACTIVE');


-- ==================== 对话持久化表 ====================

-- Table: conversation（对话会话表）
DROP TABLE IF EXISTS `conversation`;
CREATE TABLE `conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '会话唯一标识(userId_sessionId 或 UUID)',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题(首条消息截断50字)',
  `kb_ids` VARCHAR(255) DEFAULT NULL COMMENT '关联知识库ID列表(逗号分隔)',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/ESCALATED/HUMAN_SERVING/ARCHIVED',
  `escalate_reason` VARCHAR(50) DEFAULT NULL COMMENT '转人工原因: emotion_negative/manual/auto_high_risk',
  `human_agent_id` BIGINT DEFAULT NULL COMMENT '接入的客服坐席ID(预留)',
  `message_count` INT NOT NULL DEFAULT 0 COMMENT '消息总数(冗余,加速列表展示)',
  `first_message` VARCHAR(200) DEFAULT NULL COMMENT '首条消息摘要(列表展示)',
  `last_message` VARCHAR(200) DEFAULT NULL COMMENT '末条消息摘要(列表展示)',
  `token_total` INT DEFAULT 0 COMMENT '累计token消耗(成本分析)',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后活跃时间',
  `metadata` JSON DEFAULT NULL COMMENT '扩展元数据(浏览器/IP/渠道等)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversation_id` (`conversation_id`),
  KEY `idx_user_id_status` (`user_id`, `status`),
  KEY `idx_status_update` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';

-- Table: chat_message（对话消息表）
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `message_id` VARCHAR(64) NOT NULL COMMENT '消息唯一标识(UUID)',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '所属会话ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID(冗余,加速查询)',
  `role` VARCHAR(20) NOT NULL COMMENT '角色: USER/ASSISTANT/SYSTEM/TOOL/HUMAN_AGENT',
  `content` MEDIUMTEXT NOT NULL COMMENT '消息内容(支持Markdown/长文本)',
  `content_type` VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT '内容类型: TEXT/MARKDOWN/IMAGE/TOOL_CALL/STEP',
  `category` VARCHAR(30) DEFAULT NULL COMMENT '意图分类(仅ASSISTANT): calculation/reference/planning/chitchat等',
  `cot_content` TEXT DEFAULT NULL COMMENT 'CoT思考过程(仅ASSISTANT,深度思考展示)',
  `tool_name` VARCHAR(100) DEFAULT NULL COMMENT '工具名称(仅TOOL角色)',
  `tool_call_id` VARCHAR(64) DEFAULT NULL COMMENT '关联mcp_call_log的调用ID(仅TOOL角色)',
  `retrieval_sources` JSON DEFAULT NULL COMMENT '检索来源文档列表(仅ASSISTANT,含docId/page/score)',
  `tokens_input` INT DEFAULT NULL COMMENT '输入token数(仅ASSISTANT)',
  `tokens_output` INT DEFAULT NULL COMMENT '输出token数(仅ASSISTANT)',
  `duration_ms` BIGINT DEFAULT NULL COMMENT '生成耗时毫秒(仅ASSISTANT)',
  `is_escalated` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否触发转人工(0/1)',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`),
  KEY `idx_conversation_time` (`conversation_id`, `create_time`),
  KEY `idx_user_id_time` (`user_id`, `create_time`),
  KEY `idx_role_conversation` (`role`, `conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';

-- Table: conversation_event（会话事件审计表）
DROP TABLE IF EXISTS `conversation_event`;
CREATE TABLE `conversation_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '所属会话ID',
  `event_type` VARCHAR(30) NOT NULL COMMENT '事件类型: CREATED/AI_RESPONDED/ESCALATED/HUMAN_JOINED/HUMAN_REPLIED/BACK_TO_AI/ARCHIVED',
  `event_data` JSON DEFAULT NULL COMMENT '事件详情JSON',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作者ID(用户或坐席)',
  `operator_type` VARCHAR(20) DEFAULT NULL COMMENT '操作者类型: USER/HUMAN_AGENT/SYSTEM',
  `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
  PRIMARY KEY (`id`),
  KEY `idx_conversation_time` (`conversation_id`, `create_time`),
  KEY `idx_event_type_time` (`event_type`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话事件审计表';