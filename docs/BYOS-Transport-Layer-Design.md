# BYOS Gallery — 传输层与可复用虚拟后端设计（跨平台）

> **文档定位**：本文件是 PRD §8.4「网络传输加速层」的深化设计，并把它上升为**可复用、可跨平台移植（KMP）的虚拟后端**的一部分。目标读者：负责 `:core-*` 共享模块的工程师。
>
> **与 PRD 的关系**：PRD 描述「做什么」（Android MVP 落地）；本文描述「怎么把传输层设计成 Android 先行、iOS 可平滑移植，且与前端解耦」。PRD §8.4.3 中 `NetworkTransport.applyTo(OkHttpClient.Builder)` 是 Android 特化签名——本文把它上提为平台无关签名（见 §3.3），Android 实现保持不变。
>
> **版本**：Transport Design v1.1（配套 PRD v1.1）
>
> **v1.1 变更**：① Tier 1 原生核心改为 **Rust 优先**（boringtun/smoltcp + UniFFI/Gobley → commonMain 绑定），弃用 Go/gomobile 主线；② 新增**插入层（Insertion Layer）**原则——代理是可插拔的旁路，启用与否不改变 gallery 内核的联网方式（§0 G5、§3.0）；③ `expect/actual` 收敛至 3 个。

---

## 0. 设计目标与约束

| 目标 | 含义 |
|------|------|
| G1 · 可复用 | 传输层是「虚拟后端」的最底层，Provider / 鉴权 / Repository 全部叠在其上，只认一个稳定的 HTTP 客户端门面 |
| G2 · 跨平台移植潜力 | 逻辑尽量落 `commonMain`（纯 Kotlin）；Rust 核心经 UniFFI/Gobley 生成 **commonMain 绑定**，故仅**安全存储 / 后台调度 / 交互式授权** 三项需 `expect/actual` |
| G3 · 前端解耦 | UI 只依赖一个 `TransportController`（暴露 `StateFlow<TransportState>` + 挂起命令），不接触隧道/netstack 细节 |
| G4 · 不碰系统 VPN | 全程 In-App 用户态，不使用 Android `VpnService` / iOS `NEPacketTunnelProvider`（同样单隧道独占，同样规避），与系统其他 VPN 共存 |
| **G5 · 插入层（Insertion Layer）** | **代理/加速链是插在 HTTP 门面之下的可插拔旁路。gallery 内核（Provider/Repository/Coil）对它零编译期依赖；关闭时联网路径与「从未集成过传输模块」逐字节等价（NO_PROXY 真直连）；开关是运行时状态翻转，不重建客户端、不改内核代码** |

**非目标**：设备级全局隧道（那是唯一需要系统 VPN 的场景，明确不做）；UDP/QUIC 隧道（MVP 走 TCP，见 §4.1）。

---

## 1. 分层总览

传输层不是一个孤立模块，而是「虚拟后端」堆栈的地基。四层，从下到上：

```
┌──────────────────────────────────────────────────────────────┐
│ Tier 4 · 前端（平台 UI，不共享或半共享）                       │
│   Android: Compose + ViewModel   |   iOS(未来): SwiftUI        │
│   仅依赖 TransportController + SyncFacade（StateFlow/suspend）  │
└───────────────▲──────────────────────────────────────────────┘
                │  commonMain 公开 API
┌───────────────┴──────────────────────────────────────────────┐
│ Tier 3 · 共享虚拟后端（commonMain，纯 Kotlin，100% 复用）       │
│   Providers(Drive/OneDrive) · AuthCoordinator · Repository     │
│   仅依赖 NetworkClient 门面（不知道传输是否存在）               │
│   图片: Coil 3（coil-network-ktor3，共享同一 Ktor 栈）          │
└───────────────▲──────────────────────────────────────────────┘
                │  NetworkClient 门面（稳定，永不因开关变化）
      ┌─────────┴─────────┐  OutboundRouter 插入点（ProxySelector/dialer）
      │  transport OFF     │  transport ON
      │  NO_PROXY 真直连    │  loopback SOCKS5 → Tier 1
      └─────────▲─────────┘
                │  UniFFI/Gobley 生成的 commonMain 绑定（非 expect/actual）
┌───────────────┴──────────────────────────────────────────────┐
│ Tier 2 · 传输抽象 / 插入层（commonMain）                        │
│   NetworkTransport · TransportConfig · TransportController      │
│   OutboundRouter（关闭=identity 空操作）                        │
└───────────────▲──────────────────────────────────────────────┘
                │  UniFFI FFI（cargo-ndk .so / .a，Gobley 链接）
┌───────────────┴──────────────────────────────────────────────┐
│ Tier 1 · 用户态 WireGuard + netstack + SOCKS 链（Rust 原生核心）│
│   boringtun(WG 协议) + smoltcp(netstack) + SOCKS5 —— 唯一 Rust  │
│   出口：本地 127.0.0.1:LP 的 SOCKS5（+可选 HTTP CONNECT）       │
└──────────────────────────────────────────────────────────────┘
```

