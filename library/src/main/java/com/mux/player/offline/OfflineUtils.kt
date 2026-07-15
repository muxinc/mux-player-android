package com.mux.player.offline

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.mux.player.media.MediaItems.MUX_VIDEO_DEFAULT_DOMAIN
import com.mux.player.media.MuxDrmCallback
import com.mux.player.media.MuxDrmSessionManagerProvider
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

// TODO: Many of these functions should be internal-viz
// Preliminary Design Note: it's my hope that most of the logic a custom integration would need will
//  be here, and could be lifted out without our storage or playback-oriented code

/**
 * Creates a [DownloadHelper] that can download Mux Video HLS streams, including DRM-protected ones.
 *
 * The default [DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS] force the highest-bitrate video
 * rendition. Selecting the remaining audio and subtitle renditions, extracting the captured PSSHs,
 * and acquiring the Widevine license all happen in the prepare callback. Use [MuxDownloadCallback]
 * to handle license acquisition and selecting audio/subtitle tracks
 *
 * @param context used only to build the default [renderersFactory].
 * @param mediaSource the capturing source to prepare; build it with [MuxOfflineCmafHlsMediaSource.create].
 * @param renderersFactory renderer capabilities used for track selection during preparation.
 */
@OptIn(UnstableApi::class)
fun createMuxHlsDownloadHelper(
  context: Context,
  mediaSource: MuxOfflineCmafHlsMediaSource,
  renderersFactory: RenderersFactory = DefaultRenderersFactory(context),
): DownloadHelper =
  DownloadHelper.Factory()
    .setRenderersFactory(renderersFactory)
    .setTrackSelectionParameters(DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS)
    .create(mediaSource)

/**
 * The offline-DRM license seam (design §1.5): builds an [OfflineLicenseHelper] that acquires a
 * *persistent* Widevine license from the same Mux endpoint the online provider uses, reusing the
 * provider's public [MuxDrmSessionManagerProvider.drmHttpDataSourceFactory] /
 * [MuxDrmSessionManagerProvider.logger] and [MuxDrmCallback].
 *
 * NOTE: [OfflineLicenseHelper.downloadLicense] blocks, so call it off the caller's looper (the
 * callback below runs it on its `ioExecutor`). Release the helper when done.
 *
 * @param playbackId the Mux playback ID being downloaded.
 * @param drmToken a DRM token authorizing a persistent (offline) license for [playbackId].
 * @param licenseEndpointHost the Mux license server host, e.g. `license.mux.com`.
 */
@OptIn(UnstableApi::class)
internal fun MuxDrmSessionManagerProvider.offlineLicenseHelper(
  playbackId: String,
  drmToken: String,
  licenseEndpointHost: String = "license.$MUX_VIDEO_DEFAULT_DOMAIN",
): OfflineLicenseHelper {
  val sessionManager = DefaultDrmSessionManager.Builder()
    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
    .setMultiSession(false)
    .build(
      MuxDrmCallback(
        drmHttpDataSourceFactory,
        licenseEndpointHost = licenseEndpointHost,
        drmToken = drmToken,
        playbackId = playbackId,
        logger = logger,
      )
    )
  return OfflineLicenseHelper(sessionManager, DrmSessionEventListener.EventDispatcher())
}

/**
 * The offline-download prepare callback
 *
 * 1. selects all audio and subtitle renditions for download
 * 2. reads the captured video [DrmInitData] off [mediaSource] — the multivariant
 *    `#EXT-X-SESSION-KEY`, falling back to a media playlist's `#EXT-X-KEY`;
 * 3. (off the caller's looper, on [ioExecutor]) acquires the offline Widevine license → `keySetId`
 * 4. builds a [DownloadRequest] keyed by **playbackId**
 * 5. emits it via [onReady] and releases the helper, waiting for license acquisition if required
 *
 * Clear content is downloaded with no `keySetId`.
 *
 * Any failure routes to [onError].
 *
 * @param mediaSource the same capturing source passed to [createMuxHlsDownloadHelper]; its captures
 *   are complete by the time this callback fires.
 * @param playbackId the Mux playback ID being downloaded; used as the [DownloadRequest] id.
 * @param drmToken a DRM token authorizing a persistent license, or null for clear content.
 * @param licenseEndpointHost the Mux license server host, e.g. `license.mux.com`.
 */
