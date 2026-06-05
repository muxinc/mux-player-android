# Offline Widevine DRM for HLS — Design

**Status:** Draft / exploration
**Branch:** `feature/offline-drm`
**Media3 version:** 1.9.2 (line numbers below are 1.9.2). **Behavior verified unchanged through
1.10.1** — see §7.
**Scope of this doc:** Offline *playback* of Widevine-protected HLS. Online playback already
works via `MuxDrmSessionManagerProvider`. **§1 is the v1 architecture and API**; **§2 is the
background** that explains the one piece of non-obvious logic §1 relies on (capturing the PSSH
from the playlist parser). Encrypted audio (multi-key) is explicitly **deferred** — see §4.

---

## 1. Architecture & API (v1)

This is the v1 design. It leans on Media3's download stack throughout; the only addition is
capturing the Widevine PSSH from the HLS playlist parser. The parts that look unusual — observing
a parser, leaving chunkless preparation on — exist for reasons laid out in the **Background (§2)**;
read that first if the *why* matters more than the *what*.

This section is ordered **call-site first**: the customer-visible entry points (§1.1 facade, §1.2
`createDownloadHelper`) and the SDK-provided service (§1.3), then the internal implementation
(§1.4–§1.7), and download lifecycle — remove / keyset release / playback restore — in §1.8.

All offline code lives in one new sub-package — **`com.mux.player.offline`** — in this module
(no new Gradle module). Reuse in non-Mux Media3 integrations is **by example**: the reusable
plumbing depends only on Media3 types, and the Mux-specific logic is isolated in `Mux*`-named
files a customer lifts and rewrites for their own DRM / license server. The reusable/Mux split
is by **file name + dependency surface**, not a compile-time boundary — the naming *is* the
"lift this part" documentation.

```
com.mux.player.offline/
  ── reusable as-is (Media3 types only) ──
  CapturingPlaylistParserFactory.kt   // PSSH observer (§2.4)
  OfflineDownloadStore.kt             // DatabaseProvider + SimpleCache(NoOpEvictor) + DownloadManager (§1.7)
  OfflineDownloads.kt                 // internal createHlsDownloadHelper(...) (§1.6)
  ── liftable Mux example (rewrite the DRM bits) ──
  MuxDrmDownloadCallback.kt           // DownloadHelper.Callback (§2.4 flow)
  MuxOfflineLicense.kt                // provider extensions: offlineLicenseHelper(), createDownloadHelper()
  MuxDownloadService.kt               // concrete DownloadService, declared in the SDK manifest (§1.3)
  MuxOfflineDownloads.kt              // customer-facing facade; prefers MuxDownloadService (§1.1)
```

Component view — how the facade, store, and service wire together (dashed arrows are dependency
/ "uses"; the two inner boxes are the reuse boundary):

```mermaid
flowchart TB
    Customer(["Customer app"])

    subgraph offline["package com.mux.player.offline"]
        direction TB
        subgraph mux["Mux example — liftable (rewrite DRM bits)"]
            direction TB
            Facade["MuxOfflineDownloads<br/>facade"]
            Lic["MuxOfflineLicense<br/>provider extensions:<br/>createDownloadHelper, offlineLicenseHelper"]
            Callback["MuxDrmDownloadCallback<br/>DownloadHelper.Callback"]
            Service["MuxDownloadService<br/>declared in SDK manifest"]
        end
        subgraph core["reusable — Media3 types only"]
            direction TB
            Factory["createHlsDownloadHelper (internal)"]
            Capture["CapturingPlaylistParserFactory<br/>PSSH observer"]
            Store["OfflineDownloadStore (singleton)<br/>DatabaseProvider · SimpleCache · DownloadManager<br/>ioExecutor · httpDataSourceFactory"]
        end
    end

    Provider["MuxDrmSessionManagerProvider<br/>existing · com.mux.player.media"]

    Customer -->|"startDownload(mediaItem)"| Facade
    Facade -->|"createDownloadHelper()"| Lic
    Facade -->|"sendAddDownload"| Service
    Lic -.->|"extension on"| Provider
    Lic --> Factory
    Lic --> Callback
    Factory --> Capture
    Factory -.->|"cacheDataSourceFactory"| Store
    Callback -.->|"reads capturedVideoKey"| Capture
    Callback -.->|"offlineLicenseHelper → OfflineLicenseHelper"| Provider
    Service -.->|"getDownloadManager"| Store
```

### 1.1 `MuxOfflineDownloads` — customer-facing facade