**关键洞察**：
- Rust 核心经 **UniFFI/Gobley 生成 commonMain 绑定**——对上层是普通 Kotlin API，不再是平台特化 `expect/actual`（仅底层 `.so/.a` 链接由 cargo 插件按平台处理）。
- 传输是**插入层**：夹在稳定的 `NetworkClient` 门面与真实出口之间。gallery 内核依赖门面，永远不知道下面是直连还是隧道。开关只切换 `OutboundRouter`，门面与内核代码不动（G5）。
- 仅 3 项平台 API（安全存储、后台调度、交互式授权）是 `expect/actual`。

### KMP 模块图（建议）

```
:shared (KMP)
├── commonMain
│   ├── net/          NetworkClient 门面 · httpClient() 组装 · 拦截器(Bearer/缩略图分流)
│   ├── transport/    NetworkTransport · TransportConfig · TransportController · OutboundRouter
│   ├── wgcore/       (Gobley 生成) Rust 核心的 commonMain 绑定
│   ├── provider/     ICloudStorageProvider · GoogleDriveProvider · OneDriveProvider
│   ├── auth/         AuthCoordinator（token 交换/刷新，走 NetworkClient）
│   ├── secure/       SecureStore [expect]
│   ├── bg/           BackgroundScheduler [expect]
│   └── domain/       CloudFile · TimelinePhoto · Repository 接口
├── rust/             boringtun + smoltcp + socks5（Cargo 包，cdylib+staticlib）
├── androidMain
│   ├── net/          Ktor(OkHttp 引擎) 装配 · 动态 ProxySelector
│   ├── secure/       actual SecureStore = EncryptedSharedPreferences
│   ├── bg/           actual = WorkManager
│   └── auth/         AppAuth-Android（仅授权浏览器步骤）
└── iosMain（未来）
    ├── net/          Ktor(Darwin 引擎) 装配
    ├── secure/       actual SecureStore = Keychain
    ├── bg/           actual = BGTaskScheduler
    └── auth/         AppAuth-iOS（仅授权浏览器步骤）

:androidApp  → Compose UI + Hilt，依赖 :shared
:iosApp（未来）→ SwiftUI + SKIE，依赖 :shared.framework
```

> `wgcore` 绑定由 Gobley 从 `rust/` crate 生成到 `commonMain`（同时产出 android/jvm/native 侧胶水），因此 Rust 核心在两平台是同一份 Kotlin 调用面。Android MVP 阶段 `:shared` 可先只出 android target；`iosMain` 留空壳，日后加 iOS target 不动 `commonMain`。

---

## 2. Tier 1 · 用户态 WireGuard 原生核心

这是唯一的非 Kotlin 组件，也是跨平台移植的**唯一真正难点**。其余全是 Kotlin。

### 2.1 核心契约（语言无关的 FFI 边界）

无论 Go 还是 Rust 实现，对上层暴露的能力必须收敛为这组原语：

```
start(configJson) -> handle           // 建 WG 隧道(netstack) + 链到内网 SOCKS + 起本地 SOCKS5 监听
localSocksPort(handle) -> int          // 返回 127.0.0.1 上的本地监听端口
health(handle) -> {handshakeOk, lastHandshakeEpoch, rttMs}
stop(handle)
setStateCallback(handle, cb)           // 握手/断连/重连事件回调
```

**内部数据流**（核心内部，不暴露）：

```
本地 127.0.0.1:LP SOCKS5 inbound
  → netstack.DialContext(内网上游 SOCKS，如 10.0.0.5:1080)   // 经 WG 加密隧道
    → WireGuard(userspace, netstack) → 家庭/公司 WG peer
      → 内网 SOCKS 加速代理 → Google / Microsoft
```

