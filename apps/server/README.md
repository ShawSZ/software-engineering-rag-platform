# Fast Knowledge — Server 模块

Java 21 + Spring Boot 3.5 知识库 API 服务，RAG 基于 LangChain4j。

## 单机运行

服务端按单机部署设计：H2 文件库、本地文件存储、进程内 Caffeine 缓存，不需要 MySQL、Redis、MinIO 或 Docker。

在项目根目录执行：

```powershell
.\scripts\build.ps1
.\scripts\start-local.ps1
```

课程平台的 API 前缀为 `/api`，默认端口为 `8088`。首次启动前，通过 `.env.local` 配置 `APP_ADMIN_USERNAME` 和 `APP_ADMIN_PASSWORD`。

模型与 Embedding 配置放在项目根目录 `.env.local`，样例见 [deploy/local.env.example](../../deploy/local.env.example)。未配置模型 Key 时应用仍可启动和管理资料。

## 核心能力

- JWT 认证、用户管理与知识库权限
- 文档上传、异步解析与分块索引
- 进程内向量检索（Lucene HNSW）与可选关键词召回
- 单轮 RAG 问答、引用来源与历史记录
- OpenAI 兼容 LLM / Embedding 接口

## Profile

| Profile | 用途 |
|---------|------|
| `bundle` | 单 Jar 托管前端静态资源，API 挂载 `/api/v1` |
| `h2` | 单机数据库、缓存与本地存储 |
| `minimal` | hash 伪向量，无外部 Embedding 时用于启动检查 |

## 测试

```powershell
# 在项目根目录执行，无需启动外部服务
.\scripts\build.ps1 -RunTests
```

该入口运行以 `Test` 结尾的单元测试及 `H2MinimalProfileBootTest`，排除以 `IntegrationTest` 结尾的外部服务集成测试。真实模型 Probe 和外部服务集成测试保留在源码中，另行准备其测试环境后执行，不属于单机部署前置条件。

## 相关文档

- [根 README](../../README.md)
- [本地运行](../../README.md#本地运行)