The primary public surface — most customers use nothing else. It **prefers `MuxDownloadService`**:
`startDownload` runs the §1.2 path and auto-enqueues the resulting `DownloadRequest` via the
service; control and observation route through the same service / the store's `DownloadManager`.
It owns a default `MuxDrmSessionManagerProvider`, so the customer passes only a `MediaItem`.

```kotlin
object MuxOfflineDownloads {
    /** Downloads the top video variant + every audio & subtitle rendition; acquires the license;
     *  enqueues to MuxDownloadService. id = playbackId. */
    fun startDownload(context: Context, mediaItem: MediaItem)

    fun remove(context: Context, playbackId: String)       // DownloadService.sendRemoveDownload(…, MuxDownloadService::class)
    fun pauseAll(context: Context)                          // sendPauseDownloads
    fun resumeAll(context: Context)                         // sendResumeDownloads
    fun downloadManager(context: Context): DownloadManager  // for addListener / DownloadIndex queries
}
```

```kotlin
// caller's whole world:
MuxOfflineDownloads.startDownload(context, mediaItem)
MuxOfflineDownloads.downloadManager(context).addListener(myListener)   // observe progress/state
```

Customers who want the `DownloadHelper` directly (e.g. custom track selection) drop to
`provider.createDownloadHelper` (§1.2); everything below that (§1.4–§1.7) is internal.

### 1.2 `createDownloadHelper` (provider extension) — the public factory

The customer-facing way to get a configured `DownloadHelper`. Like the §1.5 license seam, it is an
extension on `MuxDrmSessionManagerProvider`; it returns a fully-configured, already-preparing
`DownloadHelper` — constructing the shared `CapturingPlaylistParserFactory`, wiring it into
**both** the helper and `MuxDrmDownloadCallback` (§1.4), and kicking off `prepare`. The provider
is the single Mux entry point for offline, the way `get()` is for online.

**Opinionated about infra:** the `Executor`, `OfflineDownloadStore`, upstream `DataSource.Factory`,
and `RenderersFactory` are *not* injected — they come from `OfflineDownloadStore.get(context)`
(§1.7) and a `DefaultRenderersFactory(context)`. The signature is just the item + result hooks.

```kotlin
fun MuxDrmSessionManagerProvider.createDownloadHelper(
    context: Context,
    mediaItem: MediaItem,
    onReady: (DownloadRequest) -> Unit,
    onError: (IOException) -> Unit,
): DownloadHelper {
    val store = OfflineDownloadStore.get(context)                        // opinionated
    val capture = CapturingPlaylistParserFactory()                       // one sink…
    val helper = createHlsDownloadHelper(
        context, mediaItem, drmProvider = this, store,
        upstream = store.httpDataSourceFactory,                          // opinionated
        renderersFactory = DefaultRenderersFactory(context),            // opinionated
        capture = capture,                                               // …into the source
    )
    helper.prepare(MuxDrmDownloadCallback(capture, this, mediaItem, store.ioExecutor, onReady, onError)) // …and the callback
    return helper
}
```

`createDownloadHelper` is the public factory; the internal `createHlsDownloadHelper` (§1.6) keeps
the four infra params injectable for in-module callers that need to override them.

### 1.3 `MuxDownloadService` — SDK-provided

The SDK ships a concrete `DownloadService` and **declares it in the library manifest**, so it
merges into the consuming app with no app-side registration. Its `DownloadManager` comes from
`OfflineDownloadStore` (§1.7), so the service and the rest of the SDK act on the same index/cache.

**Notifications are fully opinionated — no customization hook.** The SDK owns the channel, the
progress notification, and the terminal (completed/failed) notifications. The progress
notification is built inline; terminal notifications come from a small internal
`DownloadManager.Listener` the service registers. There is no customer-facing notification API.

```kotlin
class MuxDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID, R.string.mux_downloads_channel_name, /* descriptionResId = */ 0,
) {
    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager =
        OfflineDownloadStore.get(this).downloadManager
            .also { it.addListener(TerminalNotifications(this, notificationHelper)) }
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)   // §6: decided
    override fun getForegroundNotification(downloads: List<Download>, notMet: Int): Notification =
        notificationHelper.buildProgressNotification(
            this, R.drawable.mux_ic_download, /* contentIntent = */ null, /* message = */ null,
            downloads, notMet,   // helper renders progress %, item count
        )
}

/** Opinionated completed/failed notifications. Internal — no customer surface. */
private class TerminalNotifications(
    private val context: Context,
    private val helper: DownloadNotificationHelper,
) : DownloadManager.Listener {
    private var nextId = TERMINAL_NOTIFICATION_BASE_ID
    override fun onDownloadChanged(mgr: DownloadManager, download: Download, e: Exception?) {
        val notification = when (download.state) {
            Download.STATE_COMPLETED ->
                helper.buildDownloadCompletedNotification(context, R.drawable.mux_ic_done, null, null)
            Download.STATE_FAILED ->
                helper.buildDownloadFailedNotification(context, R.drawable.mux_ic_error, null, null)
            else -> return
        }
        NotificationUtil.setNotification(context, nextId++, notification)
    }
}
```

