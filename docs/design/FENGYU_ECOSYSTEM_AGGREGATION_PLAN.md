# FengYu 生态商店聚合 Skill / MCP 方案

> 状态：实施方案（仅文档）  
> 目标：让 Infinia Store 聚合 Claude、Codex 及其他 Agent 生态的 Skill / MCP，同时保证 FengYu 主项目可以稳定安装、启用、更新和回滚这些内容。  
> 范围：商店平台、FengYu 主项目安装器、兼容性导出；不包含本次代码实现。

## 1. 结论

> 当前实现约束（2026-08）：为控制商店硬盘占用，上游聚合采用“只索引元数据、下载时物化”。同步阶段只读取 marketplace / registry / Git tree 元数据，不下载仓库归档，不生成或保存 Artifact；FengYu 发起带票据的下载后，商店才获取所选上游内容，在请求级临时目录中通过受限文件流完成扫描和兼容打包，并以 `no-store` 返回。响应完成或失败时删除整个临时目录；上游虚拟条目不得进入 BlobStorage 或本地 Git 导出。

采用“**多上游适配器 + 统一规范化目录 + FengYu 原生安装协议 + 第三方兼容导出**”四层方案：

```text
Claude / Codex / MCP Registry / Git 仓库 / 其他目录
                         │
                 Upstream Adapter
                         │
             Normalized Skill / MCP Model
                         │
       扫描 → 审核 → 签名 → 版本化 → 依赖解析
                         │
       FengYu Native Install API  + 兼容生态出口
                         │
       FengYu SkillInstaller / McpInstaller
```

关键原则：

1. 上游格式只负责“发现和获取”，不能直接决定 FengYu 的安装行为。
2. 原始来源、提交号、版本、许可证、内容哈希必须永久保留，支持追溯和重新同步。
3. `SKILL.md` 作为跨 Agent 的基础内容格式；Claude/Codex 专属字段只能作为可选能力，不能破坏基础安装。
4. MCP 是“服务器配置 + 可选运行包”，不能简单当成一个可执行文件下载；默认禁用，机密只留在本地。
5. FengYu 的安装事实以本地安装器和运行时为准，商店安装记录只做同步和遥测，不能成为本地状态的唯一来源。
6. 现有 `/compat/fengyu/*` 和 Claude marketplace 出口保持兼容；新能力通过版本化 Native API 增加，不直接改变旧响应结构。

## 2. 当前基础与主要缺口

当前仓库已经具备以下基础：

- `UpstreamSource`、`UpstreamSyncService`：可以从单个 marketplace 拉取 Skill，并进入发布、扫描、审核、签名流程。
- `EcosystemExportService`：可以将已发布 Skill / MCP 导出为可 clone 的 Claude 风格仓库。
- `CompatFengYuController`：已有插件目录、Skill 目录和 Claude marketplace 兼容出口。
- `PackageScanner`：已有 Skill manifest、`SKILL.md`、MCP 模板和危险内容扫描能力。
- 统一 `Listing → Release → Artifact`、SHA-256、Ed25519、依赖与权限模型。

当前缺口：

| 缺口 | 影响 | 方案处理 |
|---|---|---|
| `UpstreamSource` 没有适配器类型、鉴权、ref/commit、同步游标 | 无法稳定支持 Claude、Codex、MCP Registry 等多种来源 | 增加来源类型和连接配置，适配器注册表化 |
| 同步逻辑硬编码 `plugins` / Git tarball / Skill | 新增生态需要继续复制分支逻辑 | 抽取 `MarketplaceAdapter`、`ArtifactFetcher`、`Normalizer` SPI |
| 上游条目与本地 Listing 的关联信息不足 | 重命名、同名、版本变化时会重复或覆盖 | 增加 `upstream_item`、来源别名、内容 digest 和来源 release |
| 当前 MCP 导出主要面向远程模板，STDIO 会被跳过 | FengYu 无法完整安装可本地运行的 MCP | 增加 MCP 部署变体和原生安装协议 |
| 现有 Skill 兼容目录缺少完整的完整性/来源信息 | 主项目安装后难以验证来源和更新 | Native API 提供签名、哈希、依赖和权限；旧接口继续兼容 |
| 上游“可信”可能被误解为自动免审 | 上游仓库被入侵时会把恶意内容带入商店 | 默认全量扫描；自动发布必须是可审计的显式策略，不以 URL 或名称天然信任 |

