# SnapAgent v__VERSION__ — SF 离线安装包

内网 CICD 专用安装包。jar 和 pom 打包在 `lib/` 目录内，不访问外部 Maven 仓库。

## 包内容

```
snap-agent-sf-install-v__VERSION__/
├── install-sf.sh                       # 一键安装脚本（复制 lib/ + 打印 AI 提示词）
├── lib/                                # Maven 本地文件仓库
│   └── com/watsontech/snapagent/
│       ├── snap-agent-core/__VERSION__/
│       │   ├── snap-agent-core-__VERSION__.jar
│       │   └── snap-agent-core-__VERSION__.pom
│       └── snap-agent-spring-boot-2x-starter/__VERSION__/
│           ├── snap-agent-spring-boot-2x-starter-__VERSION__.jar
│           └── snap-agent-spring-boot-2x-starter-__VERSION__.pom
├── pom-snippet.xml                     # 要粘贴到宿主 pom.xml 的片段
├── application-sf.yml                  # SF 默认配置（cc-switch 模型 + /app/deploy/skills）
├── INTEGRATION-AI.md                   # AI 可执行的完整集成指南
└── README-SF.md                        # 本文件
```

## 使用方式（两种）

### 方式一：一键脚本 + Claude Code（推荐）

```bash
# 1. 解压安装包
unzip snap-agent-sf-install-v__VERSION__.zip

# 2. 运行安装脚本，指向你的 Spring Boot 项目
bash snap-agent-sf-install-v__VERSION__/install-sf.sh /path/to/your-project

# 3. 脚本会复制 lib/ 到项目里，并打印一段 AI 提示词
# 4. 在项目里打开 Claude Code，粘贴那段提示词即可
```

脚本会把 `lib/`、`application-sf.yml`、`INTEGRATION-AI.md` 复制到项目根目录，
然后打印一段提示词。把提示词粘贴给 Claude Code，它会读 `INTEGRATION-AI.md`
自动完成 pom.xml 改依赖、application.yml 合并配置、Security 放行、DataSource
Bean 创建等全部代码改动。过程中会问你要工号填入 `auth-token`。

### 方式二：手动按指南操作

按 `INTEGRATION-AI.md` 的 8 个步骤逐步操作，适合不用 AI 编程助手的场景。

## 默认配置

| 项 | 值 |
|----|----|
| LLM 代理 | `https://claudecode.sf-express.com/ccr`（cc-switch） |
| 模型 | `aliyun/glm-5.2` |
| Auth Token | 使用者工号（Bearer，部署时由 AI 询问后填入） |
| Skill 上传目录 | `/app/deploy/skills` |
| 内置 Skill | health-check, database-query, redis-query |

## 版本

SnapAgent __VERSION__ | Apache 2.0