Manifest merge adds the `<service>` plus `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
and `RECEIVE_BOOT_COMPLETED` to **every** consumer of the SDK — accepted as the cost of
one-module + "SDK provides it".

### 1.4 `MuxDrmDownloadCallback` — implementation

Implements `DownloadHelper.Callback`. `onPrepared` wraps the track-selection and PSSH-discovery
complexity, acquires the license off the caller thread, and emits a ready-to-enqueue
`DownloadRequest`.

```kotlin
class MuxDrmDownloadCallback(
    private val capture: CapturingPlaylistParserFactory,
    private val drmProvider: MuxDrmSessionManagerProvider,
    private val mediaItem: MediaItem,
    private val ioExecutor: Executor,
    private val onReady: (DownloadRequest) -> Unit,
    private val onError: (IOException) -> Unit,
) : DownloadHelper.Callback {
    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
        // 1. select tracks — top video + EVERY audio & subtitle rendition (see "Track selection" below)
        // 2. videoKey = capture.videoKey  (the #EXT-X-KEY PSSH; §2.4)
        // 3. ioExecutor: drmProvider.offlineLicenseHelper(mediaItem)?.downloadLicense(format) → keySetId
        // 4. request = helper.getDownloadRequest(mediaItem.getPlaybackId(), data)  // id = playbackId, NOT the default uri id
        //              .copyWithKeySetId(keySetId)
        // 5. onReady(request); helper.release()
    }
    override fun onPrepareError(helper: DownloadHelper, e: IOException) = onError(e)
}
```

**Track selection (step 1) — top video + every audio & subtitle rendition.** `DownloadHelper`'s
default selection already picks the top video (forced highest bitrate) + one default audio. The
callback then adds the rest: for each audio and text renderer, a `SelectionOverride` per track
group, so **every** rendition is included. `getDownloadRequest()` unions these into the request's
`streamKeys`, and `SegmentDownloader` downloads exactly those (`SegmentDownloader.java:225`).

```kotlin
val mti = helper.getMappedTrackInfo(0)
for (r in 0 until mti.rendererCount) {
    val type = mti.getRendererType(r)
    if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue   // keep default top video
    val groups = mti.getTrackGroups(r)
    for (g in 0 until groups.length) {                       // each rendition = one group
        helper.addTrackSelectionForSingleRenderer(
            /* periodIndex = */ 0, r,
            DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
            listOf(SelectionOverride(g, /* track = */ 0)),
        )
    }
}
```

Audio and subtitles are **clear** today, so this affects only which *media* is fetched — the key
story is unchanged (one video key, §2.4). Per-rendition *keys* would only arise if audio became
encrypted (§4).

Acquisition runs on `ioExecutor` because `onPrepared` is delivered on the caller's looper and
`OfflineLicenseHelper.downloadLicense` blocks. DRM content with a null `capture.videoKey` is an
error path (→ `onError`).

**playbackId → keySetId is stored as a single `DownloadIndex` row.** Step 4 uses the id-taking
overload `getDownloadRequest(playbackId, …)` so the row's primary key (`DefaultDownloadIndex.java:110`)
is the playback ID, and `copyWithKeySetId` puts the license in the row's `key_set_id` column
(`:65`). That row *is* the association — there is no separate keyset table. Read it back with
`downloadManager.downloadIndex.getDownload(playbackId)?.request?.keySetId` at playback (§2.4) and
removal (§1.8). The **default** `getDownloadRequest(data)` keys by the manifest URI
(`DownloadHelper.java:860`), which would break the lookup — hence the explicit id.

### 1.5 License seam — `offlineLicenseHelper` (provider extension)

Extension on `MuxDrmSessionManagerProvider`, reusing its public `drmHttpDataSourceFactory` /
`logger` + the `MediaItem` getters + the public `MuxDrmCallback`. Leaves the online provider
untouched; no real duplication.

```kotlin
internal fun MuxDrmSessionManagerProvider.offlineLicenseHelper(mediaItem: MediaItem): OfflineLicenseHelper? {
    val playbackId = mediaItem.getPlaybackId() ?: return null
    val drmToken = mediaItem.getDrmToken() ?: return null
    val sm = DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
        .build(MuxDrmCallback(drmHttpDataSourceFactory, mediaItem.getLicenseUrlHost(), drmToken, playbackId, logger))
    return OfflineLicenseHelper(sm, DrmSessionEventListener.EventDispatcher())
}
```

### 1.6 Wired helper — `createHlsDownloadHelper` (internal)

**Internal**, not part of the public API — the public factory is `createDownloadHelper` (§1.2).
Builds a `DownloadHelper` with the observer parser, DRM provider, and download cache wired in.
`capture` is an explicit (defaulted) param so an in-module caller — or §1.2 — can share one
instance between the helper and the callback.

```kotlin
internal fun createHlsDownloadHelper(
    context: Context,
    mediaItem: MediaItem,
    drmProvider: DrmSessionManagerProvider,         // base type — reusable
    store: OfflineDownloadStore,
    upstream: DataSource.Factory,
    renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
    capture: CapturingPlaylistParserFactory = CapturingPlaylistParserFactory(),
): DownloadHelper =
    DownloadHelper.Factory()
        .setRenderersFactory(renderersFactory)
        .create(
            HlsMediaSource.Factory(store.cacheDataSourceFactory(upstream))
                .setPlaylistParserFactory(capture)
                .setDrmSessionManagerProvider(drmProvider)
                .createMediaSource(mediaItem)
        )