## 3. 上游适配器设计

### 3.1 支持矩阵

| 适配器 | 输入 | 第一阶段策略 | 归一化结果 |
|---|---|---|---|
| `CLAUDE_MARKETPLACE` | `.claude-plugin/marketplace.json`，支持同仓库、GitHub、Git、git-subdir、archive | 支持 marketplace、插件内 Skill、MCP 配置 | 一个或多个 `SKILL` / `MCP` Listing |
| `CODEX_SKILL_REPOSITORY` | Git 仓库或目录中的 `SKILL.md`，可带 `agents/openai.yaml`、scripts、references、assets | 按路径或全仓库扫描；必须固定 ref，生产同步记录 commit | 一个目录一个 `SKILL` Listing |
| `AGENT_SKILLS_REPOSITORY` | 遵循 Agent Skills 基础约定的任意 Git 仓库 | 作为通用 Skill 入口，允许未来接入更多 Agent | 一个目录一个 `SKILL` Listing |
| `MCP_REGISTRY` | MCP Registry `server.json`，包含 `packages` / `remotes` | 先支持标准公开 Registry；保留私有 Registry 配置能力 | 一个 `MCP` Listing，多种部署变体 |
| `SKILLHUB_REGISTRY` | SkillHub Open API（`/api/skills` 目录信封 + `/api/v1/download` 302 zip） | WorkBuddy 开源技能平台的官方技能目录；同步默认按下载量取前 N 页元数据，可在 URL 上携带 `source` / `category` / `keyword` 过滤与 `pages` / `pageSize` 商店侧旋钮 | 每个 slug 一个 `SKILL` Listing |
| `MCP_REPOSITORY` | 仓库中的 MCP manifest、`.mcp.json`、`server.json` 或已登记模板 | 只接受显式映射，不盲扫任意 JSON | 一个或多个 `MCP` Listing |
| `GENERIC_JSON_FEED` | 经审核的供应商 JSON feed | 仅允许管理员配置 JSON Schema 映射 | 受限的 `SKILL` / `MCP` Listing |

