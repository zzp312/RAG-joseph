## 介绍文档：
https://www.yuque.com/geren-t8lyq/ncgl94/rswl890k7817rol1?singleDoc# 《基于RAG技术的个人知识库AI问答系统实战》

## SuperSQL 集成

本项目已集成 SuperSQL 框架，这是一个基于生成式大模型和 RAG 技术的 Java 框架，专注于将自然语言转换为 SQL 查询。

### 主要特性
- **自然语言转 SQL**：自动将自然语言查询转换为精确的 SQL 语句
- **RAG 训练**：通过深度学习数据库结构提高 SQL 生成准确性和效率
- **类型安全**：利用 Java 泛型进行编译时类型检查
- **多数据库支持**：兼容各种主流数据库系统
- **性能优化**：设计注重高效执行同时保持可读性

### 配置
在 `application.yml` 中配置 SuperSQL：

```yaml
super-sql:
  init-train: false
```

### 使用示例
项目中提供了 SuperSqlExampleService 和 SuperSqlController 作为使用示例，请参考相关代码了解如何使用 SuperSQL 框架。