```

### 1.7 Storage — `OfflineDownloadStore`

Process-wide singleton owning the Media3 storage primitives. `SimpleCache` permits one instance
per directory (so this must be a singleton), and it is a **separate cache from the smart-cache**
(`MuxPlayerCache`), using `NoOpCacheEvictor` so downloads are never LRU-evicted.

```kotlin
class OfflineDownloadStore private constructor(
    val databaseProvider: DatabaseProvider,    // StandaloneDatabaseProvider — shared
    val downloadCache: Cache,                   // SimpleCache(dir, NoOpCacheEvictor(), databaseProvider)
    val downloadManager: DownloadManager,       // DownloadManager(ctx, DefaultDownloadIndex(databaseProvider), …)
    val ioExecutor: Executor,                   // opinionated infra — used by §1.2
    val httpDataSourceFactory: DataSource.Factory, // default upstream for playlist/segment fetch
) {
    /** Backs both segment download and offline playback reads. Defaults upstream to httpDataSourceFactory. */
    fun cacheDataSourceFactory(
        upstream: DataSource.Factory = httpDataSourceFactory,
    ): CacheDataSource.Factory

    companion object { fun get(context: Context): OfflineDownloadStore }
}
```

The **license is not stored here** — for single-key v1 it rides `DownloadRequest.keySetId` in
the `DownloadIndex` the `DownloadManager` already owns (§2.2). The store is also where the
**opinionated infra lives** (the IO `Executor` and the default upstream HTTP factory) so §1.2
needn't inject them, and **`MuxDownloadService` (§1.3) draws its `DownloadManager` from here**.

### 1.8 Managing downloads & keysets (lifecycle)

**Removal is writer-safe through the `DownloadManager` — never hand-delete the cache.** The
manager keeps at most one `Task` per download id (`activeTasks`, `DownloadManager.java:693`) on a
single handler thread. `removeDownload` moves the download to `STATE_REMOVING`, and
`syncRemovingDownload` **cancels the in-flight download task and waits for it to stop before**
starting the remove task, which runs `Downloader.remove()` → `cache.removeResource(...)` for every
segment + the playlist (`SegmentDownloader.java:371,379`). A writing task and a deleting task for
the same id therefore can never overlap, and bytes written during the
`sendRemoveDownload`→process window are cleaned up by that same remove task. **Media deletion is
race-free and already performed by removal** — there is nothing extra to delete.

> ⚠️ Reaching into the `Cache`/filesystem from `remove()` would run on the **caller** thread,
> outside the manager's serialization, racing the active download. If cleanup beyond
> `Downloader.remove()` is ever needed, do it in `DownloadManager.Listener.onDownloadRemoved(...)`
> (manager thread, post-removal) — never synchronously in `remove()`.

**`remove(playbackId)`** captures the keySetId before the index entry is gone, deletes media via
the manager, then drops the offline keyset from local CDM storage:

```kotlin
fun remove(context: Context, playbackId: String) {
    val store = OfflineDownloadStore.get(context)
    store.ioExecutor.execute {
        val keySetId = store.downloadManager.downloadIndex.getDownload(playbackId)?.request?.keySetId
        DownloadService.sendRemoveDownload(context, MuxDownloadService::class.java, playbackId, /* foreground = */ false)
        keySetId?.let { dropOfflineLicense(it) }
    }
}

