# 软件工程课程资料平台

面向软件工程课程的资料管理与 RAG 问答平台，支持资料上传与版本管理、关键词检索、带出处的问答和复习练习。

## 本地运行

Windows 本地运行采用 JDK 21、Maven、H2、本地文件和 Caffeine。一个可执行 JAR 提供网页和 `/api`，默认端口 8088。

```powershell
.\scripts\build.cmd -RunTests
.\scripts\start-local.cmd
```

从 `deploy/local.env.example` 创建 `.env.local`，保留已有配置。首次管理员由环境受控初始化，没有固定默认口令；模型凭据仅用于后端。未配置模型仍可使用资料。非空进程环境优先于文件。

启动后打开 `http://localhost:8088/`，或运行只读检查 `scripts/check-local.cmd`。自定义环境文件传 `-EnvFile`，自定义检查地址传 `-BaseUrl`。Ctrl+C 停止；同一 H2 库只能运行一个实例。构建前先停止占用 target 的进程。

IDEA：构建后运行 `scripts/prepare-idea.cmd`，重新加载 Maven，再选本地 KnowledgeApplication (IDEA)。脚本准备配置及已构建页面的类路径。热更新另开终端运行 `scripts/dev.cmd`，5174 的 `/api` 代理采用同环境配置的后端端口。正式 JAR 无需 Vite。

脚本回归：`python test-assets/verify_deployment.py`。业务验收用法见 [test-assets/README.md](test-assets/README.md)，均使用独立测试目录和库。

## 上游来源与许可证

本项目基于 [Fast Knowledge](https://github.com/lixuanqun/fast-knowledge) 改造，导入版本及复用范围见 [UPSTREAM.md](UPSTREAM.md)。感谢 Fast Knowledge 贡献者。仓库保留了上游的 MIT 许可证与原版权声明，见 [LICENSE](LICENSE)。