- **全程保留 hostname**：inbound SOCKS5 收到域名后不解析，以域名形式 CONNECT 给上游 SOCKS，最终在内网出口侧解析（远程 DNS，见 §4.2）。
- **不创建 TUN、不调系统 VPN**：数据落内存 netstack。

### 2.2 打包方式 · Rust 核心（主线）

按你的倾向，Tier 1 **直接采用 Rust**，不走 Go/gomobile。理由不只是偏好——2026 年 Rust→KMP 的绑定链已足够成熟，反而是更优解：

**核心组成（Rust crate `rust/`）**：
- **WG 协议**：`boringtun`（Cloudflare，已部署于数百万 iOS/Android 消费设备）。它只实现协议本身、不含网络/隧道栈，正好按需组合。若要隐私增强（DAITA/多跳）可评估其派生 `GotaTun`（Mullvad 已在 Android 落地并计划扩展到含 iOS 的全平台）。
- **用户态网络栈**：`smoltcp`（Rust 的 userspace TCP/IP）。
- **SOCKS 层**：inbound 本地 SOCKS5 监听 + outbound 链到内网上游 SOCKS 的 dialer（经 smoltcp 发起，走 WG 隧道）。
- **构建**：`crate-type = ["cdylib", "staticlib"]`；Android 用 cargo-ndk 产 `.so`，iOS 产 `.a`。

**绑定方式（关键）**：用 **UniFFI（Mozilla）+ Gobley** 生成 **Kotlin Multiplatform 绑定**。
- UniFFI 是 Mozilla 在 Firefox 移动/桌面端大规模使用的多语言绑定生成器：Rust 写一遍，自动生成 Kotlin（Android）与 Swift（iOS）绑定。
- **Gobley**（`dev.gobley.cargo` / `dev.gobley.uniffi` Gradle 插件，gobley.dev）是当前活跃维护的 KMP UniFFI 工具链（原 trixnity 项目已停维，Gobley 接棒），支持 Android / Kotlin-JVM / Kotlin-Native(iOS)，把绑定生成到 **`commonMain`**（另产 android/jvm/native 侧胶水），并用 Cargo 插件自动构建、链接 Rust 库。
- **收益**：Rust 核心对上层是 **commonMain 可直接调用的 Kotlin API**——`NetworkTransport` 的实现直接在 commonMain 调 Rust 生成类，**无需 `expect/actual`**；iOS/Android 同一份调用面。相比 gomobile 分别产 AAR/XCFramework 手工绑定，干净得多，也避开了 gomobile 对 Xcode 版本敏感、历史多次因升级产出不兼容 XCFramework 的顽疾。

**Tier 1 对上的 FFI 契约仍是 §2.1 那 5 个原语**——把面收窄，日后即便调整 Rust 内部实现也不波及 Tier 2 以上。

**代价与注意**：
- Rust 侧工作量高于复用 wireproxy：需自行拼 `boringtun`(仅协议) + `smoltcp` + SOCKS 组合（无现成「一体」crate，属于自研核心）。
- **Gobley 尚年轻（0.x）**：绑定在小版本间可能破坏，需锁 `gobley` 与 `uniffi-rs` 版本、纳入 CI。
- **Android R8/JNA keep 规则**：Gobley 在 JVM/Android 侧经 JNA 调用，release 混淆会重命名 JNA 必需类导致 `UnsatisfiedLinkError`——须加 JNA 的 ProGuard/R8 keep 规则（AAR 默认不含）。

> **D-T1 已定**：Rust 从一开始就用（UniFFI/Gobley → commonMain 绑定）。Go/gomobile 不再作为主线。

---

## 3. Tier 2 · 传输抽象 / 插入层（commonMain）

### 3.0 插入层原则（G5 的落地机制）

传输不是 gallery 内核「联网方式」的一部分，而是**插在 HTTP 门面之下的可插拔旁路**。三条硬约束：

1. **依赖方向单向**：`:transport` 依赖 `:net`（门面）；`:net`、`:provider`、Coil **不依赖** `:transport`。三者在中立的 `NetworkClient` 门面处相遇。gallery 内核对传输**零编译期依赖**——甚至整个 `:transport` 模块缺席时，内核照常直连编译运行。
2. **关闭 = 恒等空操作**：`OutboundRouter` 关闭时返回 `NO_PROXY`，联网路径与「从未集成传输」**逐字节等价**（同引擎、同 DNS、同直连 socket）。用测试固化（§9）。
3. **开关 = 运行时状态翻转**，不重建客户端、不改内核：

