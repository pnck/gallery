# BYOS Gallery — 产品与技术需求文档 (PRD)

> **定位**：Bring Your Own Storage Gallery。一款「零中间件服务器、双云盘驱动」的智能相册。用户自带 Google Drive / OneDrive 作为存储后端，App 负责本地扫描、后台同步、在线快速预览与空间释放。
>
> **本文档目标**：零伪代码。开发者可依据本文档直接建立 Gradle 模块、定义类结构、实现逻辑并提交 PR。所有接口契约、数据模型、验收标准（AC）均可落地。
>
> **版本**：PRD v1.1（整合备忘录 v3.0 / v4.0 / Backlog）
> **平台范围**：Android 客户端（MVP 唯一交付端）
> **读者**：核心开发团队 / 架构师
>
> **v1.1 变更**：① 敲定 UI 框架选型并给出依据（§2.4）；② 看图页改用 Telephoto，网格默认改为等宽正方形（§9）；③ 新增「网络传输加速层：In-App 用户态代理 + WireGuard」（§8.4）；④ WBS 新增 EPIC-5；⑤ 新增 D7–D9 决策项。

---

## 0. 关键架构假设（先读这一节）

三份来源备忘录基调为「不写后端代码，云盘即 BaaS」。本次需求描述为「可复用的后端 + 完整 API」。本 PRD 将二者**明确调和**为如下定义，全文以此为准：

- **不存在自建服务器**。Google Drive REST API v3 与 Microsoft Graph API 是真正的存储后端。
- **「可复用的后端」= 可复用的虚拟后端（Virtual Backend）**：把 Provider 抽象层、鉴权层、领域模型与同步 API 收敛进一个**独立、纯 Kotlin、零 Android UI 依赖**的模块（`:core-sync` / `:core-provider`），使其未来可被 iOS（KMP）/ Desktop 客户端复用。
- **「完整 API」= 客户端内部契约面**：即 `ICloudStorageProvider`、`AuthManager`、`PhotoRepository`、`SyncEngine` 这一组稳定接口，而非对外 HTTP 服务。

> ⚠️ 若产品实际需要一台真实中间层服务器（例如统一鉴权代理、跨设备共享、服务端缩略图生成），本 PRD 的分层需要重写，请在开工前确认。

---

## 1. 产品概述与范围

### 1.1 一句话价值
把手机相册无感同步到用户自己的网盘，需要时一键释放本地空间，任何状态下都能秒开预览——数据完全属于用户，App 不持有任何照片。

### 1.2 MVP 范围（In Scope）
| # | 能力 | 说明 |
|---|------|------|
| F1 | 无 GMS 的 OAuth 2.0 登录 | AppAuth 纯网页跳转，支持 Google 与 Microsoft 双账户 |
| F2 | 本地相册增量扫描 | MediaStore 监听，新照片入库为待上传 |
| F3 | 后台静默上传 | WorkManager + 断点续传，Doze 兼容 |
| F4 | 时间轴瀑布流 | Compose + Paging 3，十万级不 OOM |
| F5 | 鉴权防盗链预览 | Coil 拦截器实时注入 Token，秒开云端缩略图 |
| F6 | 一键释放空间 | Scoped Storage 合规删除本地、保留云端 |
| F7 | 全屏看图 | 缩放/平移、EXIF、删除 |
| F8 | 下行同步与冲突处理 | 感知云端删除/新增，本地对账 |

### 1.3 非目标（Out of Scope，MVP 明确不做）
- 跨设备实时协同 / 多人共享相册
- 人脸识别、AI 相册聚类、地图视图
- 视频转码（视频仅走分片上传，不做处理）
- 云端到云端迁移（Google ↔ OneDrive 搬运）
- 自建服务器 / 服务端缩略图

### 1.4 目标用户
对数据主权敏感、已有网盘订阅、希望摆脱厂商相册绑定的技术型或隐私敏感用户。

---

## 2. 系统架构

### 2.1 分层（Clean Architecture）

```
┌─────────────────────────────────────────────────────────┐
│  :app  (Presentation)                                    │
│  Jetpack Compose · MVI · Hilt · Paging 3 · Coil          │
│  TimelineScreen / PhotoDetailScreen / SettingsScreen     │
└───────────────▲──────────────────────────────────────────┘
                │  Domain Model (TimelinePhoto)
┌───────────────┴──────────────────────────────────────────┐
│  :core-domain                                            │
│  UseCase / Repository 接口 / SyncEngine 契约              │
└───────────────▲──────────────────────────────────────────┘
                │
┌───────────────┴──────────────┐   ┌──────────────────────┐
│  :core-data (Local SoT)       │   │  :core-provider       │  ← 可复用虚拟后端
│  Room · MediaStore · WorkMgr  │   │  ICloudStorageProvider │
│  PhotoDao · LocalMediaScanner │   │  Drive / OneDrive 实现 │
└───────────────────────────────┘   │  AuthManager (AppAuth) │
                                     └──────────────────────┘
```

### 2.2 Gradle 模块划分（建议）
| 模块 | 职责 | Android 依赖 |
|------|------|-------------|
| `:app` | UI、导航、DI 组装、Activity | 是 |
| `:core-domain` | 领域模型、Repository/UseCase 接口 | 否（纯 Kotlin） |
| `:core-data` | Room、MediaStore、WorkManager、Repository 实现 | 是 |
| `:core-provider` | Provider 抽象 + Drive/OneDrive 驱动 + AuthManager | 极少（AppAuth 需 Context） |
| `:core-network` | 定制 OkHttp、Retrofit、Result 包装、拦截器 | 否 |

> `:core-provider` + `:core-network` + `:core-domain` 即「可复用虚拟后端」。抽取为 KMP 时，这三个模块是移植目标；`:core-data` 与 `:app` 为 Android 特化实现。

### 2.3 核心技术栈
- **语言**：Kotlin 2.0（K2 编译器），全面 Coroutines + Flow
- **UI**：Jetpack Compose（无 XML），声明式；Compose BOM `2026.04.01`（core 1.11 / M3 1.4+），选型依据见 §2.4
- **架构模式**：MVI（单一数据流）
- **DI**：Hilt
- **网络**：Retrofit + OkHttp（强制 HTTP/2；共享单例 `OkHttpClient`，见 §8）
- **图片加载**：**Coil 3.5.x**（`io.coil-kt.coil3:coil-compose` + `coil-network-okhttp`），Compose-first、KMP-ready，与 OkHttp 拦截器天然协同
- **看图缩放**：**Telephoto**（`me.saket.telephoto:zoomable-image-coil3:0.19.x`），超大图自动子采样防 OOM
- **持久化**：Room（元数据） + Coil disk cache（图片）
- **后台**：WorkManager（CoroutineWorker）
- **分页**：Paging 3 + RemoteMediator
- **鉴权**：`net.openid:appauth`（token 端点走自定义 `ConnectionBuilder`，见 §5.2 与 §8.4）
- **网络传输加速**：In-App 用户态 WireGuard + SOCKS 链（wireguard-go/netstack，wireproxy 式，`gomobile bind` 成 AAR），**不使用 Android `VpnService`**，见 §8.4

