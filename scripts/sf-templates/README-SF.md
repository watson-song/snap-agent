# SnapAgent v__VERSION__ — SF 离线安装包

内网 CICD 专用安装包。jar 和 pom 打包在 `lib/` 目录内，不访问外部 Maven 仓库。

## 包内容

```
snap-agent-sf-install-v__VERSION__/
├── lib/                                    # Maven 本地文件仓库
│   └── com/watsontech/snapagent/
│       ├── snap-agent-core/__VERSION__/
│       │   ├── snap-agent-core-__VERSION__.jar
│       │   └── snap-agent-core-__VERSION__.pom
│       └── snap-agent-spring-boot-2x-starter/__VERSION__/
│           ├── snap-agent-spring-boot-2x-starter-__VERSION__.jar
│           └── snap-agent-spring-boot-2x-starter-__VERSION__.pom
├── pom-snippet.xml                         # 要粘贴到宿主 pom.xml 的片段
├── application-sf.yml                      # SF 默认配置（cc-switch 模型 + /app/deploy/skills）
├── INTEGRATION-AI.md                       # AI 可执行的完整集成指南
└── README-SF.md                            # 本文件
```

## 快速使用

1. 解压安装包到宿主项目根目录旁
2. 把 `lib/` 目录复制到宿主项目根目录（与 `pom.xml` 同级）
3. 按 `pom-snippet.xml` 在宿主 `pom.xml` 添加 `<repository>` 和 `<dependency>`
4. 按 `application-sf.yml` 在宿主 `application.yml` 添加 `snap-agent:` 配置
5. 详细步骤见 `INTEGRATION-AI.md`

## 默认配置

| 项 | 值 |
|----|----|
| LLM 代理 | `https://claudecode.sf-express.com/ccr`（cc-switch） |
| 模型 | `aliyun/glm-5.2` |
| Auth Token | `01414185`（Bearer） |
| Skill 上传目录 | `/app/deploy/skills` |
| 内置 Skill | health-check, database-query, redis-query |

## 版本

SnapAgent __VERSION__ | Apache 2.0