```kotlin
// commonMain —— 内核唯一依赖的门面
interface NetworkClient {
    val http: HttpClient           // 稳定实例；开关不换它
    // Coil / Provider / AuthCoordinator 全部只拿这个
}

// 插入点：出站路由，关闭时 identity
fun interface OutboundRouter {
    /** 返回该目标应走的代理；关闭态返回 null=NO_PROXY */
    fun proxyFor(host: String): ProxySpec?
}
```

**Android 实现（零重建）**：`NetworkClient.http` 底层的 OkHttp 装一个**动态 `ProxySelector`**，运行时读 `OutboundRouter` 当前态——开 → 返回 `SOCKS 127.0.0.1:LP`；关 → `Proxy.NO_PROXY`。切换时 `connectionPool.evictAll()` 让旧路由连接退场，新请求按新路由重连。**HttpClient 实例始终不变**。

**iOS 实现（门面稳定）**：Darwin/NSURLSession 的代理绑定在 session 配置上、不支持逐请求动态选择。故切换时由 `:transport` **在门面内部重建 Darwin 引擎**——但 `NetworkClient` 门面句柄对内核**保持不变**，内核仍无感。（G5 的「内核不受影响」在两平台都成立；「不重建」是 Android 特性，iOS 为门面内替换。）

> 净效果：把「是否加速」从内核联网逻辑里彻底剥离，降为一个 Settings 级别的旁路开关。浏览相册、拉取、上传的代码路径永远只有一条。

### 3.1 状态与配置模型

```kotlin
// commonMain
sealed interface TransportState {
    data object Disconnected : TransportState
    data object Connecting : TransportState
    data class Connected(val localSocksPort: Int, val lastHandshakeEpoch: Long) : TransportState
    data class Degraded(val reason: String) : TransportState      // 握手过期/RTT 异常，仍可用
    data class Failed(val reason: String, val retryable: Boolean) : TransportState
    data object BypassedDirect : TransportState                    // 回退直连（按策略）
}

sealed interface TransportConfig {
    data object Direct : TransportConfig
    data class SocksOnly(val endpoint: Endpoint, val auth: Cred?) : TransportConfig
    data class HttpOnly(val endpoint: Endpoint, val auth: Cred?) : TransportConfig
    data class WgThenSocks(                                        // 主场景
        val wg: WgConfig,                 // wg-quick 语义
        val upstreamSocks: Endpoint,      // 内网上游 SOCKS，如 10.0.0.5:1080
    ) : TransportConfig
}

enum class FallbackPolicy { BLOCK, DIRECT }  // 隧道失败时：阻断 or 明文直连（默认 BLOCK）
```

### 3.2 传输接口（平台无关签名）

> 相比 v1.0，传输不再持有 `applyTo(HttpClientConfig)`（一次性注入、无法运行时关断）。改为暴露 `proxyFor`，由门面的动态 `OutboundRouter` 消费——这是「插入层可关断」的接口层体现。

```kotlin
// commonMain
interface NetworkTransport : OutboundRouter {   // 传输本身就是一个出站路由
    val state: StateFlow<TransportState>
    suspend fun start()
    suspend fun stop()
    suspend fun probe(target: String = "www.googleapis.com"): TransportHealth
    /** Connected 时返回 SOCKS(127.0.0.1, localPort)；否则 null → 门面走 NO_PROXY */
    override fun proxyFor(host: String): ProxySpec?
}

data class TransportHealth(val handshakeOk: Boolean, val rttMs: Long?, val viaTunnel: Boolean)
data class ProxySpec(val kind: ProxyKind, val host: String, val port: Int)  // SOCKS5 / HTTP_CONNECT
```

### 3.3 绑定 Rust 核心（commonMain，非 expect/actual）

Gobley 把 Rust 核心生成为 **commonMain 可调用的 Kotlin 类**，因此这里**不需要 `expect/actual`**——`WgThenSocksTransport` 直接在 commonMain 使用生成绑定：