### 2.4 UI 框架选型依据（2026 调研）

**结论：Jetpack Compose 定为唯一 UI 框架**，理由如下：

1. **性能已追平 View**：自 Compose 1.9.0 起，Compose 与传统 View 的滚动 jank 率持平；1.10（2025-12）官方内部滚动 benchmark 显示二者相同，且惰性预取的「可暂停组合（pausable composition）」默认开启，专为图片密集滚动场景降卡顿。历史上「Compose 做图片网格不如 View」的顾虑已不成立。
2. **官方默认方向**：Google 已将 Compose 作为新项目默认；View 系统仅留给遗留代码库与无 Compose 方案的第三方 SDK。
3. **KMP 迁移路径**：Compose Multiplatform 让未来 iOS 端可复用同一套 UI，与「可复用虚拟后端」目标一致（Flutter/RN 无法做到同一套 Kotlin 全栈复用）。
4. **唯一注意事项**：Compose 的性能收益依赖开发者纪律——`TimelinePhoto` 必须 `@Stable`/不可变、item 用稳定 key、给 Coil 传显式尺寸（`Modifier.size`/`aspectRatio`），否则触发多余重组。

**图片加载：Coil 3（而非 Glide）**。规模化实测（约 39 万张图片）Coil 平均加载耗时约为 Glide 的 1/4；体积约 94.6KB（Glide 约 222.2KB）；Coil 3 面向 Compose 与 KMP，支持自定义 interceptor 动态拼接目标尺寸做下采样——PRD §8.3 的 OAuth 注入拦截器与缩略图分流正走此机制。

**看图缩放：Telephoto（而非手写 `graphicsLayer`）**。手写双指手势的边界（边缘回弹、双击定点、与 Pager 冲突、超大图 OOM）坑深；Telephoto 的 `ZoomableAsyncImage` 是 async `Image()` 直接替换，对超大图自动切瓦片子采样，保证放大不丢细节且不 OOM——正对 `CLOUD_ONLY` 下载几十 MB 原图的场景。

---

## 3. 数据模型与契约

系统必须严格三层分离，禁止 UI 直接触碰 DTO 或 Entity：
**网络 DTO**（抹平云盘差异）→ **数据库 Entity**（持久化）→ **UI Domain Model**（防腐层）。

### 3.1 统一云端模型（Provider 层出口）

无论底层是 Google 还是 Microsoft，Provider 清洗后必须吐出统一对象：

```kotlin
// :core-provider
data class CloudFile(
    val id: String,               // 云盘唯一主键 (Drive fileId / Graph itemId)
    val provider: ProviderType,   // G_DRIVE / ONE_DRIVE
    val contentHash: ContentHash, // 见 3.5 —— 注意：跨云盘算法不同
    val sizeBytes: Long,
    val creationTime: Long,       // 统一转为 Unix Timestamp (ms)，源自 EXIF/photo facet
    val width: Int,
    val height: Int,
    val thumbnailUrl: String?     // 短期有效，仅用于即时渲染，禁止长期入库
)

enum class ProviderType { G_DRIVE, ONE_DRIVE }

// 校验和是 provider 特定的，不能跨云盘直接比对（见 3.5）
sealed interface ContentHash {
    data class Md5(val value: String) : ContentHash        // Google Drive
    data class QuickXor(val value: String) : ContentHash   // OneDrive
    data class Sha1(val value: String) : ContentHash       // OneDrive 个人版备选
    object None : ContentHash
}
```

### 3.2 网络 DTO（Retrofit 反序列化目标）

```kotlin
// Google Drive
data class DriveFileListResponse(
    val files: List<DriveFileDTO>,
    val nextPageToken: String?
)
data class DriveFileDTO(
    val id: String,
    val md5Checksum: String?,
    val thumbnailLink: String?,
    val imageMediaMetadata: ImageMetadataDTO?  // width/height/time(EXIF)
)

// OneDrive (Microsoft Graph)
data class GraphChildrenResponse(
    @Json(name = "value") val items: List<GraphItemDTO>,
    @Json(name = "@odata.nextLink") val nextLink: String?,
    @Json(name = "@odata.deltaLink") val deltaLink: String?  // 增量同步游标
)
data class GraphItemDTO(
    val id: String,
    val name: String,
    val size: Long,
    val file: GraphFileFacet?,     // hashes: quickXorHash / sha1Hash
    val image: GraphImageFacet?,   // width / height
    val photo: GraphPhotoFacet?,   // takenDateTime
    val deleted: GraphDeletedFacet? // delta 响应中标记已删除项
)
```

### 3.3 标准化响应包装（密封类）

```kotlin
// :core-network —— 所有 Provider 方法必须返回它，禁止裸抛异常穿透到 UI
sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String, val retryable: Boolean) : ApiResult<Nothing>()
}
```

### 3.4 数据库 Entity（Room）

```kotlin
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["dateTaken"]),
        Index(value = ["provider", "cloudId"], unique = true),
        Index(value = ["localUri"])
    ]
)
data class PhotoEntity(
    @PrimaryKey val id: String,          // 本地稳定 UUID（生成策略见 3.6）
    val localUri: String?,               // content://media/... ；存在即代表本地有文件
    val cloudId: String?,                // 云端主键
    val provider: String?,               // G_DRIVE / ONE_DRIVE
    val contentHashType: String?,        // MD5 / QUICK_XOR / SHA1
    val contentHashValue: String?,       // 去重/秒传用（惰性计算，见 3.5/3.6）
    val cloudThumbnailUrl: String?,      // 会过期，仅缓存最近一次，配合拦截器使用
    val dateTaken: Long,                 // UI 排序唯一依据（Unix ms）
    val width: Int,
    val height: Int,
    val syncState: SyncState             // 枚举 + TypeConverter
)

// 分页游标专用表（RemoteMediator 使用）
@Entity(tableName = "sync_keys")
data class SyncKeyEntity(
    @PrimaryKey val target: String,      // "drive_timeline" / "onedrive_timeline"
    val nextPageToken: String?,          // 初始全量分页游标
    val deltaToken: String?              // 增量/下行同步游标（Drive startPageToken / Graph deltaLink）
)
```

### 3.5 校验和与去重策略（跨云盘关键坑）

