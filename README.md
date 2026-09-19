# HKG-RAG

**HKG-RAG**（Hierarchical Knowledge Graph with Retrieval-Augmented Generation）是一个面向私域文档的检索增强问答框架，融合**层次知识图谱、多粒度检索与多智能体协同机制**。

HKG-RAG 将文档组织为**实体层—文档层—主题聚类层**的层次知识结构，并根据不同类型问题动态选择文档块检索、实体检索、三元组检索和主题检索，以提升复杂问答中的知识覆盖与多跳推理能力。

## Framework

HKG-RAG 的整体框架如下图所示：

![HKG-RAG Framework](figures/hkg_rag_framework.png)

## 技术栈

| 类别 | 技术 |
|---|---|
| 开发语言 | Java 17 |
| 后端框架 | Spring Boot 3.5.5、Spring AI 1.1.7 |
| 向量数据库 | Milvus 2.6.8 |
| 图数据库 | Neo4j |
| 大语言模型 | Qwen2.5-14B-Instruct |
| Embedding 模型 | Qwen3-Embedding-4B |
| 模型推理服务 | vLLM |

## 环境要求

- **JDK 17+**
- **Maven 3.6+**
- **Milvus 2.6+**
- **Neo4j 5.x**
- **LLM 服务**：Qwen2.5-14B-Instruct
- **Embedding 服务**：Qwen3-Embedding-4B

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/WJQ-7/HKG-RAG.git
cd HKG-RAG
```

### 2. 配置

修改 `src/main/resources/application.properties`，根据实际部署环境配置模型服务、数据库及相关参数。

```properties
# Server
server.port=8080

# Prompt language: zh | en
HKG-RAG.prompt.lang=en


# Milvus
# 向量数据库连接地址
milvus.host=127.0.0.1
milvus.port=19530


# Neo4j
# 用于存储层次知识图谱中的实体、文档、主题节点及其关系
spring.neo4j.uri=bolt://localhost:7672
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=<YOUR_PASSWORD>


# Qwen LLM
# HKG-RAG 主要使用的大语言模型服务
HKG-RAG.llm.qwen.base-url=http://localhost:8001
HKG-RAG.llm.qwen.api-key=<YOUR_API_KEY>
HKG-RAG.llm.qwen.model=/path/to/qwen2.5-14b-instruct


# DeepSeek LLM
# 用于模型评估任务
HKG-RAG.llm.deepseek.base-url=https://api.deepseek.com
HKG-RAG.llm.deepseek.api-key=<YOUR_API_KEY>
HKG-RAG.llm.deepseek.model=<MODEL_NAME>


# Embedding
# 用于文档、实体及主题的向量化表示
HKG-RAG.embedding.base-url=http://localhost:8002
HKG-RAG.embedding.api-key=<YOUR_API_KEY>
HKG-RAG.embedding.model=/path/to/qwen3-embedding-4b


# Chunk
# 文档分块大小及相邻分块重叠长度
HKG-RAG.chunk.max-size=1024
HKG-RAG.chunk.overlap-chunk-size=128


# Retrieval
# 检索权重及候选召回数量
HKG-RAG.retrieval.EXACT-WEIGHT=0.5
HKG-RAG.retrieval.BM25-WEIGHT=0.5
HKG-RAG.retrieval.VECTOR_WEIGHT=0.5
HKG-RAG.retrieval.TopK=5


# Agent
# 单次问答允许的最大工具调用轮数和 Token 预算
HKG-RAG.agent.max-tool-rounds=6
HKG-RAG.agent.max-total-tokens=24000


