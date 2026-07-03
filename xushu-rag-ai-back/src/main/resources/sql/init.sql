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
-- Table structure for mcp_tool_registry
-- ----------------------------
DROP TABLE IF EXISTS `mcp_tool_registry`;
CREATE TABLE `mcp_tool_registry` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tool_name` VARCHAR(255) NOT NULL COMMENT '工具名称（如：员工信息查询、发送企微通知）',
    `description` VARCHAR(500) COMMENT '工具描述（AI根据描述判断何时调用，20字以内）',
    `tool_category` VARCHAR(50) DEFAULT 'default' COMMENT '工具分类：calculation/reference/operation/default',
    `endpoint` VARCHAR(500) COMMENT 'MCP Server端点URL（真实对接时填写）',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'MEDIUM' COMMENT '风险等级：LOW/MEDIUM/HIGH',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    `create_time` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_enabled` (`enabled`),
    INDEX `idx_tool_category` (`tool_category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP工具注册表';


-- ----------------------------
-- Insert default data
-- ----------------------------
INSERT INTO `knowledge_base` (`id`, `name`, `description`, `parent_id`, `status`, `create_time`, `update_time`)
VALUES (1, '通用知识库', '默认通用知识库，存放未分类文档', NULL, 'ACTIVE', NOW(), NOW());

INSERT INTO `prompt_template` (`id`, `kb_id`, `name`, `template_content`, `description`, `template_type`, `variables`, `status`, `is_default`, `create_time`, `update_time`)
VALUES (1, NULL, '通用模板', '你是{kb_name}知识库的智能助手，请根据以下上下文回答问题：\n\n上下文：{context}\n\n问题：{question}\n\n请仅基于上下文回答，不要引入外部知识。', '默认模板，用于知识检索类问答', 'default', '[\"context\", \"question\", \"kb_name\"]', 'ACTIVE', 1, NOW(), NOW());

INSERT INTO `prompt_template` (`kb_id`, `name`, `template_content`, `description`, `template_type`, `variables`, `status`, `is_default`, `create_time`, `update_time`)
VALUES (NULL, '操作执行模板', '你是操作助手，请根据用户的要求判断需要执行什么操作。\n\n如果用户尚未明确确认执行，请描述操作详情和所需参数，在回答末尾征求用户确认。\n如果用户已确认（如回复\"是\"\"确定\"\"执行\"），请直接整理操作参数。\n\n用户请求：{question}', '用于执行操作类任务，如信息查询、合同签署、流程办理等', 'operation', '[\"question\"]', 'ACTIVE', 1, NOW(), NOW());