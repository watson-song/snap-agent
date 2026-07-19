# Releasing SnapAgent / 发版指南

本文档描述如何将 SnapAgent 发布到 **Maven Central** 和 **GitHub Releases**。

This document describes how to release SnapAgent to **Maven Central** and **GitHub Releases**.

---

## 中文

### 背景

- **OSSRH 已于 2025-06-30 下线**，所有发布走新的 [Central Publisher Portal](https://central.sonatype.com)
- 命名空间：`cn.watsontech`（groupId: `cn.watsontech.snapagent`）
- 发布的构件：`snap-agent-core`、`snap-agent-spring-boot-2x-starter`、`snap-agent-client`（demo 不发布）
- 发布插件：`central-publishing-maven-plugin`（见根 `pom.xml` 的 `release` profile）

### 一次性配置

#### 1. Central Portal User Token

1. 登录 https://central.sonatype.com → 确认 namespace `cn.watsontech` 为 Active
2. **Account → Generate User Token**，得到一对 `username` / `password`（Token，不是登录密码）
3. Token 就是凭据，**不要提交到任何仓库或贴在公开渠道**

#### 2. GPG 密钥（签名是 Central 强制要求）

```bash
brew install gnupg
gpg --full-generate-key          # RSA 4096，设置 passphrase
gpg --list-secret-keys --keyid-format=long

# 上传公钥（Portal 会去 keyserver 校验）
KEY_ID=<你的KEY_ID>
gpg --keyserver keys.openpgp.org     --send-keys $KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys $KEY_ID
```

#### 3. GitHub Secrets（CI 发布方式，推荐）

仓库 **Settings → Secrets and variables → Actions** 添加 4 个 Secret：

| Secret | 内容 |
|---|---|
| `CENTRAL_USERNAME` | Portal User Token 的 username |
| `CENTRAL_PASSWORD` | Portal User Token 的 password |
| `GPG_PRIVATE_KEY` | `gpg --export-secret-keys --armor <KEY_ID>` 的完整输出（含 BEGIN/END 行） |
| `GPG_PASSPHRASE` | GPG 私钥密码 |

#### 4.（可选）本机 settings.xml — 仅本地发布时需要

```xml
<servers>
  <server>
    <id>central</id>
    <username>PORTAL_TOKEN_USERNAME</username>
    <password>PORTAL_TOKEN_PASSWORD</password>
  </server>
</servers>
<mirrors>
  <mirror>
    <id>aliyunmaven</id>
    <!-- 关键：不能用 mirrorOf=*，否则阿里云镜像会拦截发往 central.sonatype.com 的部署请求 -->
    <mirrorOf>central,!central</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
</mirrors>
```

### 发版流程（推荐：CI 发布）

```bash
# 一键完成：改版本号 → 跑测试 → commit → tag → 准备下一个 SNAPSHOT
./scripts/release.sh 0.3.0 --next 0.4.0

# 推送后，GitHub Actions 自动并行执行两个 workflow：
git push origin dev && git push origin v0.3.0
```

| Workflow | 触发 | 作用 |
|---|---|---|
| `maven-central.yml` | tag `v*` | GPG 签名 + 发布到 Maven Central（autoPublish） |
| `release.yml` | tag `v*` | 创建 GitHub Release（jar + standalone pom + install.sh） |

发布后约 15~30 分钟全球可拉取。验证：

- https://central.sonatype.com/artifact/cn.watsontech.snapagent/snap-agent-core
- https://repo1.maven.org/maven2/cn/watsontech/snapagent/

### 发版流程（备选：本地发布）

需要本机配好 GPG 和 settings.xml（见上方"一次性配置 4"）：

```bash
./scripts/release.sh 0.3.0 --deploy-local
```

`--deploy-local` 会在本地执行 `mvn clean deploy -Prelease`，GPG 密码通过 gpg-agent 弹窗输入（或用 `-Dgpg.pinentry-mode=loopback -Dgpg.passphrase=xxx`）。

### release.sh 参数

| 参数 | 说明 |
|---|---|
| `<version>` | 必填，x.y.z 格式（如 `0.3.0`），不带 `-SNAPSHOT` |
| `--next <x.y.z>` | 发版后自动切到下一个开发版本（自动加 `-SNAPSHOT`）并单独 commit |
| `--deploy-local` | 本地直接部署到 Central Portal（跳过 CI） |
| `--skip-tests` | 跳过测试（不推荐） |
| `--dry-run` | 只打印将执行的命令，不做任何改动 |

### 失败排查

| 症状 | 原因与解决 |
|---|---|
| `401 Unauthorized` | Token 错或用了登录密码。重新生成 User Token |
| `Missing signature` | GPG 公钥未上传 keyserver；或 CI 的 `GPG_PRIVATE_KEY` 不完整 |
| 部署请求被镜像拦截 | settings.xml 的 `mirrorOf=*` 必须改为 `central,!central` |
| Portal 校验卡在 pending | 首次发布较慢，去 Portal 网页看 Deployment 状态和具体报错 |
| tag 推错 | `git push origin :refs/tags/vX.Y.Z` 删远端 tag；Central 上已发布的版本**不可删除/覆盖**，只能发新版本号 |

> ⚠️ **Maven Central 上已发布的版本不可修改、不可删除**。版本号发错只能递增再发。

---

## English

### Background

- **OSSRH was shut down on 2025-06-30**; all publishing goes through the new [Central Publisher Portal](https://central.sonatype.com)
- Namespace: `cn.watsontech` (groupId: `cn.watsontech.snapagent`)
- Published artifacts: `snap-agent-core`, `snap-agent-spring-boot-2x-starter`, `snap-agent-client` (the demo module is not published)
- Publishing plugin: `central-publishing-maven-plugin` (see the `release` profile in the root `pom.xml`)

### One-time Setup

#### 1. Central Portal User Token

1. Log in to https://central.sonatype.com and confirm namespace `cn.watsontech` is Active
2. **Account → Generate User Token** — you get a `username`/`password` pair (a token, NOT your login password)
3. The token is a credential — **never commit it or paste it in public channels**

#### 2. GPG Key (signing is mandatory for Central)

```bash
brew install gnupg
gpg --full-generate-key          # RSA 4096, set a passphrase
gpg --list-secret-keys --keyid-format=long

# Upload the public key (the Portal verifies signatures against keyservers)
KEY_ID=<your-key-id>
gpg --keyserver keys.openpgp.org     --send-keys $KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys $KEY_ID
```

#### 3. GitHub Secrets (CI publishing, recommended)

Repo **Settings → Secrets and variables → Actions**, add 4 secrets:

| Secret | Content |
|---|---|
| `CENTRAL_USERNAME` | Portal User Token username |
| `CENTRAL_PASSWORD` | Portal User Token password |
| `GPG_PRIVATE_KEY` | Full output of `gpg --export-secret-keys --armor <KEY_ID>` (including BEGIN/END lines) |
| `GPG_PASSPHRASE` | GPG private key passphrase |

#### 4. (Optional) Local settings.xml — only needed for local publishing

```xml
<servers>
  <server>
    <id>central</id>
    <username>PORTAL_TOKEN_USERNAME</username>
    <password>PORTAL_TOKEN_PASSWORD</password>
  </server>
</servers>
<mirrors>
  <mirror>
    <id>aliyunmaven</id>
    <!-- Critical: mirrorOf=* would intercept deploy requests to central.sonatype.com -->
    <mirrorOf>central,!central</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
</mirrors>
```

### Release Flow (recommended: CI publishing)

```bash
# One command: bump version → run tests → commit → tag → prepare next SNAPSHOT
./scripts/release.sh 0.3.0 --next 0.4.0

# Push; two GitHub Actions workflows run in parallel:
git push origin dev && git push origin v0.3.0
```

| Workflow | Trigger | Purpose |
|---|---|---|
| `maven-central.yml` | tag `v*` | GPG-sign and publish to Maven Central (autoPublish) |
| `release.yml` | tag `v*` | Create the GitHub Release (jars + standalone POMs + install.sh) |

Artifacts become globally available in ~15–30 minutes. Verify at:

- https://central.sonatype.com/artifact/cn.watsontech.snapagent/snap-agent-core
- https://repo1.maven.org/maven2/cn/watsontech/snapagent/

### Release Flow (alternative: local publishing)

Requires local GPG and settings.xml (see "One-time Setup 4"):

```bash
./scripts/release.sh 0.3.0 --deploy-local
```

`--deploy-local` runs `mvn clean deploy -Prelease` locally; the GPG passphrase is prompted via gpg-agent (or use `-Dgpg.pinentry-mode=loopback -Dgpg.passphrase=xxx`).

### release.sh Options

| Option | Description |
|---|---|
| `<version>` | Required, x.y.z format (e.g. `0.3.0`), no `-SNAPSHOT` suffix |
| `--next <x.y.z>` | After release, switch to the next dev version (`-SNAPSHOT` appended) in a separate commit |
| `--deploy-local` | Deploy to Central Portal from your machine (skip CI) |
| `--skip-tests` | Skip tests (not recommended) |
| `--dry-run` | Print commands without changing anything |

### Troubleshooting

| Symptom | Cause & Fix |
|---|---|
| `401 Unauthorized` | Wrong token or login password used. Regenerate a User Token |
| `Missing signature` | Public key not uploaded to a keyserver; or CI `GPG_PRIVATE_KEY` is incomplete |
| Deploy intercepted by mirror | Change `mirrorOf=*` to `central,!central` in settings.xml |
| Portal validation stuck at pending | First releases are slow — check the Deployment status in the Portal UI |
| Wrong tag pushed | Delete remote tag: `git push origin :refs/tags/vX.Y.Z`. Published Central versions **cannot be deleted or overwritten** — bump and re-release |

> ⚠️ **Versions published to Maven Central are immutable.** A wrong release can only be superseded by a newer version number.