@OptIn(UnstableApi::class)
class MuxDownloadCallback(
  private val mediaSource: MuxOfflineCmafHlsMediaSource,
  private val drmProvider: MuxDrmSessionManagerProvider,
  private val playbackId: String,
  private val drmToken: String?,
  private val licenseEndpointHost: String = "license.$MUX_VIDEO_DEFAULT_DOMAIN",
  private val ioExecutor: Executor,
  private val onReady: (DownloadRequest) -> Unit,
  private val onError: (IOException) -> Unit,
) : DownloadHelper.Callback {

  override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
    if (tracksInfoAvailable) {
      selectAllAudioAndTextRenditions(helper)
    }

    // Widevine PSSH, either an EXT-X-SESSION-KEY (multi-key) or the video EXT-X-KEY (single-key)
    val videoDrmInitData = mediaSource.capturedMultivariantPlaylist?.drmInitData
      ?: mediaSource.selectedMediaPlaylists.firstNotNullOfOrNull { it.drmInitData }

    if (videoDrmInitData != null) {
      acquireLicenseAsync(helper, videoDrmInitData)
    } else {
      onReady(helper.getDownloadRequest(playbackId, null))
    }
  }

  override fun onPrepareError(helper: DownloadHelper, e: IOException) {
    helper.release()
    onError(e)
  }

  private fun acquireLicenseAsync(helper: DownloadHelper, videoDrmInitData: DrmInitData) {
    ioExecutor.execute {
      try {
        val keySetId = acquireLicense(videoDrmInitData)
        val request = helper.getDownloadRequest(playbackId, null).copyWithKeySetId(keySetId)
        onReady(request)
      } catch (e: IOException) {
        onError(e)
      } catch (e: Exception) {
        onError(IOException(e))
      } finally {
        helper.release()
      }
    }
  }


  /** Acquires a persistent offline license for [videoDrmInitData], returning its `keySetId`. */
  private fun acquireLicense(videoDrmInitData: DrmInitData): ByteArray {
    val token = drmToken
      ?: throw IOException("cannot acquire an offline license without a DRM token")
    val licenseHelper = drmProvider.offlineLicenseHelper(playbackId, token, licenseEndpointHost)
    return try {
      val format = Format.Builder().setDrmInitData(videoDrmInitData).build()
      licenseHelper.downloadLicense(format)
    } finally {
      licenseHelper.release()
    }
  }

  private fun selectAllAudioAndTextRenditions(helper: DownloadHelper) {
    val mappedTrackInfo = helper.getMappedTrackInfo(/* periodIndex = */ 0)
    for (renderer in 0 until mappedTrackInfo.rendererCount) {
      val type = mappedTrackInfo.getRendererType(renderer)
      if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue // keep default top video
      val groups = mappedTrackInfo.getTrackGroups(renderer)
      for (group in 0 until groups.length) { // each rendition is its own group
        helper.addTrackSelectionForSingleRenderer(
          /* periodIndex = */ 0,
          renderer,
          DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
          listOf(DefaultTrackSelector.SelectionOverride(group, /* tracks = */ 0)),
        )
      }
    }
  }
}

/**
 * An [HlsPlaylistParserFactory] that observes the parsed result so callers can capture playlists as
 * they are loaded by the [androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker].
 *
 * This rides the tracker's existing playlist loads, so observation costs no extra network calls and
 * no segment downloads (chunkless preparation can stay on). It is **observe-only** — the parsed
 * playlists are never mutated.
 */
@OptIn(UnstableApi::class)
class CapturingHlsPlaylistParserFactory(
  val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
  val onMainManifest: (HlsMultivariantPlaylist) -> Unit,
  val onMediaPlaylist: (HlsMediaPlaylist) -> Unit,
) : HlsPlaylistParserFactory {

  // The initial tracker load uses this; the result may be either a multivariant OR (for a
  // single-rendition master-less stream) a media playlist, so we observe both types.
  override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
    observing(delegate.createPlaylistParser())

  // Media playlists referenced by a multivariant playlist — the #EXT-X-KEY lives here.
  override fun createPlaylistParser(
    multivariantPlaylist: HlsMultivariantPlaylist,
    previousMediaPlaylist: HlsMediaPlaylist?,
  ): ParsingLoadable.Parser<HlsPlaylist> =
    observing(
      delegate.createPlaylistParser(
        multivariantPlaylist, previousMediaPlaylist
      )
    )

  private fun observing(
    inner: ParsingLoadable.Parser<HlsPlaylist>,
  ): ParsingLoadable.Parser<HlsPlaylist> =
    ParsingLoadable.Parser { uri, input ->
      inner.parse(uri, input).also { parsed ->
        when (parsed) {
          is HlsMultivariantPlaylist -> onMainManifest(parsed)
          is HlsMediaPlaylist -> onMediaPlaylist(parsed)
        }
      }
    }
}