/** Local-only purge — no network, no drmToken. Mux enforces no offline-license quota, so we
 *  never do a server-side MODE_RELEASE. */
private fun dropOfflineLicense(keySetId: ByteArray) {
    if (Build.VERSION.SDK_INT < 29) return            // no per-license purge API pre-29 (see below)
    val mediaDrm = try { MediaDrm(C.WIDEVINE_UUID) } catch (_: Exception) { return }
    try { mediaDrm.removeOfflineLicense(keySetId) } catch (_: Exception) { /* best-effort */ }
    finally { mediaDrm.close() }                      // close() is API 28+
}
```

We deliberately **do not** call `OfflineLicenseHelper.releaseLicense` (MODE_RELEASE): that is a
*networked* release exchange — it POSTs a release request to the license server via the
`MediaDrmCallback` and needs a valid `drmToken` — whose only purpose is decrementing a server-side
allotment, which Mux doesn't enforce. `MediaDrm.removeOfflineLicense` (API 29+) purges the keyset
from the device's CDM store with no I/O. On **API 23–28** there is no public per-license purge API;
removing the `Download` already drops our `keySetId` reference, leaving only a tiny orphaned CDM
blob (a few KB, unreferenceable) — a negligible local leak with no quota to care about. Either way
this touches the CDM store, not the disk cache, so it can't race cache writes.

**Playback restore** is the mirror image: resolve the `keySetId` from the `DownloadIndex` by
`playbackId` and hand it to `MuxDrmSessionManagerProvider` for `setMode(MODE_PLAYBACK, keySetId)`
(§2.4). The store already exposes `downloadManager` (hence the index) for this lookup.

## 2. Background: the added PSSH-extraction logic

§1 leans entirely on Media3's download stack except for **one** addition — capturing the Widevine
PSSH from the playlist parser. This section explains why that addition is necessary: what offline
acquisition needs, how Media3's download orchestration already works (nothing Mux-specific), why
HLS doesn't surface the PSSH through the normal path, and the observer that bridges the gap.

### 2.1 What offline license acquisition needs

To play DRM content offline we need an **offline license** (a persisted Widevine `keySetId`)
acquired ahead of time, then replayed via `DefaultDrmSessionManager.setMode(MODE_PLAYBACK,
keySetId)`. `OfflineLicenseHelper.downloadLicense(format)` requires a `Format` whose `drmInitData`
is non-null — it asserts exactly this (`OfflineLicenseHelper.java:231`,
`checkArgument(format.drmInitData != null)`). So the whole problem reduces to:

> **Get the Widevine PSSH / `DrmInitData` for the asset, without playing it.**

For Mux delivery the DRM signaling is always a single `#EXT-X-KEY` tag in each **media** playlist
(no `#EXT-X-SESSION-KEY` in the multivariant playlist, no key rotation).

### 2.2 Media3 download orchestration (existing design we build on)

Nothing here is Mux-specific — it's how Media3 already splits planning from execution, repeated so
the §1 wiring is legible. **`DownloadHelper` plans** (what to download); **`DownloadManager`
executes** (fetch + lifecycle). They hold no reference to each other — the only contract is a
serializable **`DownloadRequest`**.

- **`DownloadHelper`** — transient. Prepares the source, lets you select tracks, emits a
  `DownloadRequest` via `getDownloadRequest(...)`. Downloads nothing; you `release()` it after.
- **`DownloadManager`** — app-scoped singleton, `(Context, WritableDownloadIndex, DownloaderFactory)`
  (`DownloadManager.java:238`). Accepts `DownloadRequest`s via `addDownload(...)` (`:466`), persists
  them to a SQLite `DownloadIndex`, runs a queue (retries, `Requirements`), creates a `Downloader`
  per request via `downloaderFactory.createDownloader(request)` (`:1015`), emits `Download` state.
- **`DownloadService`** — foreground `Service` wrapper; `DownloadService.sendAddDownload(...)` is the
  usual way the request reaches `addDownload(...)`. (The SDK ships one — §1.3.)