> **这是三份备忘录都忽略、但必须先定死的决策。**

- Google Drive 返回 `md5Checksum`。
- **OneDrive 不返回 MD5**：个人版返回 `quickXorHash` + `sha1Hash`，商业版仅 `quickXorHash`。
- **结论**：
  - `CloudFile.contentHash` 是 **provider 特定的**，`contentHashType` 字段必须落库。
  - **跨云盘的哈希去重不可行**（算法不同）。「秒传/去重」只在**同一 provider 内**成立。
  - 若同时挂载两个云盘并想避免重复上传，采用启发式匹配：`(sizeBytes + dateTaken + fileName)` 组合键，作为软去重，不作强约束。
  - 本地 → 云端匹配：上传前若本地已算出对应 provider 的哈希，可先 `list?q=hash` 探测；否则直接上传（幂等由文件名+云端目录约定保证）。

### 3.6 主键与本地哈希决策

- **主键 `id` = 本地生成 UUID**（`UUID.randomUUID()` 或基于 `MediaStore._ID + provider` 派生的稳定串）。
- **不采用「文件 MD5 作为主键」**（Backlog TASK-201 的建议）：理由是 MD5 需读取整文件字节，扫描期成千上万张照片时开销不可接受，且 OneDrive 无法用 MD5 跨端匹配。
- **哈希惰性计算**：`contentHashValue` 仅在**上传时**或**用户主动去重时**计算，扫描期不计算。

### 3.7 状态机（Source of Truth）

相册核心不是 UI，而是这套状态机。四态（合并 v3.0 三态 + v4.0 的 `PENDING_DELETE`）：

```kotlin
enum class SyncState(val code: Int) {
    PENDING_UPLOAD(0),  // 本地有，云端无：待上传
    SYNCED(1),          // 本地有，云端有
    CLOUD_ONLY(2),      // 本地无，云端有：已释放空间
    PENDING_DELETE(3)   // 用户请求删除：待从云端(±本地)移除，完成后删行
}
```

**状态扭转表：**

| 起始态 | 触发事件 | 目标态 | DB 副作用 |
|--------|----------|--------|-----------|
| （无） | Scanner 发现新本地照片 | `PENDING_UPLOAD` | Insert，`localUri` 有值、`cloudId` 空 |
| `PENDING_UPLOAD` | UploadWorker 上传成功 | `SYNCED` | 写入 `cloudId` / `provider` |
| `SYNCED` | 用户确认「释放空间」 | `CLOUD_ONLY` | `localUri = null` |
| `CLOUD_ONLY` | 用户查看原图 | `CLOUD_ONLY`（不变） | 原图仅落 `cacheDir`，不入 DCIM |
| `SYNCED` / `CLOUD_ONLY` | 用户在 App 内删除 | `PENDING_DELETE` | 标记 |
| `PENDING_DELETE` | Worker 完成云端(±本地)删除 | （删行） | Delete row |
| 任意 | 下行同步发现云端已删 | （删行） | 本地清理后 Delete row |

### 3.8 UI 领域模型（防腐层）

UI 只认这个，由 Repository 计算衍生属性：

```kotlin
data class TimelinePhoto(
    val id: String,
    val renderUri: String,     // Repo 计算：localUri 优先，为空则 "provider://{cloudId}"
    val aspectRatio: Float,    // width/height，供瀑布流高度计算
    val showCloudIcon: Boolean // syncState == CLOUD_ONLY
)
```

---

## 4. Provider 抽象层（可复用虚拟后端核心）

### 4.1 契约接口

整个网盘代理层的核心。所有方法为挂起函数，返回 `ApiResult`，Provider 内部负责 Token 刷新与异常包装。

```kotlin
// :core-provider
interface ICloudStorageProvider {
    val providerType: ProviderType

    /** 拉起 AppAuth 网页鉴权 */
    suspend fun authenticate(context: Context): ApiResult<Unit>

    /**
     * 初始全量分页拉取。
     * @param pageToken 为空表示第一页；返回下一页 token。
     */
    suspend fun listPhotos(pageToken: String?): ApiResult<CloudPage>

    /**
     * 增量/下行同步（含服务端删除）。
     * @param deltaToken 上次保存的游标（Drive startPageToken / Graph deltaLink）
     * @return 变更集 + 新游标
     */
    suspend fun fetchChanges(deltaToken: String?): ApiResult<CloudChangeSet>

    /** 上传（内部按大小自动选择 multipart / resumable，见 4.4） */
    suspend fun uploadFile(uri: Uri, mimeType: String, onProgress: (Int) -> Unit): ApiResult<CloudFile>

    /** 删除云端对象 */
    suspend fun deleteFile(cloudId: String): ApiResult<Unit>

    /** 获取原图字节流（看大图时用，落 cacheDir） */
    suspend fun downloadOriginal(cloudId: String): ApiResult<InputStream>

    /** 实时缩略图 URL（短期有效，仅供即时渲染） */
    suspend fun getThumbnailUrl(cloudId: String): ApiResult<String>
}

data class CloudPage(val files: List<CloudFile>, val nextPageToken: String?)
data class CloudChangeSet(
    val upserted: List<CloudFile>,
    val deletedCloudIds: List<String>,
    val newDeltaToken: String
)
```

### 4.2 Provider 差异抹平对照表

| 维度 | Google Drive v3 | OneDrive (MS Graph v1.0) |
|------|-----------------|--------------------------|
| Auth 授权端点 | `accounts.google.com/o/oauth2/v2/auth` | `login.microsoftonline.com/common/oauth2/v2.0/authorize` |
| Auth Token 端点 | `oauth2.googleapis.com/token` | `login.microsoftonline.com/common/oauth2/v2.0/token` |
| Scope | `.../auth/drive.file` | `Files.ReadWrite offline_access User.Read` |
| 列表 | `GET drive/v3/files?q=...&pageToken=` | `GET /me/drive/root/children` 或 `/special/photos/children` |
| 增量同步 | Changes API：`changes.list` + `startPageToken` | Delta：`GET /me/drive/root/delta` + `deltaLink` |
| 分页游标 | `nextPageToken` | `@odata.nextLink` |
| 增量游标 | `newStartPageToken` | `@odata.deltaLink` |
| 校验和 | `md5Checksum` | `quickXorHash` / `sha1Hash`（**无 MD5**） |
| 拍摄时间 | `imageMediaMetadata.time` | `photo.takenDateTime` |
| 尺寸 | `imageMediaMetadata.width/height` | `image.width/height` |
| 小文件上传 | `POST /upload/.../files?uploadType=multipart` | `PUT /me/drive/items/{parent}:/{name}:/content` |
| 大文件上传 | `uploadType=resumable`（初始化拿 session URI 再分片 PUT） | `createUploadSession` 再分片 PUT |
| 缩略图 | `thumbnailLink`（**需 Bearer 头**，见 8.3） | `GET /items/{id}/thumbnails`（**URL 预授权，无需头**） |
| 删除 | `DELETE drive/v3/files/{id}` | `DELETE /me/drive/items/{id}` |
| 原图下载 | `GET drive/v3/files/{id}?alt=media` | `GET /me/drive/items/{id}/content` |