Claude marketplace 的来源字段需要完整支持 `github`、`url`、`git-subdir` 等类型，并记录 `ref` 与精确 `sha`；Claude 官方文档明确区分 marketplace source 与 plugin source，且支持通过 commit SHA 固定插件来源，不能只保存一个可变分支链接。详见 [Claude Code plugin marketplaces](https://code.claude.com/docs/en/plugin-marketplaces)。

Codex 适配器不假设存在一个长期稳定的“Codex marketplace JSON”协议。优先兼容其可移植的 Skill 目录结构：`SKILL.md`、YAML frontmatter 以及可选资源目录；未来如果 OpenAI 发布稳定目录 API，再新增独立 API 适配器，不改变 Git 仓库适配器。参考 [OpenAI/Codex build skills](https://developers.openai.com/codex/skills/) 和 [OpenAI Skills Catalog](https://github.com/openai/skills)。

MCP 适配器以官方 Registry 的 `server.json` 为优先输入；Registry 只提供元数据，实际包可能位于 npm、PyPI、NuGet、Docker、MCPB 或远程服务，因此商店必须分别处理“元数据来源”和“实际安装来源”。参考 [MCP Registry](https://modelcontextprotocol.io/registry/about) 与 [supported package types](https://modelcontextprotocol.io/registry/package-types)。

SkillHub 是腾讯面向 WorkBuddy 的开源技能平台（站点 skillhub.cn，API 基址 `https://api.skillhub.cn`），没有 Git 仓库形态的目录，只能走 HTTP API：目录接口 `/api/skills` 返回 `code/data` 信封，下载接口 `/api/v1/download` 以 302 跳转到带时效签名的对象存储地址。因此 `SKILLHUB_REGISTRY` 适配器：同步阶段只翻页读取目录信封（默认 `pages=3` × `pageSize=100`，按下载量排序，URL 可携带上游过滤参数）；下载阶段按同步记录的 slug 与版本号请求下载接口，重定向的每一跳都重新过 SSRF 校验，不缓存、不转发 `Location` 地址。上游目录条目变化（版本、描述等）会改变元数据摘要，按既有漂移策略要求重新同步。参考 [SkillHub Open API](https://github.com/Tencent/skillhub)。

SkillHub 同时是商店的**默认上游**：`UpstreamCatalogBootstrap` 启动时按名字幂等播种 `upstream_source` 行（名称 `SkillHub (WorkBuddy)`，命名空间 `skillhub`，默认 URL `https://api.skillhub.cn/api/skills?pages=1`，即下载量 Top 100），随后的启动索引沿用“从未成功同步才索引”的既有规则。可用 `store.upstream.defaults.enabled=false` 关闭，或用 `store.upstream.defaults.skillhub-url` 指向镜像/调整窗口；同步依赖播种的 `ci@infinia.local` / `reviewer@infinia.local` 账号，未开 `store.seed.enabled` 的部署会记录清晰的同步错误并在每次启动重试，而不是静默跳过。

### 3.2 适配器接口

建议新增以下领域 SPI，具体实现放在 application / infrastructure 层：

```text
UpstreamAdapter
  supports(sourceConfig)
  discover(sourceConfig, cursor) -> DiscoveredItem[]
  fetch(item, pinnedRevision) -> SourceBundle
  normalize(bundle) -> NormalizedListing[]
  checkpoint() -> SyncCheckpoint

SourceBundle
  sourceId, sourceUrl, ref, commitSha, manifestBytes, files, fetchedAt

NormalizedListing
  kind(SKILL | MCP), stableExternalId, name, description,
  version, files, deployments, permissions, provenance
```

同步采用“发现目录元数据 → 固定来源定位 → 建立虚拟 Listing/Release”的流水线，不获取制品内容。用户下载时才执行“获取所选制品 → 解包 → 规范化 → 扫描 → 兼容打包 → 流式返回”，请求结束即释放，禁止写入 BlobStorage、导出目录或其他持久缓存。网络请求需要超时、大小限制、重试、ETag/If-None-Match、速率限制和 SSRF 防护；禁止根据上游内容访问内网地址、云 metadata 地址或任意本地文件。

## 4. 统一目录和来源追踪

### 4.1 稳定身份

保留现有坐标：

```text
infinia://skill/<namespace>/<slug>
infinia://mcp/<namespace>/<slug>
```

新增来源关联表，不把上游 URL 直接当作 Listing 主键：

```text
upstream_source
  id, adapter_type, name, endpoint, auth_ref, config_json,
  enabled, sync_cursor, etag, last_sync_at, last_sync_status

upstream_item
  id, source_id, external_id, listing_id, source_url, source_path,
  ref, commit_sha, upstream_version, content_sha256,
  first_seen_at, last_seen_at, removed_at, raw_metadata_blob

upstream_release
  upstream_item_id, listing_release_id, source_commit_sha,
  source_version, normalized_sha256, sync_run_id
```

同一 Skill 被多个来源发现时，按以下顺序去重：

1. 已登记的 `external_id + source_id`；
2. 精确内容哈希；
3. 明确的 canonical repository + path + commit；
4. 最后才使用人工确认的来源别名。

不得仅凭名称去重。名称相同但内容不同的项目必须保留，并在界面显示来源和冲突提示。

### 4.2 版本规则

- 上游有合法 SemVer：保留原版本，同时在 `Release` 中记录来源版本。
- 上游版本或目录元数据变化：创建新的元数据 revision，不覆盖旧 Release；未下载内容不计算或保存制品 digest。
- 上游没有版本：使用 `0.0.0` 作为内部基础版本，并用 `sourceCommitSha/contentSha256` 做唯一更新依据，不能伪造一个看似正式的版本。
- 上游删除或撤回：标记 `DEPRECATED` / `YANKED`，不删除已安装内容；新安装停止解析到该版本。
- 上游回滚到旧 commit：不复活旧本地 release，生成一次同步事件并按照安全策略等待审核。

## 5. Skill 规范化策略

### 5.1 内容保真

Skill 的 `SKILL.md`、脚本、引用资料和 assets 原样保存；商店只补充外层 `manifest.json`，不改写正文语义。规范化时记录：

- 原始路径与重定位后的路径；
- frontmatter 原文、解析结果和未知字段；
- 是否包含 Claude/Codex 专属字段；
- 依赖命令、运行平台、网络域名和文件访问范围；
- license、作者、仓库、commit、提交时间和内容哈希。

基础校验要求：

- 根目录必须有 `SKILL.md`；
- `name` / `description` 符合 Agent Skills 基础约定；
- frontmatter 只能保留已支持字段，未知字段告警而非静默丢弃；
- 路径不能越界，禁止符号链接逃逸、设备文件、超大文件和 zip bomb；
- 脚本只做静态扫描和权限声明，不在商店同步时执行；
- Skill 的命令名使用 `namespace:skill` 或 `source:skill` 显式命名空间，避免覆盖 FengYu 内置 Skill。

Claude 官方文档要求 Skill 目录包含 `SKILL.md`，并说明 Skill 可以携带脚本和引用资料；这与 Codex 使用的可移植 Skill 目录天然兼容，但调用名称和额外 frontmatter 仍需按客户端能力分层处理。参考 [Claude Code skills](https://code.claude.com/docs/en/skills) 和 [OpenAI build skills](https://developers.openai.com/codex/skills/)。

### 5.2 多生态导出

商店内部只保存规范化元数据；收到客户端下载请求后，在请求级临时目录中生成对应视图，并在响应结束后删除：

| 出口 | 用途 | 约束 |
|---|---|---|
| `FENGYU_NATIVE` | FengYu 主项目安装 | 带完整签名、哈希、依赖、权限和安装类型 |
| `CLAUDE_MARKETPLACE` | Claude Code 生态发现/安装 | 输出 `.claude-plugin/marketplace.json` 和可 clone / archive 的源 |
| `CODEX_REPOSITORY` | Codex 兼容 Git/目录导入 | 输出标准 `SKILL.md` 目录，不伪造不存在的 marketplace 协议 |
| `RAW_ARTIFACT` | 高级用户审查或离线导入 | 必须保留 provenance，仍需 FengYu 本地验证 |

兼容出口不是新的信任根；所有出口都指向同一个已发布的商店 release。

## 6. MCP 规范化和安装模型

### 6.1 MCP 数据模型

MCP Listing 需要拆成“服务器定义”和“部署变体”：

```text
MCP Listing
  ├── Server identity / capabilities / homepage / license
  ├── Remote deployment
  │     └── streamable-http: urlTemplate, headers, oauth metadata
  └── Local deployment
        ├── npm / PyPI / NuGet / Docker / MCPB
        ├── command + args schema
        ├── environment variable declarations
        └── package version + package digest
```

MCP 当前标准传输包括 stdio 和 Streamable HTTP；远程 HTTP 必须验证 Origin 并实施认证，本地服务应绑定 localhost。FengYu 的 `McpInstaller` 需要针对两种传输分别安装和测试，不能把远程 URL 当作本地命令执行。参考 [MCP transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)。

### 6.2 安全策略

- 只发布受审查的配置模板和已固定版本的运行包，不发布用户 token、cookie、私钥或环境变量值。
- `defaultEnabled=false`；工具 allowlist 默认为空或只读，首次启用必须确认。
- 远程地址仅允许 HTTPS；记录 hostname、端口、路径和 OAuth/headers 需求。
- STDIO 的 command、args、env 采用结构化数组和 Schema，禁止 shell 拼接、`sh -c` 动态串联和远程安装脚本。
- 包安装必须有 registry、版本、digest；npm/PyPI/Docker 等包的安装脚本不在商店服务器执行，FengYu 侧要在隔离环境中执行并记录结果。
- 所有密钥由 FengYu 存入 OS Keychain/本地密钥库；配置文件只保存 secret reference，不保存明文 secret。
- 连接测试使用脱敏日志，禁止上传 MCP 请求参数、响应内容和用户业务数据。
- 上游 Registry 的“已注册”不等于 FengYu“已信任”，仍需扫描、策略判断和审计。

## 7. FengYu 主项目安装协议

### 7.1 双通道

为了不破坏现有安装能力，FengYu 同时支持：

1. **Native Store 通道（推荐）**：FengYu 请求签名的 resolution/install manifest，由本地类型安装器处理 Skill / MCP。
2. **Legacy Compatibility 通道（兼容）**：继续支持现有 `FENGYU`、`CLAUDE`、`CODEX` 等来源配置；它们只能通过兼容适配器转入同一个本地安装器，不能各自写文件或写 MCP 配置。

Native manifest 最少包含：

```json
{
  "schemaVersion": 1,
  "coordinate": "infinia://skill/claude/pdf-tools@2.0.0",
  "type": "SKILL",
  "sourceReleaseId": "...",
  "artifact": { "url": "...", "sha256": "...", "signature": "...", "keyId": "..." },
  "dependencies": [],
  "permissions": [],
  "install": { "mode": "SKILL_DIRECTORY", "defaultEnabled": true }
}
```

MCP 的 `install` 节点另需携带部署变体、默认禁用状态、secret references、tool policy 和连接测试要求。

### 7.2 本地安装编排

```text
StoreClient
  → ResolveRelease
  → VerifySignatureAndHash
  → CheckHostAndPlatform
  → ResolveDependencies
  → ShowPermissionAndProvenanceDiff
  → StageToTemp
  → SkillInstaller / McpInstaller
  → PreflightAndHealthCheck
  → CommitJournal
  → Notify + OptionalTelemetry
```

Skill 安装要求：

- 下载到临时目录，校验签名、哈希、manifest 和 `SKILL.md`；
- 目录原子替换，保留上一版本；
- 不覆盖 FengYu 内置 Skill，不覆盖用户有修改的目录；
- 写入 `sourceCoordinate`、`sourceReleaseId`、`contentSha256` 和安装时间；
- 更新失败自动恢复旧目录，重启后仍能发现旧 Skill。

MCP 安装要求：

- 先写入 disabled 的候选配置，再进行 schema、包完整性和连接预检；
- 远程 MCP 只保存模板和 secret reference；STDIO MCP 才执行本地包安装；
- 用户补齐密钥并明确启用后，才切换 active 配置；
- 启用前显示 transport、网络目标、命令、参数、环境变量和工具权限；
- 配置更新失败恢复旧配置，正在运行的 MCP 连接不被半更新状态破坏；
- 卸载前备份本地配置，删除时不删除用户密钥，除非用户明确确认。

## 8. API 与兼容性

新增版本化接口：

| 接口 | 用途 |
|---|---|
| `GET /api/v1/catalog?type=SKILL|MCP&ecosystem=...` | 统一目录检索 |
| `POST /api/v1/resolutions` | 按 FengYu 版本、平台、能力、已安装项解析 release 和依赖 |
| `GET /api/v1/releases/{id}/install-manifest?client=fengyu` | 返回 FengYu Native 安装清单 |
| `GET /api/v1/compat/fengyu/skills-catalog` | 保留现有 Skill 目录兼容协议 |
| `GET /api/v1/compat/fengyu/mcp-catalog` | 新增 FengYu MCP 兼容目录 |
| `GET /api/v1/compat/fengyu/claude-marketplace.json` | 保留 Claude Skill/MCP marketplace 出口 |
| `GET /api/v1/compat/fengyu/codex/catalog` | 提供 Codex 可消费的固定仓库/归档源及其 digest |
| `GET /api/v1/admin/upstreams` | 管理来源、适配器、同步策略和失败状态 |
| `POST /api/v1/admin/upstreams/{id}/sync` | 幂等触发同步 |

兼容规则：

- 现有插件目录只返回 `PLUGIN`，Skill / MCP 不得混入；
- 现有 Skill catalog 字段和 HTTP 状态保持不变；
- 新客户端优先使用 Native API，旧客户端继续使用旧目录；
- 任何旧接口不得返回未在本次下载请求中通过扫描的上游内容；
- Native API 的 schema 采用显式版本号，字段只增不删，破坏性变更走 `/api/v2`。

## 9. 数据库与代码落点

实施时按以下边界修改，避免把上游协议逻辑继续堆入单个 Service：

```text
store-domain
  UpstreamAdapter contract / Provenance / DeploymentVariant / InstallManifest

store-application
  UpstreamRegistry
  SyncOrchestrator
  ResolutionService
  NativeInstallManifestService
  ClaudeMarketplaceAdapter
  CodexSkillRepositoryAdapter
  McpRegistryAdapter

store-infrastructure
  UpstreamSourceAdapter
  UpstreamItemAdapter
  RawMetadataBlobStore
  ETag/Cursor persistence

store-scanner
  SkillNormalizer / McpNormalizer
  source pinning / package digest / SSRF / command policy rules

store-contract
  OpenAPI DTO、Native install manifest、MCP deployment schema

FengYu 主项目
  StoreClient、SkillInstaller、McpInstaller、InstallJournal、Keychain adapter
```

建议新增 Flyway migration：

- 扩展 `upstream_source`：`adapter_type`、`config_json`、`auth_ref`、`sync_cursor`、`etag`、`trust_policy`；
- 新增 `upstream_item`、`upstream_release`、`sync_run`；
- 给发布 Artifact 增加 provenance 和部署变体关联；
- 给本地安装记录增加 `sourceCoordinate`、`sourceReleaseId`、`contentSha256`、`installMode`。

## 10. 测试和验收标准

### 10.1 商店侧

- 同步只请求目录/manifest/Git tree 元数据，不请求上游仓库归档或包体。
- 首次制品请求发生在 FengYu 下载路径；实时扫描和兼容打包后使用 `no-store` 返回，磁盘不产生 Blob 或 Git 导出。
- Claude marketplace 的同仓库、GitHub、git-subdir、archive、固定 commit 均可同步。
- Codex / Agent Skills 仓库可按目录导入，保留 `SKILL.md`、scripts、references、assets。
- MCP Registry 的 stdio 包和 Streamable HTTP 远程定义均可规范化；缺少 digest、明文 secret、HTTP 明文地址、动态 shell 命令时阻断。
- 同一个来源重复同步不生成重复 release；内容变化生成新 revision；上游删除只下架不删本地内容。
- 上游网络超时、ETag 未变化、单条目损坏不会导致整批同步状态错误或重复导入。
- 任何上游内容都必须在返回下载响应前经过扫描；同步服务只处理元数据，不能获取制品内容。

### 10.2 FengYu 主项目侧

- 从 Native API 安装 Skill 后，重启 FengYu 仍能发现并调用 Skill。
- 从旧 `skills-catalog` 安装 Skill 的路径继续可用，且最终落到同一个 `SkillInstaller`。
- 从 Native API 安装 MCP 后，配置存在但默认 disabled，未输入 secret 时不能启动。
- 用户输入 secret 后可单独启用 MCP；连接失败不会破坏旧配置，也不会把 secret 写入日志或商店。
- Skill / MCP 更新展示权限、来源、版本和内容变化；失败自动回滚。
- FengYu 离线时已安装 Skill / MCP 继续可用；商店不可用时不会清空本地 registry。
- 同名 Skill 不覆盖内置 Skill；同名 MCP 使用坐标和来源 namespace 隔离。
- Windows、macOS、Linux 至少各完成一次 Skill 安装、MCP 配置、更新、回滚和卸载验收。

### 10.3 契约测试

建立 golden fixtures：

- Claude `marketplace.json`、plugin manifest、Skill bundle、MCP 配置；
- Codex 标准 Skill 目录和带 `agents/openai.yaml` 的插件目录；
- MCP Registry `server.json` 的 npm、PyPI、Docker、MCPB、remote variants；
- FengYu Native install manifest、旧 Skill catalog、Claude marketplace export。

每次变更必须运行：商店后端测试、scanner 测试、OpenAPI 类型生成、兼容出口测试，以及 FengYu 主项目安装器的黑盒集成测试。

## 11. 分阶段交付

### Phase 0：契约冻结

- 冻结 Native install manifest、provenance、MCP deployment schema。
- 采集真实 Claude/Codex/MCP fixtures，建立 golden tests。
- 明确 FengYu 主项目当前安装路径和可复用的 registry / runtime API。

### Phase 1：多 Skill 上游

- 抽取适配器 SPI。
- 上线 Claude marketplace、Codex Skill repository、通用 Agent Skills repository。
- 增加来源追踪、固定 commit、内容去重和同步游标。
- 保留现有 Claude marketplace 和 FengYu Skill catalog 出口。

### Phase 2：MCP Registry 与安全安装

- 接入 MCP Registry `server.json`。
- 实现 remote / stdio deployment variants、secret references、工具策略和默认禁用。
- 完成 MCP Native API、`mcp-catalog` 和兼容 marketplace 导出。

### Phase 3：FengYu 主项目安装闭环

- 实现统一 `StoreClient → InstallOrchestrator → SkillInstaller/McpInstaller`。
- 增加签名验证、权限确认、安装 journal、健康检查、回滚和离线行为。
- 先以 feature flag 灰度，旧安装链路保留为 fallback。

### Phase 4：生态扩展与治理

- 增加管理员可配置的 Generic JSON feed 和私有 MCP Registry。
- 增加来源信誉、人工复核、撤回、告警、同步监控和供应链报告。
- 在 Native API 稳定后，再考虑向外提供 FengYu marketplace 或发布 CLI。

## 12. 不采用的方案

- 不让 FengYu 直接逐个连接 Claude、Codex、MCP Registry；这样会造成配置分裂、格式分裂和安全策略绕过。
- 不把所有 Skill / MCP 压成一个 `.fys` 后交给任意客户端安装；第三方客户端的目录、权限和命名规则不同。
- 不把 MCP secret 放进商店 Artifact、marketplace JSON、Git 仓库或同步日志。
- 不因为来源被称为“官方”就跳过扫描、签名和审计。
- 不把 Codex API 中的云端 Skill 资源直接等同于本地 Codex Skill 仓库；两者先通过独立适配器建模。

## 13. 完成定义

当以下条件全部满足时，认为本方案完成：

1. 商店能通过适配器聚合 Claude、Codex/Agent Skills 和 MCP Registry 的 Skill / MCP，并保留可验证 provenance。
2. 所有聚合目录元数据经过审核和版本化；制品内容在每次下载时统一获取，并在受限临时目录中流式扫描、兼容打包；临时文件不进入持久存储且请求结束即删除。
3. FengYu 主项目通过 Native API 可正常安装、发现、配置、启用、更新、回滚、卸载 Skill / MCP。
4. 现有 FengYu Skill 安装路径和现有 Claude marketplace 兼容路径回归通过。
5. 商店离线、上游异常、安装失败、MCP 缺少密钥等场景不会破坏 FengYu 已安装生态。