```kotlin
// commonMain —— WgCore 由 Gobley 从 rust/ crate 生成（UDL/proc-macro）
//   #[uniffi::export] 的 Rust 侧原语，见 §2.1 的 5 个契约
class WgThenSocksTransport(
    private val core: WgCore,             // Gobley 生成，commonMain 可见
    private val scope: CoroutineScope,
) : NetworkTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state = _state.asStateFlow()

    override suspend fun start() {
        _state.value = TransportState.Connecting
        core.start(config.toJson())                    // 拉起 WG + 链上游 SOCKS + 本地 SOCKS5
        val port = core.localSocksPort()
        core.observeState { ev -> scope.launch { _state.value = ev.toTransportState(port) } }
        _state.value = TransportState.Connected(port, core.health().lastHandshakeEpoch)
    }
    // proxyFor(host) = 若 Connected 则 SOCKS(127.0.0.1, port) 否则 null（交给 OutboundRouter）
}
```

- Rust `.so`/`.a` 的构建与链接由 Gobley 的 Cargo 插件按平台处理（cargo-ndk / cargo）；Kotlin 侧只见 commonMain API。
- **`applyTo` 已被 §3.0 的 `OutboundRouter` 取代**：传输不再直接改 `HttpClientConfig`，而是把「本地 SOCKS 端口」通过 `OutboundRouter.proxyFor` 暴露给门面的动态 ProxySelector——这正是「插入层、可关断」的关键（对比 v1.0 的 `applyTo` 是一次性注入，无法运行时关断而不重建）。

### 3.4 代理注入的跨平台可行性（已核实）

- Ktor 通用引擎配置支持 `proxy = ProxyBuilder.socks(host, port)`。
- **Android**：OkHttp 引擎原生支持 SOCKS，且支持动态 `ProxySelector`（§3.0 的运行时开关基础）。
- **iOS**：Darwin 引擎（NSURLSession）**自 Ktor 3.3.2 起支持 SOCKS 代理**——故本地 SOCKS5 入口在 iOS 同样可用。**要求 Ktor ≥ 3.4.x**。
- 因此「本地 SOCKS5 入口」在两平台通用；差异仅在开关机制（Android 动态 selector / iOS 门面内重建，见 §3.0）。核心另保留可选本地 HTTP CONNECT 出口作后备。

---

## 4. Tier 3 · 共享 HTTP / Provider / 鉴权

### 4.1 HTTP 客户端选型：Ktor（OkHttp + Darwin 引擎）

| 维度 | 结论 |
|------|------|
| 客户端 | **Ktor Client 3.4.x**（`ktor-client-core` in commonMain） |
| Android 引擎 | `ktor-client-okhttp`（androidMain）——底层仍是 OkHttp，可预配置共享 `OkHttpClient`、挂 §8.3 拦截器、与 Coil 共享连接池 |
| iOS 引擎 | `ktor-client-darwin`（iosMain）——NSURLSession，Apple 官方网络路径，HTTP/2 默认 |
| 为何不用 CIO 单引擎 | CIO 虽覆盖全平台，但**仅 HTTP/1.1**；相册对 Google/MS 的多路复用需要 HTTP/2，故坚持 OkHttp+Darwin |
| 协议 | 强制 HTTP/2（TCP）；禁用 HTTP/3(QUIC/UDP)，因 §4.2 的 SOCKS 隧道 UDP 支持不完整 |

```kotlin
// commonMain —— 门面装配：内核只拿 NetworkClient.http，永不感知 router 状态
expect fun platformHttpClient(
    router: OutboundRouter,                       // 动态出站路由（关=NO_PROXY）
    config: HttpClientConfig<*>.() -> Unit,
): HttpClient

fun buildNetworkClient(router: OutboundRouter): NetworkClient = object : NetworkClient {
    override val http = platformHttpClient(router) {
        install(ContentNegotiation) { json() }
        install(HttpRequestRetry) { /* 429/IO 退避 */ }
        // 缩略图 Bearer 注入 / OneDrive 直连分流（Ktor 版拦截器）
    }
}
// androidMain: platformHttpClient 用 OkHttp 引擎 + proxySelector{ router.proxyFor(host) ?: NO_PROXY }
// iosMain:     Darwin 引擎；router 变更时门面内重建（见 §3.0）
```

- **内核（Provider/Repository/Coil）只依赖 `NetworkClient`**，不知道 `router` 是否接了传输。传输模块把 `WgThenSocksTransport` 注册为 `router` 的实现；不启用时 `router` 是恒返回 `null` 的 identity，`NetworkClient.http` 即纯直连（G5）。