### 4.3 增量与下行同步（修正备忘录的核心误区）

> 三份备忘录把「分页 `pageToken`」当成了增量同步——**错误**。`pageToken` 只是翻页，无法感知服务端删除/修改。真正的增量必须用：
> - **Drive**：Changes API（`changes.getStartPageToken` → `changes.list`）
> - **OneDrive**：Delta Query（`/delta` → `deltaLink`）
>
> 这同时解决了 Backlog 的「待决策：网页端删除照片客户端如何感知」。

**同步分两阶段：**
1. **首次全量**：`listPhotos(pageToken)` 循环翻页灌入 Room，同时保存首个 `deltaToken`。
2. **日常增量**：`fetchChanges(deltaToken)` 拿到 `upserted` + `deletedCloudIds`，对账：
   - `upserted` 中本地无对应 → Insert（`CLOUD_ONLY`，例如别的设备传的）。
   - `deletedCloudIds` → 若本地 `CLOUD_ONLY` 则删行；若 `SYNCED` 则保留本地文件、`cloudId=null`、回退 `PENDING_UPLOAD`（策略可配，MVP 取「删行」）。

### 4.4 上传大小分流
- 图片（5–10MB）：走 multipart/简单 PUT。
- 视频或 >4MB：走 resumable / upload session 分片。WorkManager 每次唤醒只传剩余分片，规避 10 分钟执行窗口超时。

---

## 5. 鉴权层（AppAuth，无 GMS）

### 5.1 AuthManager 契约

```kotlin
// :core-provider —— 每个 provider 一个实例，或内部按 provider 分区存储
interface AuthManager {
    val providerType: ProviderType
    suspend fun startAuthorization(context: Context): ApiResult<Unit>
    suspend fun getValidAccessToken(): String     // 内部 performActionWithFreshTokens
    fun isAuthorized(): Boolean
    suspend fun signOut()
}
```

### 5.2 实现要点
- 库：`net.openid:appauth`，纯网页跳转，**不依赖 Google Play Services**。
- **持久化**：`AuthState` 序列化为 JSON，存入 Jetpack `EncryptedSharedPreferences`（Google/Microsoft 各一 key）。
- **自动刷新**：`getValidAccessToken()` 内部调用 `authState.performActionWithFreshTokens`，对上层透明。
- **Redirect URI**：`manifestPlaceholders` 配置 `appAuthRedirectScheme`；两家各配一个回调 scheme。
- **令牌请求走加速链**：给 `AuthorizationService` 注入自定义 `net.openid.appauth.connectivity.ConnectionBuilder`，使其底层复用 §8 的共享 `OkHttpClient`——这样 **token 交换与刷新**（`oauth2.googleapis.com/token` / `login.microsoftonline.com/.../token`）会经过 §8.4 的 `proxy@socks@vpn` 加速链。
- ⚠️ **浏览器授权页无法走 App 代理**：AppAuth 的**交互式授权页**由系统浏览器 / Chrome Custom Tab 打开（Google 禁止 WebView OAuth，报 `disallowed_useragent`），该请求不经过 App 的 `OkHttpClient`，因此**无法**被 §8.4 的 In-App 传输链加速。仅系统级 `VpnService` 能覆盖它，但那会与用户其他 VPN 互斥（见 §8.4 拒绝理由）。此为一次性登录步骤：可接受其不加速，或提示用户在该步骤自行开启系统 VPN。token 交换/刷新与全部数据拉取不受此限制。

### 5.3 OAuth 参数（落地即用）

| | Google | Microsoft |
|--|--------|-----------|
| authorization_endpoint | `https://accounts.google.com/o/oauth2/v2/auth` | `https://login.microsoftonline.com/common/oauth2/v2.0/authorize` |
| token_endpoint | `https://oauth2.googleapis.com/token` | `https://login.microsoftonline.com/common/oauth2/v2.0/token` |
| scope | `https://www.googleapis.com/auth/drive.file` | `Files.ReadWrite offline_access User.Read` |
| PKCE | 必须（AppAuth 默认开启） | 必须 |

> `drive.file` scope 只能访问 App 自己创建的文件——符合 BYOS「App 只碰自己上传的照片」的最小权限原则。若需读取用户既有照片，需评估 `drive.readonly`（会触发 Google 敏感权限审核）。

---

## 6. 本地数据层（MediaStore + Room）

### 6.1 LocalMediaScanner

```kotlin
class LocalMediaScanner(private val resolver: ContentResolver) {
    // Projection: _ID, DATE_TAKEN, DATE_ADDED, DATE_MODIFIED, WIDTH, HEIGHT
    suspend fun scanIncremental(sinceDateModified: Long): List<LocalMediaItem>
}
```
- 查询 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`。
- **仅持久化 `content://` Uri，禁用绝对路径 `File`**（分区存储兼容）。
- 与 Room 对账：Room 中不存在 → Insert，`localUri` 赋值、`syncState = PENDING_UPLOAD`。
- 增量：以 `DATE_MODIFIED > lastScan` 作为过滤，避免每次全表扫描。

### 6.2 PhotoDao

```kotlin
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    fun getPhotosPaged(): PagingSource<Int, PhotoEntity>

    @Query("SELECT * FROM photos WHERE syncState = 0")
    suspend fun getPendingUploads(): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE syncState = 1 AND dateTaken < :beforeTs")
    suspend fun getSyncedOlderThan(beforeTs: Long): List<PhotoEntity>

    @Query("UPDATE photos SET cloudId=:cloudId, provider=:provider, syncState=1 WHERE id=:id")
    suspend fun markAsSynced(id: String, cloudId: String, provider: String)

    @Query("UPDATE photos SET localUri=NULL, syncState=2 WHERE id IN (:ids)")
    suspend fun markAsCloudOnly(ids: List<String>)

    @Upsert suspend fun upsertAll(items: List<PhotoEntity>)
    @Query("DELETE FROM photos WHERE cloudId IN (:cloudIds)")
    suspend fun deleteByCloudIds(cloudIds: List<String>)
}
```

### 6.3 SDK 碎片化权限矩阵（打通底层相册的鬼门关）