# Milvus Collections
# 配置实际使用的实体、文档和主题聚类 Collection 名称
HKG-RAG.milvus.Entity-Collection=<ENTITY_COLLECTION_NAME>
HKG-RAG.milvus.File-Collection=<FILE_COLLECTION_NAME>
HKG-RAG.milvus.Cluster-Collection=<CLUSTER_COLLECTION_NAME>
```



### 3. 数据准备

将原始文档放入：

```
src/main/resources/documents/
```

## 路径



### 阶段 1：文档预处理
HKG-RAG 提供两种文档提取方式。

#### 方式一：使用 Python 脚本提取
提供 Python 文档解析脚本: 
```text
src/main/resources/scripts/DocParser.py 
```
该脚本用于递归读取指定目录下的原始文档，并将文档内容统一转换为 JSON 格式。
首先安装 Python 依赖： 
```bash 
pip install "markitdown[all]" 
``` 
如需解析旧版 `.doc` 文件，还需安装： 
```bash 
pip install textract 
```
 运行方式： 
 ```bash 
 python src/main/resources/scripts/DocParser.py <输入文档目录> <输出JSON路径> 
 ```


#### 方式二：使用 Java 程序提取 
```text
src/test/java/com/bistu/hkgrag/WorkFlow/DocPreprocess.java
├── @Order(1) docParser()    # 提取 documents/ 下所有文档
└── @Order(2) fileChunk()    # 文档分块

### 阶段 2：文档抽取


```
### 阶段 2：文档抽取
```text
src/test/java/com/bistu/hkgrag/WorkFlow/ExtractionServiceTest.java
├── @Order(1) testExtractionService()   # LLM 抽取实体、属性及关系
├── @Order(2) testFileTopicExtraction() # LLM 抽取文档主题
└── @Order(3) test_topicCluster()       # 生成主题节点
```



### 阶段 3：数据库构建
数据库构建包括 **Milvus 向量数据库构建** 和 **Neo4j 知识图谱构建** 两部分。

相关代码位置：
```text
src/test/java/com/bistu/hkgrag/WorkFlow/DBServiceTest.java
```
#### 3.1 Milvus 向量数据库构建

按照 `@Order` 顺序运行以下方法：
| 步骤 | 内容                           |
|---|------------------------------|
| `@Order(1)` | `testMilvusInsertContentEntities()`| 向 Milvus **entity 表**插入内容实体  |
| `@Order(2)` | `testMilvusInsertDocumentEntities()`| 向 Milvus **file 表**插入文档实体    |
| `@Order(3)` | `testMilvusInsertClusterEntities()`| 向 Milvus **cluster 表**插入聚类实体 |
| 后续步骤 | 向 Neo4j 插入实体节点与关系（构成 HKG）    |

#### 3.2 Neo4j 知识图谱构建

完成 Milvus 数据写入后，继续按照 `@Order` 顺序运行以下方法：
| 步骤 | 方法 | 输入数据 | 功能 |
|---|---|---|---|
| `@Order(4)` | `test_insertEntities()` | 将实体节点写入 Neo4j |
| `@Order(5)` | `test_createRelations()`| 在 Neo4j 中创建实体之间的关系 |

### 阶段 4：评估

#### 4.1 BISTU-OfficeQA 数据集评估

相关代码位置：
```text
src/test/java/com/bistu/hkgrag/Evaluation/Evaluation.java
```
评估数据位于：

```text
src/main/resources/data/datasets/Bistu/qa.jsonl
```

运行 `runAllMethods()`，该方法会依次完成：

| 步骤 | 内容 |
|---|---|
| Step 1 | 使用 NaiveRAG 和 HKG-RAG 对测试集问题生成答案 |
| Step 2 | 保存各方法生成的 QA 结果 |
| Step 3 | 计算 EM、F1、ROUGE-L 和 Recall 等指标 |
| Step 4 | 生成各方法的评估结果及汇总对比文件 |

也可以分步运行：

| 方法 | 功能 |
|---|---|
| `testSet()` | 使用 HKG-RAG 对 BISTU-OfficeQA 测试集生成回答 |
| `evaluation()` | 对已有 QA 结果计算自动评估指标 |
| `testAssessService()` | 使用 LLM 对已有问答结果进行模型评估 |
| `runAllMethods()` | 一键完成多种 RAG 方法的答案生成、指标评估和结果汇总 |

#### 4.2 UltraDomain 数据集评估

UltraDomain 数据集提供独立的完整运行流程，相关代码位置：

```text
src/test/java/com/bistu/hkgrag/WorkFlow/UltraDomainPipeline.java
```

当前支持以下四个子数据集：

```text
agriculture
cs
legal
mix
```

`UltraDomainPipeline` 将数据处理、数据库构建和评估划分为三个阶段：

| 阶段 | 方法 | 内容 |
|---|---|---|
| Stage 1 | `stage1_Extraction()` | 文档分块、Topic 提取、实体关系抽取及主题聚类 |
| Stage 2 | `stage2_DatabaseBuild()` | 构建 Milvus 向量数据库和 Neo4j 知识图谱 |
| Stage 3 | `stage3_Evaluation()` | 在 QA 数据集上运行不同 RAG 方法并生成评估结果 |

各数据集均提供对应的分阶段运行入口，例如：

```text
stage1_Agriculture()
stage2_Agriculture()
stage3_Agriculture()