```mermaid
flowchart LR
    subgraph plan [DownloadHelper — plan]
        direction TB
        p1["prepare(callback)"] --> p2["onPrepared: top video + all audio/subtitle renditions"]
        p2 --> p3["acquire license — our step → keySetId"]
        p3 --> p4["getDownloadRequest → DownloadRequest<br/>id, uri, streamKeys, keySetId"]
    end
    p4 -->|"DownloadService.sendAddDownload"| add["addDownload(request)"]
    subgraph exec [DownloadManager — execute]
        direction TB
        add --> idx[("DownloadIndex, SQLite")]
        add --> q["queue and Requirements"]
        q --> dl["HlsDownloader.download"]
        dl --> seg["load streamKey-filtered playlists"]
        dl --> cache[("Cache")]
        dl --> st["state: QUEUED → DOWNLOADING → COMPLETED"]
    end
```

**The two sides must share the same `Cache`.** The manager's `DownloaderFactory` is built with a
`CacheDataSource.Factory` pointing at the cache we read at playback (the deferred audio path in §4
would reuse it so playlist fetches dedup).

**The `keySetId` seam.** `DownloadRequest` carries a `keySetId` field (`DownloadRequest.java:164`),
with `Builder.setKeySetId(...)` (`:83`), `copyWithKeySetId(...)` (`:258`), and `toMediaItem()`
(`:302`) — the built-in place to stash a single offline license.

- **It does not acquire anything.** `getDownloadRequest` only *copies* a keySetId already on the
  `MediaItem`: `setKeySetId(localConfiguration.drmConfiguration.getKeySetId())`
  (`DownloadHelper.java:922`). Acquisition (§2.4) is our step; set it on the `MediaItem` up front or
  `request.copyWithKeySetId(keySetId)` afterward.
- **It's exactly what v1 needs.** One video key → `DownloadRequest.keySetId`; `toMediaItem()`
  round-trips it and `DownloadHelper.createMediaSource(request, dsf, drm)` restores it via
  `MODE_PLAYBACK`. A single `byte[]` can't represent encrypted-audio multi-key — deferred (§4).

So **v1 has no bespoke license store**: the `keySetId` lives on the `DownloadRequest` in the
`DownloadIndex` the `DownloadManager` already owns. Neither `DownloadHelper` nor `DownloadManager`
ever contacts the license server — they move bytes and state. The license lifecycle is ours.

### 2.3 Why HLS doesn't surface the PSSH through the normal path

The textbook offline flow is: `DownloadHelper.prepare()` → walk `getTrackGroups()` → find a
`Format` with `drmInitData` → `OfflineLicenseHelper.downloadLicense(format)`. For HLS the
track-group `Format` has **null** `drmInitData`, because HLS prepares "chunkless" by default
(`HlsMediaPeriod.java:655`) and the chunkless format is built by `deriveVideoFormat(...)`, which
constructs a fresh `Format.Builder()` and **never copies `drmInitData`**.
`createTrackGroupArrayWithDrmInfo` only stamps a crypto type (`HlsSampleStreamWrapper.java:1546`);
it does not add init data.

Only the **chunk-loading** prepare path (`setAllowChunklessPreparation(false)`) surfaces it,
because loading a segment fires `output.setDrmInitData(...)` (`HlsMediaChunk.java:600`) →
`deriveFormat` retains it. But that downloads a segment we don't otherwise need.

**Implication:** a custom parser cannot place the PSSH on the chunkless `Format` (the field is
dropped downstream regardless of what the parser emits). So instead of routing the key *onto the
Format*, we read it *off the parser output directly* — §2.4.

### 2.4 The added logic: a playlist-parser observer

`HlsMediaSource` runs a `DefaultHlsPlaylistTracker` that, immediately after the multivariant
playlist loads, fetches the **primary** (variant[0]) media playlist
(`DefaultHlsPlaylistTracker.java:318,331-336`) and parses it using the **injected**
`HlsPlaylistParserFactory` (`:802`). We inject a factory that **delegates to the real parser and
observes the result** — riding the tracker's existing load, so we capture the `DrmInitData` with
**no extra network call** and **no segment download** (chunkless prep can stay ON, since we don't
read from the `Format`). It is **observe-only**: we never mutate parsed data.

> ⚠️ **Read the segment-level `drmInitData`, not `protectionSchemes`.** The stock parser decodes
> the `#EXT-X-KEY` PSSH and attaches it to each `segment.drmInitData` (full bytes). The
> playlist-level `HlsMediaPlaylist.protectionSchemes` has its PSSH data **stripped** —
> `schemeData.copyWithData(null)` (`HlsPlaylistParser.java:1404`). Using it yields a license
> request with empty init data.