| API Level | 读权限 | 访问方式 | 备注 |
|-----------|--------|----------|------|
| ≤ 28 (Android 9) | `READ_EXTERNAL_STORAGE` | 可用 File 绝对路径 | 老路径，仍需兼容 |
| 29–32 (Android 10–12) | `READ_EXTERNAL_STORAGE` | **仅 MediaStore + Uri** | 分区存储，禁用绝对路径 |
| ≥ 33 (Android 13+) | `READ_MEDIA_IMAGES` | MediaStore + Uri | `READ_EXTERNAL_STORAGE` 废弃 |
| ≥ 34 (Android 14) | `READ_MEDIA_IMAGES` 或 `READ_MEDIA_VISUAL_USER_SELECTED` | 部分授权 | 需处理「仅选中部分照片」的降级 UI |

- **删除操作**：Android 11+（API 30+）无法静默删除非 App 创建的文件，必须走系统确认框（见 7.3）。
- `targetSdk` 建议 34；`minSdk` 建议 26（Android 8，覆盖 Compose 与 WorkManager 稳定区间）。

---

## 7. 后台同步引擎（WorkManager）

### 7.1 UploadWorker（静默上传）

```kotlin
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val pending = photoDao.getPendingUploads()
        if (pending.isEmpty()) return Result.success()
        for (photo in pending) {
            when (val res = provider.uploadFile(Uri.parse(photo.localUri!!), mime) { /*progress*/ }) {
                is ApiResult.Success ->
                    photoDao.markAsSynced(photo.id, res.data.id, res.data.provider.name)
                is ApiResult.Error ->
                    if (res.retryable) return Result.retry()  // 429/IOException → 指数退避
                    else return Result.failure()
            }
        }
        return Result.success()
    }
}
```

**约束与坑位：**
- 必须 `CoroutineWorker`，绝不占用主线程。
- **约束条件**：`setRequiredNetworkType(CONNECTED)`；可选「仅 WiFi / 仅充电」用户开关。
- **限流**：HTTP 429 → `Result.retry()`，系统按指数退避（Exponential Backoff）重新唤醒。
- **10 分钟窗口**：大文件必走分片（见 4.4），每次只传剩余分片。
- **去重触发**：`UploadWorker` 前置一次「本地 → 云端软去重」检查（同 provider 内哈希/启发式），避免重复上传。

### 7.2 SyncDownWorker（增量下行）
- 周期性（如 6h）或前台唤醒时触发。
- 调 `provider.fetchChanges(deltaToken)`，按 4.3 对账，更新 `deltaToken`。

### 7.3 CleanupWorker + 系统删除框（Scoped Storage 合规释放空间）

> **关键坑**：Android 11+ 下即使 `File.delete()` 也会抛 `SecurityException`。**不能后台静默删**。

流程：
1. 后台：查询 `syncState == SYNCED` 且超过阈值（如 30 天）的照片，计算 `List<Uri>`。
2. 把列表交给**前台 UI**（Worker 不能弹窗）。
3. UI 调用系统批量删除请求：

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val pi = MediaStore.createDeleteRequest(contentResolver, urisToDelete)
    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
}
```
4. 用户在系统框点「允许」后，回调里 `photoDao.markAsCloudOnly(ids)`（`localUri=null`, `syncState=CLOUD_ONLY`）。
5. **验收**：系统相册中照片消失，但本 App 时间轴仍显示缩略图（走云端）。

### 7.4 ContentObserver 防抖（解决备忘录「耗电」TODO）
- 监听 `MediaStore` 的 `ContentObserver` 会被频繁唤醒。
- 用 `Flow.debounce(2_000)` 合并抖动事件后再入队一次性 `OneTimeWorkRequest`，避免频繁唤醒 App。

---

## 8. 网络层调优

### 8.1 OkHttp
- **强制 HTTP/2 多路复用**：对 `googleapis.com` / `graph.microsoft.com` 并发请求复用同一 TCP 连接。
- **共享连接池**：Coil 与 Retrofit 共用同一个定制 `OkHttpClient`，`ConnectionPool` 保持最多 15 个 Keep-Alive 连接。
- **不做 OkHttp 强缓存**：云盘数据动态；缓存由 Room（元数据）+ Coil（图片文件）分层接管。

### 8.2 Retrofit Service（Drive 示例）

```kotlin
interface DriveApiService {
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,          // "mimeType contains 'image/' and trashed = false"
        @Query("pageToken") pageToken: String?,
        @Query("fields") fields: String =
            "nextPageToken, files(id, md5Checksum, thumbnailLink, imageMediaMetadata)"
    ): Response<DriveFileListResponse>

    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun initResumableUpload(@Body meta: FileMeta): Response<Unit>  // 返回 Location: session URI
}
```

### 8.3 Coil 鉴权拦截器（防盗链缩略图秒开）

> 解决 Drive `thumbnailLink` 请求 403 的问题。**两家策略不同**，拦截器必须分流：

```kotlin
class CloudThumbAuthInterceptor(private val authProvider: (ProviderType) -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url

        // 方案 A：自定义协议 drive://{cloudId} / onedrive://{cloudId}
        //         由 Fetcher 解析为真实 API 请求
        // 方案 B：拦截真实域名注入头（如下）

        return when {
            // Google：googleusercontent.com / drive api 缩略图 → 注入 Bearer
            url.host.contains("googleusercontent.com") || url.host.contains("googleapis.com") -> {
                val token = runBlocking { authProvider(ProviderType.G_DRIVE) }
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            }
            // OneDrive 缩略图 URL 预授权，直接放行，禁止再加头（会 400）
            else -> chain.proceed(req)
        }
    }
}
```

- **Drive**：缩略图需 `Authorization: Bearer`，否则 403。
- **OneDrive**：`/thumbnails` 返回的 URL 已预授权且短期有效，**直连即可，加头反而报错**。
- Token 实时从 `EncryptedSharedPreferences` 取，**禁止入库**（缩略图 URL 也一样，仅缓存最近一次供占位）。

### 8.4 网络传输加速层（In-App 用户态代理 + WireGuard）

#### 8.4.1 目标拓扑

用户自有一台部署在**家庭 / 公司内网**的加速 SOCKS 代理（该内网到 Google/Microsoft 有更优路由）。该 SOCKS 代理**只在内网可达**，手机需先经 WireGuard 接入该内网，再经内网 SOCKS 代理出海。因此完整链路为：

```
App(OkHttp / AppAuth token 请求)
  → 本地 SOCKS5 入口 (127.0.0.1:LP)        // In-App 用户态模块暴露
    → WireGuard 用户态隧道 (netstack)        // 接入家庭/公司内网
      → 内网 SOCKS 加速代理 (e.g. 10.0.0.5:1080)
        → Google Drive / Microsoft Graph