stage1_CS()
stage2_CS()
stage3_CS()

stage1_Legal()
stage2_Legal()
stage3_Legal()

stage1_Mix()
stage2_Mix()
stage3_Mix()
```

其中，`stage3_Evaluation()` 当前运行以下方法：

| 方法 | 内容 |
|---|---|
| `NaiveRAG` | 基于向量检索的基础 RAG |
| `HKGRAG` | 完整 HKG-RAG |
| `HKGRAG-without-MultiAgent` | 去除多智能体协同机制的 HKG-RAG |

评估阶段读取各领域目录下的 `qa.jsonl`，生成对应方法的 QA 结果，并计算 EM、F1、ROUGE-L 和 Recall 等指标。

此外，也可以直接运行完整流水线：

```text
fullPipeline_Agriculture()
fullPipeline_CS()
fullPipeline_Legal()
fullPipeline_Mix()
```

`fullPipeline()` 会依次执行：

```text
数据抽取
   ↓
数据库构建
   ↓
问答评估
```

因此，在使用 UltraDomain 数据集时，无需再单独执行前述 BISTU-OfficeQA 的文档预处理、文档抽取和数据库构建流程。

### 启动问答服务

```bash
mvn spring-boot:run
```

调用接口：

```bash
curl -X POST "http://localhost:8080/api/chat?message=你的问题&sessionId=1"
```

## 项目结构

```text
src/main/java/com/bistu/hkgrag/
├── config/                  # AIConfig(模型客户端)、MilvusConfig、PromptConfig(中英双语提示词)、WebConfig
├── dao/
│   ├── milvus/              # Milvus 数据访问层（entity/file/cluster 三表混合检索）
│   └── neo4j/               # Neo4j 数据访问层（HKG 实体节点与关系）
├── llm/
│   ├── agent/               # 多智能体：QuestionAnalysisAgent、IntentAgent、EvaluationAgent、
│   │                        #   MultiAgent(编排)、RetrievalTools(检索工具)、EvidenceStore(证据缓存)
│   ├── controller/          # ChatController（POST /api/chat）
│   ├── evaluation/          # Evaluator、EvaluationUtils（EM/F1/RougeL/Recall）
│   ├── service/             # ExtractionService(抽取)、GenClusterService(聚类)、
│   │                        #   RerankService(重排)、BaseLineService(NaiveRAG)、GenTestSetService(测试集)
│   └── model/               # 领域模型
└── preprocess/
    ├── parser/              # 文档解析（PDF/Word/Excel/Rar → Markdown）
    └── clean/               # 文本清洗、分块（FileChunkService）
```

## 数据集

项目相关数据位于 `src/main/resources/data/datasets/`：

| 目录 | 说明 |
|---|---|
| **BISTU-OfficeQA** | 本文构建的中文私域问答数据集，包含原始文档及对应问答数据 |
| **Bistu** | BISTU-OfficeQA 数据集上的实验数据及中间结果 |
| **UltraDomain** | UltraDomain 公开基准数据集上的实验数据及中间结果 |