**Observer factory.**

```kotlin
class CapturingPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
    private val onMediaPlaylist: (HlsMediaPlaylist) -> Unit,
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =   // multivariant — delegate
        delegate.createPlaylistParser()

    override fun createPlaylistParser(                                            // media playlist — #EXT-X-KEY lives here
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> {
        val inner = delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        return ParsingLoadable.Parser { uri, input ->
            inner.parse(uri, input).also { if (it is HlsMediaPlaylist) onMediaPlaylist(it) }
        }
    }
}

fun HlsMediaPlaylist.widevineInitData(): DrmInitData? =
    segments.firstNotNullOfOrNull { seg ->
        seg.drmInitData?.takeIf { d -> (0 until d.schemeDataCount).any { d[it].matches(C.WIDEVINE_UUID) } }
    }
```

**Wiring** (the source side of §1.6/§1.2):

```kotlin
@Volatile var capturedVideoKey: DrmInitData? = null   // single key; parser runs on a loader thread
val parserFactory = CapturingPlaylistParserFactory { mp ->
    mp.widevineInitData()?.let { capturedVideoKey = it }
}
HlsMediaSource.Factory(dataSourceFactory)
    .setPlaylistParserFactory(parserFactory)
    .setDrmSessionManagerProvider(drmProvider)   // so the DRM track isn't dropped as unselectable
    .createMediaSource(mediaItem)
```

**License acquisition** reuses `MuxDrmCallback` (the §1.5 seam) so it hits the same Mux endpoint;
`OfflineLicenseHelper.downloadLicense(Format(capturedVideoKey))` returns the `keySetId`.

**Playback restore.** Thread the stored `keySetId` into `MuxDrmSessionManagerProvider` so its
builder calls `setMode(MODE_PLAYBACK, keySetId)` when an offline license exists for the playback
ID — no network license request is made.

### 2.5 End-to-end flow

```mermaid
sequenceDiagram
    autonumber
    participant App
    participant DH as DownloadHelper
    participant Src as HlsMediaSource and Tracker
    participant Cap as CapturingPlaylistParserFactory
    participant Lic as OfflineLicenseHelper
    participant Idx as DownloadIndex
    participant Prov as MuxDrmSessionManagerProvider

    rect rgb(245, 245, 245)
    Note over App,Idx: download time — acquire license
    App->>DH: prepare(mediaItem)
    DH->>Src: prepareSource / tracker.start
    Src->>Src: load multivariant playlist
    Src->>Cap: parse variant[0] media playlist
    Cap-->>Cap: segment.drmInitData becomes capturedVideoKey
    Src-->>DH: onPrepared
    DH->>Lic: downloadLicense(Format with capturedVideoKey)
    Lic-->>DH: keySetId
    DH->>Idx: persist DownloadRequest.keySetId
    end

    rect rgb(235, 242, 250)
    Note over App,Prov: later — offline playback
    App->>Prov: get(mediaItem)
    Prov->>Idx: keySetId for playbackId
    Idx-->>Prov: keySetId
    Prov-->>App: DrmSessionManager in MODE_PLAYBACK
    end
```

## 3. Limitations / caveats

- **Reads the primary (variant[0]) playlist — confirmed correct for Mux.** The tracker
  auto-loads only variant[0] (`DefaultHlsPlaylistTracker.java:398-425`). Mux delivery uses one
  `#EXT-X-KEY` value across all video variants, **and** we download only a single video variant,
  so the captured key is unconditionally the right one. Audio renditions are out of scope (§4).
- **Async, possibly repeated.** The observer fires on a loader thread, and for live it re-fires
  on each refresh. Holder must be thread-safe; capture must be idempotent. `onPrepared` is a
  safe VOD read point.
- **Key never reaches the `Format`.** Chunkless prep still strips it; irrelevant here since we
  read it from the observer.
- **Persistent offline licenses are supported** by the Mux DRM service (confirmed), so the
  acquired `keySetId` is durable for offline playback. Still surface
  `OfflineLicenseHelper.getLicenseDurationRemainingSec(keySetId)` (`OfflineLicenseHelper.java:274`)
  and renew via `renewLicense(...)` for any license that carries an expiry.
- **`MuxDrmCallback` construction at download time** needs `playbackId` / `drmToken` /
  custom-domain from the `MediaItem` — confirmed available via the existing `MediaItem`
  extension getters, so §1.5 builds the callback directly.