```

**覆盖范围**：OAuth 的 **token 交换/刷新**（经 §5.2 的 `ConnectionBuilder` 复用共享 `OkHttpClient`）、Drive/Graph 元数据拉取、上传、原图下载、缩略图——**全部经此链**。唯一例外是 AppAuth 交互式授权页（系统浏览器，见 §5.2）。

#### 8.4.2 关键决策：**不使用 Android `VpnService`**

> 你观察到的「proxy 与 vpn 无法同时启用」根因即在此：Android 的 `VpnService` 是**系统级、单实例、独占**接口——同一时刻只允许一个 App 持有 VPN 通道。用户若已运行其他 VPN/代理 App 或系统 Always-on VPN，就会与之互斥。

因为本 App **自己持有 OkHttp 网络栈**，根本不需要在系统层建隧道。采用「**完全用户态、进程内**」方案（等价于 `wireproxy` / `wiresocks`：wireguard-go + gVisor netstack，握手与加密在用户态完成，数据落内存 netstack，对外只暴露一个本地 SOCKS5，**不创建 TUN、不调 `VpnService`、无需 root**）。

**收益**：
- 与用户设备上其他 VPN/系统 VPN **共存**，不抢占系统通道。
- 天然 **per-app split**：只有本 App 流量入隧道，系统其余流量不受影响。
- 不弹系统「VPN 密钥」授权框。
- proxy 与 WireGuard 是**链式嵌套**关系（proxy 在隧道对端），而非争抢系统通道的互斥关系。

#### 8.4.3 传输抽象（`:core-network`）

```kotlin
interface NetworkTransport {
    suspend fun start(): TransportHandle         // 建立隧道/代理链，返回本地入口 host:port
    fun applyTo(builder: OkHttpClient.Builder)   // 注入 proxy + 远程 DNS + socketFactory
    suspend fun stop()
    suspend fun probe(): TransportHealth          // 握手成功 + 探测目标域名往返延迟
}

sealed interface TransportConfig {
    object Direct : TransportConfig
    data class SocksOnly(val host: String, val port: Int, val auth: Cred?) : TransportConfig
    data class HttpOnly(val host: String, val port: Int, val auth: Cred?) : TransportConfig
    // 目标场景：WG 接入内网 + 内网上游 SOCKS 加速代理
    data class WgThenSocks(
        val wgConfig: WgConfig,                   // wg-quick 语义：私钥/peer 公钥/endpoint/allowedIps/dns
        val upstreamSocks: Endpoint               // 内网 SOCKS，如 10.0.0.5:1080
    ) : TransportConfig
}
```

- `WgThenSocksTransport`（主实现）：内部用户态模块建立 WG → **在 netstack 内 `DialContext(upstreamSocks)`** 够到内网 SOCKS → 再把内网 SOCKS 的能力**重新暴露为本地 `127.0.0.1:LP` SOCKS5** 给 App。App 只认这个本地入口。
- 进程内**单例传输**：Coil 图片加载 + Retrofit API + Upload/SyncDown Worker **复用同一 `OkHttpClient` / 同一条隧道**，避免多条隧道并发。

#### 8.4.4 打包方式（用户态 WireGuard）

> 详见配套《传输层与可复用虚拟后端设计》。定稿为 **Rust 主线**。

- **采用**：Rust 核心（`boringtun` WG 协议 + `smoltcp` 用户态栈 + SOCKS5 inbound/链上游），经 **UniFFI + Gobley** 生成 **Kotlin Multiplatform 绑定**（落 `commonMain`，Android/iOS 同一调用面），cargo 插件按平台构建链接 `.so`/`.a`。
- 官方 `com.wireguard.android:tunnel` 的 `GoBackend` 仍绑定 `Tunnel`/`VpnService`，**不满足**「不碰 VpnService」，不采用。
- **不走 Go/gomobile**：gomobile 的 iOS XCFramework 对 Xcode 版本敏感、历史多次失配；Rust/UniFFI 同时产出 Kotlin+Swift 绑定，且是行业迁移方向（Mullvad 已由 wireguard-go 迁往 Rust）。
- **插入层原则**：代理是插在 `NetworkClient` 门面之下的可插拔旁路，gallery 内核零依赖，关闭即逐字节直连（见设计文档 §3.0 与本 PRD D8'）。

#### 8.4.5 远程 DNS（加速的真正来源，必须做）

走内网出口的意义不仅是链路，更是让 **Google 的 GeoDNS 在内网 SOCKS 出口侧解析**，拿到离该出口最近的 CDN edge。

- 因此**域名必须在链路对端解析**，App 侧禁止预解析（否则 DNS 泄漏 + 解析到手机侧的慢 IP，加速失效）。
- 实现：使用 **SOCKS5 remote DNS**——OkHttp 侧传占位 `Dns`（返回哨兵地址），让 SOCKS5 以域名形式 `CONNECT`；本地 Go SOCKS 入口与到内网 SOCKS 的链接**全程保留 hostname**，最终由内网 SOCKS 解析。

#### 8.4.6 协议与安全约束

- **强制 HTTP/2（TCP）经隧道**：wireproxy 式 SOCKS5 目前主要支持 TCP `CONNECT`（UDP/QUIC 支持不完整），故 App 侧禁用 HTTP/3(QUIC/UDP)、固定 HTTP/2——与 §8.1 一致。
- **TLS 端到端保持**：内网 SOCKS 与家庭 WG peer 仅为**传输中继**，看不到明文；到 `googleapis.com`/`graph.microsoft.com` 的 TLS 仍端到端，中继**不做 MITM**，App 无需信任任何家庭侧证书。§8.3 的 Bearer 注入发生在 TLS 内，与本层正交。
- **凭据存储**：WG 私钥、peer 公钥、preshared key、上游 SOCKS 账号一律入 `EncryptedSharedPreferences`，**私钥永不出库/日志**。

#### 8.4.7 生命周期与电量

- **按需启停**：Worker 上传/同步批次前 `start()` 预热隧道，空闲超时 `stop()`；WG `PersistentKeepalive=25s` 仅隧道活跃时维持 NAT 映射。
- **前台切换**：UI 前台可保持隧道热连接以加速交互式浏览；后台由 Worker 生命周期驱动，不常驻。
- **失败回退**：`probe()` 失败（握手超时/内网 SOCKS 不可达）时按用户策略回退 `Direct` 或阻断（默认阻断以避免明文直连泄漏加速意图，可配）。

---

## 9. UI 层（Compose + MVI + Paging 3）

### 9.1 页面组合（NavHost 三页）

**1. TimelineScreen（核心主页）**
- **默认 `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 100.dp))` + 正方形裁切（`ContentScale.Crop`）** + `collectAsLazyPagingItems()`——Google Photos 风格：像素级对齐、缩略图请求尺寸统一、缓存命中率与内存最优。
- 备选 `LazyVerticalStaggeredGrid`（Pinterest 瀑布流，按原始宽高比排列）留给后续「相册/精选」视图；`TimelinePhoto.aspectRatio` 字段为此保留。MVP 主时间轴用正方形网格。
- 按日期分组 `stickyHeader`。
- `TopAppBar` 显示同步状态指示器（如「正在上传 3/15」）+ 加速链状态（隧道已连/直连）。
- 每个 Item：`renderUri` 优先本地；`syncState == CLOUD_ONLY` 时右上角画云朵图标。给 Coil 传显式尺寸以启用下采样（见 §2.4 纪律要求）。

**2. PhotoDetailScreen（全屏看图）**
- `HorizontalPager` 左右滑切换。
- **缩放/平移用 Telephoto `ZoomableAsyncImage`（coil3 变体），不手写 `graphicsLayer` 手势**——超大图自动切瓦片子采样防 OOM，双击定点/边缘回弹/与 Pager 手势协同均内置。
- **Pager 配合**：每页独立 `rememberZoomableState`；翻页离屏（`settledPage != page`）时重置缩放，避免复用错乱（Telephoto 官方 recipe）。
- 底部 ActionBar：EXIF、删除。
- **看原图缓存**：`CLOUD_ONLY` 原图经 §8.4 加速链下载，**必须存 `context.cacheDir`，禁止写 `DCIM/`**（否则 MediaStore 重扫导致状态 0/2 重复项 —— 解决备忘录「大图缓存」TODO）。

**3. SettingsScreen（设置与授权）**
- AppAuth OAuth 唤起入口（Google / Microsoft 双账户）。
- 「一键释放空间」按钮 → 触发 7.3 流程。
- 同步策略开关（仅 WiFi / 仅充电 / 释放阈值天数）。
- **网络加速（§8.4）**：传输模式选择（直连 / 纯 SOCKS / WG+内网 SOCKS 链）；导入 `wg-quick` 配置或扫二维码；填内网上游 SOCKS 地址；「连通性测试」按钮（`probe()`：WG 握手 + 探测 `googleapis.com` 往返延迟并与直连对比）。

### 9.2 MVI 数据流（以 Timeline 为例）

```kotlin
sealed class TimelineIntent {
    object ForceSync : TimelineIntent()
    data class OnPhotoClick(val photoId: String) : TimelineIntent()
    object RequestFreeSpace : TimelineIntent()
}

