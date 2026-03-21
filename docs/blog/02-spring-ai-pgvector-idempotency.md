# Spring AI 与 PgVector 实践：彻底解决向量数据库的重复插入与同步问题

在使用 Spring AI 结合 PgVector 作为知识库（RAG）后端时，如果系统每次重启都全量加载文档，常常会导致数据重复插入。本文探讨了其根本原因，并提供基于 Hash 的幂等同步方案。

## 问题复现：首次启动与重启的“重复幽灵”

在初始实现中，我们的 `VectorStoreConfig` 每次都会执行 `loadDocuments -> enrichDocuments -> vectorStore.add(documents)`。
结果发现，不仅系统重启后会出现重复，甚至在**第一次启动**时，同一张表里也出现了内容和元数据完全相同，只有 ID 不同的向量记录。

## 根因分析：Spring Bean 与共享表

一开始，我们怀疑是 Spring Bean 多次初始化的问题。但实际上，Spring Bean 在单例模式下只初始化一次。导致重复的真正原因在于：
1. **多 Bean 共享数据表**：我们的系统中有多个配置类（如 `KnowledgeVectorStoreConfig` 和 `DiaryVectorStoreConfig`），它们都向同一个 PostgreSQL 表 `public.vector_store` 写入数据。如果在写入时不作命名空间（Namespace）隔离，逻辑很容易相互干扰。
2. **缺乏幂等性（Idempotency）**：Spring AI 的 `.add()` 方法默认是“追加（Append）”模式，在 Chunk（文档切片）级别缺乏唯一约束或联合唯一键检查。

## 解决思路：基于 Hash 指纹的增量同步方案

为了实现目录文件与数据库的严格同步（包括新增、修改、删除），我们引入了基于 `chunk_hash` 的增量同步机制：

### 1. 注入强语义元数据
在加载文档（`DocumentLoader`）时，我们为每个分块注入关键元数据：
* `source`: 标明数据来源（如 `'knowledge'` 或 `'diary'`）。
* `filename`: 标明所属文件。
* `chunk_index`: 标明该块在文件中的序号。

### 2. 生成确定性的 Chunk Hash
在执行 `.add()` 之前，我们通过 SHA-256 算法计算一个特征值：
```java
String hashInput = filename + "_" + chunkIndex + "_" + content;
String chunkHash = DigestUtils.sha256Hex(hashInput);
```
将这个 `chunk_hash` 存入 Document 的 Metadata 中。

### 3. 三步同步算法
启动时的加载逻辑改造为：
1. **清理已删除的文件**：遍历本地目录，拿到所有存在的文件名列表。执行 SQL `DELETE`，将数据库中 `source = 'knowledge'` 且不在本地文件列表中的记录删除。
2. **过滤已存在的块**：查询数据库中当前 `source` 下所有的 `chunk_hash` 集合。
3. **增量添加**：遍历本地生成的 Document 列表，过滤掉 `chunk_hash` 已经存在于数据库的记录，最后仅将**新增或修改**的 Document 执行 `.add()`。

## 避坑指南：PostgreSQL `jsonb` 类型转换异常

在实现中，如果尝试使用原生 SQL 更新现存旧数据的元数据（例如为没有 `source` 的数据补充 `source`），容易踩到类型转换的坑：
```sql
-- 错误示例：会抛出 BadSqlGrammarException: ERROR: COALESCE could not convert type jsonb to json
UPDATE public.vector_store 
SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{source}', '"knowledge"', true) 
WHERE COALESCE(metadata->>'source','') = '';
```
原因在于 PgVector 创建表时，默认的 `metadata` 列可能是原生的 `json` 而不是 `jsonb`，两者在执行 `COALESCE` 和 `jsonb_set` 时不兼容。
**更优雅的解法**：废弃 `UPDATE` 操作。在我们的 `SELECT` 查询中，利用原生 SQL 语法（`WHERE metadata->>'source' IS NULL OR metadata->>'source' = 'knowledge'`）来兼容旧数据，并在后续的新增流程中通过 Spring AI 的 API 规范地写入元数据。