### 4.2 远程 DNS（跨平台要点）

- 目的不变：让 **Google GeoDNS 在内网出口侧解析**，拿到离内网最近的 CDN edge。
- 因为 App 侧连的是本地 `127.0.0.1` SOCKS 入口，**真实域名解析发生在 Tier 1 核心 → 上游 SOCKS → 内网出口**，天然远程 DNS。
- 前提：HTTP 引擎以 **SOCKS5 域名地址类型** 发起（不预解析目标）。OkHttp 与 Darwin 对 SOCKS 代理均按此处理；**集成测试须实测确认无本地 DNS 泄漏**（对每个引擎各验一次）。

### 4.3 图片加载并入同一传输（Coil 3 + Ktor）

- 用 **Coil 3 的 `coil-network-ktor3`** 网络层（而非 `coil-network-okhttp`），让 Coil 复用 §4.1 的**同一个被传输注入过的 Ktor 客户端**。
- 效果：**缩略图与原图下载也自动走 `proxy@socks@vpn` 加速链**，且此方案在 Android 与 iOS 一致（Coil 3 为 KMP）。
- §8.3 的「Drive 缩略图注入 Bearer / OneDrive 直连分流」改写为 Ktor 拦截器，挂在共享客户端上。

### 4.4 Provider 与鉴权的跨平台归属

- **Provider（Drive/OneDrive）**：纯 Ktor 调用，**全部 commonMain**，100% 复用。
- **鉴权拆两段**（关键设计，解决 AppAuth 平台绑定）：
  1. **交互式授权（浏览器/Custom Tab / ASWebAuthenticationSession）**：平台特化（AppAuth-Android / AppAuth-iOS），`expect/actual AuthAuthorizer`。**此步不经传输链**（系统浏览器），与 PRD §5.2 已知例外一致。
  2. **code→token 交换 + refresh**：**不走 AppAuth 的 HTTP，改由 commonMain 的 `AuthCoordinator` 用共享 Ktor 客户端直接 POST token 端点**——于是**令牌流量在两平台都统一经加速链**，且逻辑共享。令牌存 `SecureStore`（§6）。

---

## 5. expect / actual 边界总表

| 能力 | commonMain（共享） | androidMain | iosMain（未来） |
|------|-------------------|-------------|----------------|
| 传输抽象 / 状态机 / 插入层 | ✅ 全部 | — | — |
| 原生 WG 核心 | ✅ **commonMain 绑定（Gobley 生成）** | Rust `.so`（cargo-ndk，插件链接） | Rust `.a`（cargo，插件链接） |
| HTTP 引擎装配 | Ktor core + 组装 | OkHttp 引擎 + 动态 ProxySelector | Darwin 引擎（门面内重建切换） |
| 图片加载 | Coil 3 + ktor3 层 | ✅ | ✅ |
| Provider（Drive/OneDrive） | ✅ 全部 | — | — |
| 令牌交换/刷新 | ✅（AuthCoordinator） | — | — |
| 交互式授权 | 契约（`expect AuthAuthorizer`） | AppAuth-Android | AppAuth-iOS |
| 安全存储 | 契约（`expect SecureStore`） | EncryptedSharedPreferences | Keychain |
| 后台调度 | 契约（`expect BackgroundScheduler`） | WorkManager | BGTaskScheduler |
| 前端 | — | Compose + Hilt | SwiftUI + SKIE |

> **移植成本收敛到 3 个 `expect/actual`**：安全存储、后台调度、交互式授权。WG 核心因 Gobley 生成 commonMain 绑定而**不再是 `expect/actual`**（仅底层 Rust 库的编译目标按平台走）。其余（传输/插入层、HTTP 组装、Provider、令牌流、图片）零改动。

---

## 6. 前端对接契约（Frontend Docking）

前端**只依赖一个门面**，不接触隧道内部。

### 6.1 对上暴露的门面

```kotlin
// commonMain —— UI 唯一依赖
interface TransportController {
    val state: StateFlow<TransportState>          // UI 直接绑定：连接中/已连(端口)/降级/失败/直连
    suspend fun connect(config: TransportConfig)
    suspend fun disconnect()
    suspend fun runConnectivityTest(): TransportHealth   // 设置页「连通性测试」
    fun currentConfig(): TransportConfig?
}
```

