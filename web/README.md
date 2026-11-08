# Fast Knowledge — Web 前端

Vue 3 + Vite + Element Plus 管理界面。

前端随单机 JAR 发布；构建与启动入口见 [根 README](../README.md)。

## 页面与路由

| 页面 | 路径 | 权限 |
|------|------|------|
| 概览 | `/dashboard` | 登录 |
| 知识库 | `/kbs`、`/kbs/:id` | 登录 |
| 智能检索 / 问答 / 对话 / 写文档 | `/search`、`/qa`、`/chat`、`/writer` | 登录 |
| 设置与隐私 | `/settings` | 登录 |
| **大模型配置** | `/settings/llm` | 管理员 |
| 用户管理 | `/users` | 管理员 |

## 开发

先构建单机包，并在项目根目录启动后端：

```powershell
.\scripts\build.ps1
.\scripts\start-local.ps1
```

在另一个终端、项目根目录启动前端开发服务器：

```powershell
.\scripts\dev.ps1    # http://localhost:5174
```

Vite 代理 `/api` → `http://localhost:8088`。两个终端分别用 Ctrl+C 停止；后端使用 H2、本地文件与 Caffeine。

## 构建

```powershell
.\scripts\build.ps1
```

在项目根目录调用该脚本；它会执行 `npm ci`、构建 `web/dist`，并把前端资源放入可执行 JAR。

单独构建静态资源时，在 `web` 目录运行 `npm ci`、`npm run build`，产物为 `web/dist/`。这不是独立的部署模式。

## 目录

```
web/src/
├── api/            # 接口（含 llm-config.ts）
├── views/          # 页面（settings/llm.vue 大模型配置）
├── stores/         # Pinia（config、auth）
└── router/         # 路由与 adminOnly 守卫
```

## 相关文档

- [根 README](../README.md)
- [单机配置样例](../deploy/local.env.example)