## 4. Deferred: encrypted audio (multi-key)

v1 already **downloads** every audio and subtitle rendition (§1.4) — but they're **clear**, so
there is still only one key (video). This section records what changes if **audio becomes
encrypted**. With CMAF each audio rendition is a separate `#EXT-X-MEDIA` with its own media
playlist and `#EXT-X-KEY`, so the problem becomes **1 video key + N audio keys** — a refactor no
matter how v1 is built, so we don't optimize for it now. Sketch:

- **Selection is already done.** v1 selects the top video + all audio + all subtitle renditions
  for download (§1.4); that doesn't change. What changes is needing a *key* per encrypted audio
  rendition.
- **Acquisition.** The §2.4 observer can't reach audio playlists — the tracker auto-loads only the
  primary, and `HlsPlaylistParser` is `final` (`:76`) so `HlsDownloader`'s parses can't be wrapped.
  Instead parse each *selected* audio media playlist
  (`multivariant.copy(streamKeys).mediaPlaylistUrls`) through the **shared `CacheDataSource.Factory`**
  so fetches dedup against the segment download, and acquire one license per key.
- **Storage.** A `Map<keyId, keySetId>` keyed by Widevine KID, instead of the single
  `DownloadRequest.keySetId` (§2.2).
- **Playback (the real cost).** `DefaultDrmSessionManager` and `DownloadRequest.keySetId` each
  hold exactly one `keySetId`, so multi-key needs a custom `DrmSessionManager` that maps each
  incoming `format.drmInitData`/KID to its stored `keySetId` and opens a restore session per key.
  **Unless** Mux's license server returns a multi-key license (all KIDs in one response) — then it
  collapses back to one `keySetId` and no custom manager is needed (§6).

## 5. Alternatives considered

| Approach | Extra network | Notes |
|---|---|---|
| **Observer parser (§2.4) — chosen** | none | rides the tracker's primary-playlist load; chunkless stays on. Single video key. |
| `setAllowChunklessPreparation(false)` + read `getTrackGroups()` | 1 segment | simpler mental model; downloads a segment just to trigger `setDrmInitData`. |
| Parse the *selected* video playlist via the shared cache | none (cache dedup) | not needed for Mux (single video variant, shared key — §3); kept as the basis of the deferred audio path (§4). |

All read the **same** source: the segment-level `DrmInitData` decoded from `#EXT-X-KEY`.

## 6. Open questions

**v1 — all resolved:**
- ~~Do all video variants share one `#EXT-X-KEY`?~~ **Resolved:** yes for Mux, and we download a
  single video variant regardless — the §2.4 observer reading `variants[0]` is correct (§3).
- ~~Confirm `MuxDrmCallback`'s inputs are reachable from the `MediaItem`.~~ **Resolved:**
  `playbackId` / `drmToken` / custom-domain are available via the existing `MediaItem` extension
  getters; §1.5 builds the callback from them.
- ~~Does the `drmToken` authorize a persistent/offline license?~~ **Resolved:** yes — the Mux DRM
  service issues persistent offline licenses, so the acquired `keySetId` is durable (§3).

**Storage / lifecycle:**
- Cache + index live in `OfflineDownloadStore` (§1.7); license rides `DownloadRequest.keySetId`
  (§2.2). Removal is writer-safe via `DownloadManager.removeDownload`, and the keyset is dropped
  **locally** via `MediaDrm.removeOfflineLicense` (API 29+) — no server release, since Mux has no
  offline-license quota (§1.8). Renewal for expiry-bearing licenses via
  `OfflineLicenseHelper.renewLicense` (§3).
- Pre-API-29 has no per-license purge API; removal drops the `keySetId` reference and leaves a
  negligible orphaned CDM blob. (No remaining decision here.)

**Download stack — decided (SDK provides `MuxDownloadService`, §1.3):**
- **Scheduler:** `PlatformScheduler` (JobScheduler; resumes across reboot/network, needs only
  `RECEIVE_BOOT_COMPLETED`).
- **Manifest merge:** accepted — the `<service>` + `FOREGROUND_SERVICE*` / `RECEIVE_BOOT_COMPLETED`
  permissions merge into all SDK consumers.
- **Notifications:** fully opinionated and SDK-owned — channel, progress, and terminal
  (completed/failed) notifications, no customization hook (§1.3).

**Deferred (only if audio gets encrypted, §4):**
- Does Mux's license server return a multi-key license (all KIDs in one response)? Decides
  whether the custom multi-key `DrmSessionManager` is ever needed.