### 6.2 Android（Compose）消费

```kotlin
@HiltViewModel
class TransportViewModel @Inject constructor(
    private val controller: TransportController        // Hilt 提供的 :shared 单例
) : ViewModel() {
    val ui = controller.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), TransportState.Disconnected)
    fun onConnect(cfg: TransportConfig) = viewModelScope.launch { controller.connect(cfg) }
}
```
- **TopAppBar 指示器**（PRD §9.1）直接读 `TransportState`：`Connected`→已加速、`Degraded`→黄点、`Failed/BypassedDirect`→灰/红。
- **SettingsScreen**：导入 `wg-quick`/扫码 → 构造 `WgThenSocks` → `connect()`；「连通性测试」→ `runConnectivityTest()` 展示握手与加速前后 RTT 对比。

### 6.3 iOS（未来，SwiftUI）消费

- 用 **SKIE**（或 KMP-NativeCoroutines）把 `StateFlow<TransportState>` 暴露为 Swift 的 `AsyncSequence`/`@Published`，`suspend` 暴露为 `async`。
- SwiftUI 直接 `for await s in controller.state` 更新视图。**同一门面，零业务重写**。

### 6.4 DI 装配

| | Android | iOS |
|--|---------|-----|
| 共享层 DI | Hilt module 提供 `NativeWgCore` / `TransportController` / 共享 `HttpClient` / `Provider` 单例 | Koin（`:shared` 内）或手工组装，供 Swift 取 |
| 单例约束 | **全 App 唯一传输 + 唯一 HttpClient**：Coil、Provider、Upload/Sync 任务共享同一条隧道 | 同 |

### 6.5 生命周期与调度对接

- **前台热连接**：进入浏览态时 `connect()` 预热，加速交互式缩略图/原图。
- **后台按需**：`expect BackgroundScheduler` —— Android 由 WorkManager 在上传/同步批次前 `connect()`、批次后空闲 `disconnect()`；iOS 由 BGTaskScheduler 等价驱动。隧道**不常驻**，省电。
- **失败传播**：核心断连 → `TransportState.Failed(reason, retryable)` → UI 显示原因 + 「重试/直连」动作；按 `FallbackPolicy` 决定是否允许明文直连（默认 `BLOCK`，避免暴露加速意图/DNS 泄漏）。

---

## 7. 安全与密钥（跨平台）

```kotlin
expect class SecureStore {
    fun putSecret(key: String, value: String)
    fun getSecret(key: String): String?
    fun remove(key: String)
}
```
- Android `actual` = `EncryptedSharedPreferences`；iOS `actual` = Keychain。
- 存：WG 私钥、peer 公钥、preshared key、上游 SOCKS 凭据、OAuth `AuthState`。
- **私钥永不出核心边界外的明文日志**；传给 Tier 1 的 `configJson` 仅在进程内内存传递。
- 家庭 endpoint 仅为传输中继，**看不到 TLS 明文**（到 googleapis/graph 的 TLS 端到端），无需在 App 信任任何家庭侧证书。

---

## 8. 线程与并发模型

- Tier 2/3 全程 Kotlin Coroutines；网络 IO 用 `Dispatchers.IO`（kotlinx-coroutines 在 Native/iOS 亦提供）。
- Tier 1 Rust 核心的状态回调须**跨线程 marshal 回协程**：UniFFI 回调经 `callbackFlow` 转 `Flow`，再合流进 `TransportController.state`。
- `WgCore.start()` 为挂起，内部在 IO 上执行握手，超时（如 10s）→ `Failed(retryable=true)`。
- UniFFI 对象（如 `WgCore`）持有 Rust 侧句柄，须在 `stop()` 后 `close()` 释放（Gobley 生成 `AutoCloseable`）。

---

## 9. 测试策略

- **插入层等价性测试（G5 核心）**：对同一组 Provider 请求，分别用「不装传输模块的直连客户端」与「装了传输但 `OutboundRouter` 关闭的客户端」执行，断言两者出站行为一致（同一目标 IP、无 loopback SOCKS 介入）。这把「关闭=真直连」固化为回归。
- **FakeTransport**（commonMain）：实现 `NetworkTransport`，`proxyFor` 指向本地回环 mock SOCKS，用于 Provider/Repository 纯 commonTest。
- **运行时开关测试**：连接态下翻转 router，断言 `evictAll()` 后新请求走隧道、且 `HttpClient` 实例未变（Android）。
- **DNS 泄漏测试**：起本地假上游 SOCKS，断言目标域名以**域名形式**（非预解析 IP）抵达上游——每个 HTTP 引擎各验一次（§4.2）。
- **UniFFI 契约测试**：对 Rust 核心的 5 原语写共享测试，Android/iOS(Native) 各跑一遍生成绑定。
- **端到端**：真机连家庭 WG peer + 内网 SOCKS，`runConnectivityTest()` 对比直连/加速 RTT。