data class TimelineState(
    val syncStatus: SyncStatus = SyncStatus.Idle
    // PagingData 由 Paging 3 在 UI 侧挂起收集，不放入 State，防重组丢失
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repo: PhotoRepository,
    private val workManager: WorkManager
) : ViewModel() {
    val photosFlow = repo.getPagedTimelinePhotos().cachedIn(viewModelScope)
    private val _state = MutableStateFlow(TimelineState())
    val state = _state.asStateFlow()

    fun processIntent(intent: TimelineIntent) { /* enqueueUniqueWork / navigate */ }
}
```

### 9.3 Paging 3 + RemoteMediator
- `PagingSource` 来自 Room（`getPhotosPaged()`）。
- `RemoteMediator` 负责在滚动到底时触发网络增量拉取，游标存 `SyncKeyEntity`。
- **验收**：滚动 10000 张不卡顿、不 OOM；本地与云端占位图混排肉眼难辨加载差异。

---

## 10. 非功能性需求（NFR）

| 类别 | 要求 |
|------|------|
| 性能 | 时间轴 10 万张滚动 60fps；冷启到首屏 < 1.5s（缓存命中） |
| 内存 | 大列表滚动无 OOM（Paging 3 生效验证） |
| 耗电 | ContentObserver 防抖；后台同步遵守 Doze / App Standby；上传默认可限「仅充电/WiFi」 |
| 安全 | Token 走 `EncryptedSharedPreferences`；缩略图/原图 URL 不长期落库；最小权限 scope |
| 兼容 | minSdk 26 / targetSdk 34；覆盖 API 29 / 33 / 34 权限分叉 |
| 可靠 | 上传幂等；429/断网指数退避；断点续传不重复计费流量 |
| 隐私 | App 不持有照片副本；`drive.file` 仅访问自建文件 |

---

## 11. 工作分解结构（Epic / Task / AC）

> 在原 Backlog 基础上：补齐 OneDrive Provider、下行同步、看图页三项缺失任务；`syncState` 统一为四态枚举。

### EPIC-1 · 可复用虚拟后端：鉴权与云盘对接
| Task | 标题 | 关键实现 | 验收标准（AC） |
|------|------|----------|----------------|
| T-101 | 无 GMS OAuth（AppAuth） | `AuthManager`；Google+MS 双套 endpoint；`EncryptedSharedPreferences`；`performActionWithFreshTokens` | 唤起浏览器授权后重定向回 App；杀进程重启能读 Token 并自动刷新 |
| T-102 | `ICloudStorageProvider` — Google Drive | Retrofit `DriveApiService`；`q=mimeType contains 'image/'`；multipart/resumable 上传；Changes API 增量 | 本地图上传到 Drive；能反序列化含 `thumbnailLink` + EXIF 的响应 |
| T-103 | `ICloudStorageProvider` — OneDrive | Graph `children` + `delta`；upload session；`quickXorHash` 解析；thumbnails | 本地图上传到 OneDrive；`delta` 能拿到 `deltaLink` 并解析 `deleted` facet |
| T-104 | 统一模型与差异抹平 | `CloudFile` / `ContentHash` / `ApiResult`；跨 provider 校验和策略 | 两 provider 输出结构一致的 `CloudFile`；哈希类型正确落库 |

### EPIC-2 · 本地状态机与 MediaStore
| Task | 标题 | 关键实现 | AC |
|------|------|----------|----|
| T-201 | Room + `PhotoEntity` + 四态枚举 | `PhotoDao`；`SyncState` TypeConverter；`sync_keys` 表 | Schema 编译通过；分页读写测试数据成功 |
| T-202 | MediaStore 增量扫描 | `LocalMediaScanner`；`content://` Uri；`DATE_MODIFIED` 增量 | 拍新照片触发扫描后 Room 新增 `PENDING_UPLOAD` 记录；API 10+ 无绝对路径 |
| T-203 | 权限分叉适配 | API 29/33/34 权限矩阵；部分授权降级 UI | 三档 Android 版本均能正常读取相册 |