/**
 * The [DrmInitData] decoded from the (single) `#EXT-X-KEY`. The stock parser attaches the full PSSH
 * to each segment's [DrmInitData], so we read it off the first segment.
 *
 * (Deliberately NOT [HlsMediaPlaylist.protectionSchemes] — that copy has its PSSH data stripped.)
 */
@OptIn(UnstableApi::class)
fun HlsMediaPlaylist.firstSegmentDrmInitData(): DrmInitData? =
  segments.firstOrNull()?.drmInitData

/** The [DrmInitData] decoded from the multivariant playlist's `#EXT-X-SESSION-KEY`, if any. */
@OptIn(UnstableApi::class)
fun HlsMultivariantPlaylist.firstSessionKeyDrmInitData(): DrmInitData? =
  sessionKeyDrmInitData.firstOrNull()

/**
 * An [HlsMediaSource] (wrapped, since [HlsMediaSource] is final) that uses a
 * [CapturingHlsPlaylistParserFactory] to capture, as the source prepares:
 *
 * Build one with [create]. The captures are populated from the tracker's loader threads (one per
 * media playlist, so concurrently) during preparation, and are held in thread-safe containers keyed
 * by playlist URI.
 *
 * This class should only be used to download VOD streams, especially if they are DRM'd
 */
@OptIn(UnstableApi::class)
class MuxOfflineCmafHlsMediaSource private constructor(
  wrapped: HlsMediaSource,
  private val captures: Captures,
) : WrappingMediaSource(wrapped) {

  /**
   * A snapshot of the media playlists parsed so far, each paired with its `#EXT-X-KEY`
   * [DrmInitData] — deduped by playlist URI.
   *
   * The tracker only loads the media playlist for a rendition once that rendition is selected.
   *
   * This is only guaranteed complete after `DownloadHelper` preparation has completed (i.e. from
   * `DownloadHelper.Callback.onPrepared`)
   */
  val selectedMediaPlaylists: List<CapturedMediaPlaylist> get() = captures.media.values.toList()

  /** The multivariant playlist + its `#EXT-X-SESSION-KEY` [DrmInitData], once parsed. */
  val capturedMultivariantPlaylist: CapturedMultivariantPlaylist? get() = captures.multivariant

  /** A media playlist paired with the [DrmInitData] from its (single) `#EXT-X-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMediaPlaylist(
    val drmInitData: DrmInitData?,
    val playlist: HlsMediaPlaylist,
  )

  /** A multivariant playlist paired with the [DrmInitData] from its `#EXT-X-SESSION-KEY`. */
  @OptIn(UnstableApi::class)
  data class CapturedMultivariantPlaylist(
    val drmInitData: DrmInitData?,
    val playlist: HlsMultivariantPlaylist,
  )

  /** Thread-safe sink shared with the parser-factory callbacks. */
  internal class Captures {
    /** Keyed by playlist [HlsPlaylist.baseUri]: concurrent puts from per-bundle loader threads are
     *  safe, and re-parses of the same rendition (live refresh) overwrite rather than duplicate. */
    val media = ConcurrentHashMap<String, CapturedMediaPlaylist>()

    /** Only one multivariant playlist exists, so a single safely-published reference is enough. */
    @Volatile var multivariant: CapturedMultivariantPlaylist? = null
  }

  companion object {
    /**
     * Builds a [MuxOfflineCmafHlsMediaSource] for [mediaItem].
     *
     * @param dataSourceFactory upstream used to fetch playlists (and, when played, segments).
     * @param drmSessionManagerProvider optional — supply it so the DRM track isn't dropped as
     *   unselectable during preparation.
     */
    fun create(
      dataSourceFactory: DataSource.Factory,
      mediaItem: MediaItem,
      drmSessionManagerProvider: DrmSessionManagerProvider? = null,
    ): MuxOfflineCmafHlsMediaSource {
      val captures = Captures()
      val parserFactory = CapturingHlsPlaylistParserFactory(
        onMainManifest = { multivariant ->
          captures.multivariant = CapturedMultivariantPlaylist(
            drmInitData = multivariant.firstSessionKeyDrmInitData(),
            playlist = multivariant,
          )
        },
        onMediaPlaylist = { media ->
          captures.media[media.baseUri] = CapturedMediaPlaylist(
            drmInitData = media.firstSegmentDrmInitData(),
            playlist = media,
          )
        },
      )

      val wrapped = HlsMediaSource.Factory(dataSourceFactory)
        .setPlaylistParserFactory(parserFactory)
        .apply {
          drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
        }
        .createMediaSource(mediaItem)

      return MuxOfflineCmafHlsMediaSource(wrapped, captures)
    }
  }
}