---

## 10. 落地顺序、风险与开放问题

**落地顺序**（与 PRD EPIC-5 对齐，可并行主线）：
1. commonMain 定义 `NetworkClient` 门面 + `OutboundRouter` + `NetworkTransport / TransportConfig / TransportController / TransportState`。先配 identity router（永远直连）跑通全栈——**此时 gallery 内核已完整可用，传输尚未接入**（验证 G5 依赖方向）。
2. Android：`SocksOnly` 直连内网代理跑通端到端（验证动态 ProxySelector 注入 + 远程 DNS + 关断等价）。
3. Rust 核心：`boringtun + smoltcp + SOCKS5` crate → Gobley/UniFFI 生成 commonMain 绑定 → `WgThenSocks` 完整链（Android 先行）。
4. 把 Provider/鉴权/Coil 挂到 `NetworkClient` 门面，确认令牌与图片均可经隧道、且关闭传输时逐字节直连。
5.（iOS 阶段）加 `iosMain`：Darwin 引擎装配 + 3 个 `expect/actual` 的 actual；Rust 核心因 Gobley 已是 commonMain，iOS 侧仅需 cargo 目标 + 门面内切换逻辑。

**风险**：
- **R1 · Gobley 尚年轻（0.x）**：KMP 绑定在小版本间可能破坏。缓解：锁 `gobley`/`uniffi-rs` 版本、CI 固化绑定产物、纳入契约测试。
- **R2 · Android R8/JNA**：Gobley 经 JNA 调用，release 混淆重命名 JNA 类 → `UnsatisfiedLinkError`。缓解：加 JNA keep 规则（AAR 默认不含）。
- **R3 · Rust 核心自研成本**：无「boringtun+smoltcp+SOCKS 一体」现成 crate，需自行组合。缓解：把 SOCKS/netstack 胶水做薄，核心逻辑靠成熟 crate。
- **R4 · SOCKS UDP 不完整**：QUIC/HTTP3 不走隧道。缓解：强制 HTTP/2（已定）。
- **R5 · 引擎 DNS 行为差异**：远程 DNS 须逐引擎实测（§4.2）。
- **R6 · iOS 代理切换需重建引擎**：Darwin 不支持动态 selector。缓解：门面内重建，句柄对内核不变（§3.0）。

**开放问题**：
- **D-T1 · 已定**：Tier 1 用 **Rust（boringtun/smoltcp + UniFFI/Gobley）**，从一开始就用，不走 gomobile。
- **D-T2**：`FallbackPolicy` 默认 `BLOCK` 还是 `DIRECT`？→ 建议 `BLOCK`（安全优先：隧道失败宁可不联网也不明文直连暴露加速意图），设置页可改。
- **D-T3**：多内网出口/多 peer 切换（家庭 vs 公司）？→ `TransportConfig` 预留 profile 列表，MVP 单 profile。
- **D-T4**：是否评估 `GotaTun`（Mullvad 的 boringtun 派生，含 DAITA/多跳隐私增强）替换纯 boringtun？→ MVP 用 boringtun，隐私增强按需再引入。

---

*本设计把 PRD §8.4 的传输层上升为可跨平台移植的虚拟后端地基，并贯彻两条原则：（1）**Rust 优先**——Tier 1 用 boringtun+smoltcp+SOCKS，经 UniFFI/Gobley 生成 commonMain 绑定，连 WG 核心都不再是 `expect/actual`，移植成本收敛到 3 项平台 API；（2）**插入层**——代理夹在稳定的 `NetworkClient` 门面之下，gallery 内核零依赖、关闭即逐字节直连、开关为运行时状态翻转。Ktor 3.4（OkHttp/Darwin，Darwin SOCKS 自 3.3.2 起可用）与 Coil 3（ktor3 网络层）保证本地 SOCKS 入口两平台通用。*
