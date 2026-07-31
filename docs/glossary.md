# SnapAgent Naming Convention Glossary

> This glossary maps deprecated/old class and SPI names to their current names
> following the 2.x architecture refactor. Use the **Current Name** in all new
> documentation and code. Old names are retained here only as a historical
> reference and migration aid.

## Knowledge Subsystem

| Old Name (v0.7) | Current Name (v2.x) | Notes |
|-----------------|---------------------|-------|
| `KnowledgeBase` | `VectorStore` | Renamed in 2.x refactor. `VectorStore` replaces the in-memory keyword-search `KnowledgeBase` with a vector similarity search SPI (`add`/`delete`/`similaritySearch`). |
| `KnowledgeFragment` | `Document` | Unified with the vector store model. `Document` carries `id` + `content` + `metadata` + `embedding`. |
| `KnowledgeSearcher` | `DocumentRetriever` | RAG pipeline integration. `DocumentRetriever.retrieve(query, topK)` replaces `KnowledgeSearcher.score(query, fragment)`. |
| `KnowledgeInjector` | `RetrievalAugmentationAdvisor` | Advisor pattern. Was a `SystemPromptExtender`; now an `Advisor` (order=200) executing transform → retrieve → augment before `agent_node`. |
| `KnowledgeSource` | `DocumentReader` + `KnowledgeSourceConfig` | Split into reader SPI + config. `KnowledgeETLPipeline` reads via `DocumentReader`, splits via `TokenTextSplitter`, embeds via `EmbeddingModel`, writes to `VectorStore`. |
| `SimpleKeywordSearcher` | `VectorStoreDocumentRetriever` | Default `DocumentRetriever` delegates to `VectorStore.similaritySearch`. |
| `MarkdownKnowledgeSource` | `DocumentReader` (Markdown) | Markdown reading folded into the ETL pipeline's `DocumentReader` step. |
| `KnowledgeSedimentationExtractor` | `KnowledgeSedimentationService` | Extracts Q&A `Document` from `IssueClosure`, embeds, and writes to `VectorStore`. |
| `SystemPromptExtender` | `Advisor` | Generalized. `Advisor` provides `beforeNode`/`afterNode` hooks covering context injection and interception. |
| `ProjectContextExtender` | `ProjectContextAdvisor` | Renamed as part of the `Advisor` generalization. |
| `KnowledgeController` | `KnowledgeController` | Retained; underlying search now calls `VectorStore.similaritySearch`. |

## Tool / Plugin Subsystem

| Old Name (v1.x) | Current Name (v2.x) | Notes |
|-----------------|---------------------|-------|
| `ToolProvider` SPI | `@Tool` + `ToolCallback` | Declarative annotations replace the provider interface. |
| `ToolDispatcher` | `ToolsNode` (graph node) | Tool dispatch is now a graph node executed by `GraphExecutor`. |
| `PluginRegistry` | `ToolCallbackRegistry` | Plugin hot-load retained; produces `ToolCallback[]` via `ToolCallbacks.from()`. `PluginDescriptor` retained for JAR plugin metadata. |
| `InMemoryPluginRegistry` | `ToolCallbackRegistryImpl` | Default in-memory implementation. |
| `ToolPlugin` / `ToolPluginRegistry` | `ToolCallback` / `ToolCallbackRegistry` | Metadata layer merged into the unified `ToolCallback` SPI. |
| `@ToolPluginAnnotation` | `@Tool` / `@ToolParam` | Declarative annotations replace YAML manifest. |

## Agent Runtime

| Old Name (v1.x) | Current Name (v2.x) | Notes |
|-----------------|---------------------|-------|
| `AgentExecutor` (for loop) | `GraphExecutor` + `ReActGraphFactory` | Linear ReAct loop replaced by graph runtime. |
| `SimpleWorkflowEngine` | `StateGraph` (YAML → graph) | Workflow engine merged into graph model. |
| `WorkflowDefinition` / `WorkflowStep` | `StateGraph` nodes/edges | — |
| `ConversationStore` | `ChatMemoryRepository` | Session history SPI aligned with Spring AI. |