### EPIC-3 · 后台同步引擎（WorkManager）
| Task | 标题 | 关键实现 | AC |
|------|------|----------|----|
| T-301 | UploadWorker 静默上传 | `CoroutineWorker`；429→retry；分片 | 断线/熄屏后台完成 10 张上传，状态转 `SYNCED` |
| T-302 | 合规清理本地空间 | `MediaStore.createDeleteRequest` + `ActivityResultLauncher` | Android 11+ 弹系统删除框；删后系统相册消失、App 内仍显缩略图（`CLOUD_ONLY`） |
| T-303 | SyncDownWorker 下行增量 | `fetchChanges(deltaToken)` 对账；服务端删除处理 | 网页端删照片，客户端下次同步后本地对应记录被清理 |
| T-304 | ContentObserver 防抖 | `Flow.debounce` 合并事件 | 连续拍多张仅触发一次上传入队 |

### EPIC-4 · UI 渲染与图片引擎
| Task | 标题 | 关键实现 | AC |
|------|------|----------|----|
| T-401 | Coil 鉴权拦截器 | Drive 注入 Bearer / OneDrive 直连分流 | `CLOUD_ONLY` 的 Drive 缩略图不报 403；OneDrive 直连成功 |
| T-402 | Compose 正方形网格 Timeline | `LazyVerticalGrid` 正方形裁切 + Paging 3；stickyHeader；云朵图标 | 1 万张滚动不卡不 OOM；本地/云端混排难辨 |
| T-403 | PhotoDetailScreen 看图 | Telephoto `ZoomableAsyncImage`(coil3)；`HorizontalPager` 每页独立 zoom state；原图落 `cacheDir` | 看原图不污染 DCIM，无重复项；超大图缩放不 OOM；EXIF 展示；删除走 `PENDING_DELETE` |

### EPIC-5 · 网络传输加速层（In-App 用户态代理 + WireGuard）
| Task | 标题 | 关键实现 | AC |
|------|------|----------|----|
| T-501 | `NetworkTransport` 抽象 + Direct/Socks/Http | OkHttp `.proxy` + `proxyAuthenticator`；进程内单例注入共享 `OkHttpClient` | 切换传输模式后，所有 API/图片/token 请求均经所选出口；直连模式回归正常 |
| T-502 | 用户态 WireGuard + 内网 SOCKS 链 | wireguard-go+netstack 经 `gomobile bind` 成 AAR；netstack 内 `DialContext` 内网 SOCKS；重暴露本地 `127.0.0.1:LP` SOCKS5；**不调 VpnService** | 手机经 WG 接入内网并经内网 SOCKS 访问 Google；与系统其他 VPN 共存不冲突 |
| T-503 | 远程 DNS + 连通性/延迟测试 | SOCKS5 remote DNS（全程保留 hostname）；`probe()` 握手+延迟对比；固定 HTTP/2 | DNS 在内网出口侧解析（无本地泄漏）；测试页显示握手成功与加速前后延迟 |
| T-504 | 传输生命周期 + 加密配置 | Worker 前 `start()`/空闲 `stop()`；`PersistentKeepalive`；WG 私钥入 `EncryptedSharedPreferences` | 上传批次自动建/拆隧道；杀进程重启配置可恢复；私钥不出现在日志 |
| T-505 | AppAuth token 走加速链 | 自定义 `ConnectionBuilder` 复用共享 `OkHttpClient` | token 交换/刷新经 §8.4 链路；交互式授权页走系统浏览器（已知例外，见 §5.2） |

---

## 12. 未决策清单（Open Decisions，附推荐）

| # | 议题 | 推荐方案（待确认） |
|---|------|-------------------|
| D1 | 是否需要真实中间层服务器 | 否（保持 BYOS 零服务器）；若要跨设备共享再评估 |
| D2 | 双云盘同时挂载时的去重 | 仅同 provider 内哈希去重 + 跨端启发式软匹配（size+date+name） |
| D3 | 下行发现云端删除时，本地 `SYNCED` 文件如何处理 | MVP：删行（云端为准）；可加「保留本地并回退待上传」的用户开关 |
| D4 | `drive.file` 是否够用 | 够（只碰自建文件）；若要接管既有照片需 `drive.readonly`（触发 Google 审核） |
| D5 | 释放空间阈值 | 默认 30 天 + 手动触发；设置页可调 |
| D6 | 视频是否纳入 MVP | 建议 MVP 仅图片；视频仅保留分片上传通道，UI 灰度 |
| D7 | 用户态 WG 打包方式 | **已定：Rust（boringtun+smoltcp）+ UniFFI/Gobley → commonMain 绑定**；不走 gomobile |
| D8 | 是否提供可选的系统级 `VpnService` 全局模式 | 默认不做——会与用户其他 VPN 互斥，且违背 per-app split 初衷；仅 In-App 传输 |
| D8' | 代理与内核的耦合 | **已定：插入层**——代理插在 `NetworkClient` 门面下，内核零依赖，关闭即逐字节直连，开关为运行时状态翻转 |
| D9 | 交互式 OAuth 授权页加速 | 接受不加速（一次性）；若目标区域登录页不可达，提示用户该步骤自行开系统 VPN。不引入 WebView OAuth（Google 会拒） |

---

## 13. 里程碑与交付顺序

1. **M1 · 骨架打通**：`:core-provider` + AuthManager（Google）+ Room 建库（T-101/T-201）。
2. **M2 · 单云盘闭环**：Drive 上传/拉取 + Scanner + UploadWorker + Timeline 渲染（T-102/T-202/T-301/T-402）。
3. **M3 · 预览与释放**：Coil 拦截器 + 看图页 + 合规清理（T-401/T-403/T-302）。
4. **M4 · 双云盘 + 增量**：OneDrive Provider + 下行同步 + 防抖（T-103/T-303/T-304）。
5. **M5 · 打磨**：权限分叉全覆盖、NFR 性能验证、去重策略、设置项（T-104/T-203/D2）。

> **传输加速层（EPIC-5）为独立并行轨**：仅触及 `:core-network`，不阻塞主线。建议在 M2 打通单云盘直连闭环后并行开发，最晚随 M4/M5 合入。个人「私心」加速能力可先以 `SocksOnly` 直连内网代理跑通，再补 `WgThenSocks` 完整链。

---

*本 PRD（v1.1）基于 v3.0 架构实录、v4.0 生产蓝图、Jira Backlog 整合而成，修正了「pageToken≠增量同步」「跨云盘 MD5 去重不可行」「MD5 作主键开销」三处原文缺陷，补齐 OneDrive 对接与下行同步设计。v1.1 敲定 Compose/Coil 3/Telephoto 选型，并新增「In-App 用户态代理 + WireGuard」加速传输层（不使用 Android VpnService，规避与系统 VPN 的独占互斥）。可直接据此建立 Android Studio 工程与分支开工。*
