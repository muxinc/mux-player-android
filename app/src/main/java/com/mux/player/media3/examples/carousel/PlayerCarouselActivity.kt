package com.mux.player.media3.examples.carousel

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.VERTICAL
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.FullScreenCarouselStrategy
import com.mux.player.media.MediaItems
import com.mux.player.media3.PlaybackIds
import com.mux.player.media3.R
import com.mux.player.media3.databinding.ActivityPlayerCarouselBinding

/**
 * Shows a Carousel of list items that play video (like reels)
 *
 * The carousel is a RecyclerView with a [CarouselLayoutManager].
 */
class PlayerCarouselActivity : AppCompatActivity() {

  private lateinit var viewBinding: ActivityPlayerCarouselBinding
  private val carousel: RecyclerView get() = viewBinding.carousel

  private val viewModel by viewModels<PlayerCarouselViewModel>()
  private var adapter: VideoCarouselAdapter = createAdapter(createListItems())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    viewBinding = ActivityPlayerCarouselBinding.inflate(layoutInflater)
    setContentView(viewBinding.root)

    carousel.adapter = adapter
    setUpRecyclerView(carousel)
  }

  private fun setUpRecyclerView(rv: RecyclerView) {
    val layoutManager = CarouselLayoutManager(FullScreenCarouselStrategy())
    layoutManager.orientation = VERTICAL
    val snapHelper = PlayerSnapHelper(
      viewModel = { viewModel },
      carousel = { carousel },
      adapter = { adapter }
    )

    rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
      override fun onChildViewAttachedToWindow(view: View) {
        // If the player is idle when we bind, the UI is initializing and we should autoplay
        if (viewModel.player.playbackState == Player.STATE_IDLE
          || viewModel.player.currentMediaItem == null) {
          val item = adapter.items[0]
          viewModel.changeMediaItem(item.mediaItem)
          viewModel.playIntoView(view.findViewById(R.id.item_fullscreen_player_playerview))
          viewModel.play()

          Log.d("PlayerCarouselActivity", "Player was idle. Autoplaying")
        }
      }
      override fun onChildViewDetachedFromWindow(view: View) {
      }
    })

    rv.layoutManager = layoutManager
    snapHelper.attachToRecyclerView(rv)
  }

  private fun createAdapter(listItems: List<CarouselItem> = listOf()): VideoCarouselAdapter {
    return VideoCarouselAdapter(
      items = listItems,
      inflater = { layoutInflater },
      viewModel = { viewModel }
    )
  }

  private fun createListItems(): List<CarouselItem> {
    return listOf(
      CarouselItem(
        title = "Tears of Steel",
        description = "A time travel story with really cool high-quality CGI and some truly" +
            " powerful dialogue writing",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.TEARS_OF_STEEL),
      ),
      CarouselItem(
        title = "Sintel",
        description = "A knight meets a baby dragon and together they do some adventure stuff." +
            " The dragon is super adorable, I forget what actually happens though",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.SINTEL),
      ),
      CarouselItem(
        title = "Robot shooting a bird",
        description = "Robot shooting a bird",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.ROBOT_SHOOTING_A_BIRD),
      ),
      CarouselItem(
        title = "Making of Sintel",
        description = "A documentary about the making of Sintel",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.MAKING_OF_SINTEL),
      ),
      CarouselItem(
        title = "Big Buck Bunny",
        description = "A rabbit gets harassed by a bunch of punk squirrels.",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.BIG_BUCK_BUNNY),
      ),
      CarouselItem(
        title = "Mux Marketing Video",
        description = "We make video for the internet. I mean we don't make *videos* we make " +
            "video infrastructure. Although we did make _this_ video, so I guess we do make videos." +
            "Come to think of it we actually make a lot of videos. Every time we ship we do, plus" +
            " we have tutorials and other stuff on our youtube channel. I didn't have to mention " +
            "that, but I want this to be more than 5 lines to I can see how the scrim looks",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.MUX_MARKETING_VIDEO),
      ),
      CarouselItem(
        title = "Making of Big Buck Bunny",
        description = "A group of nerds talk about making big buck bunny, presumably because they " +
            "were the ones that did that",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.MAKING_OF_BUCK_BUNNY),
      ),
      CarouselItem(
        title = "Elephant's Dream",
        description = "A surreal fantasy action movie. The first Open Movie Project movie. It's " +
            "old but kinda trippy",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.ELEPHANTS_DREAM),
      ),
      CarouselItem(
        title = "View From a Blue Moon",
        description = "I don't know what is happening in this",
        mediaItem = MediaItems.fromMuxPlaybackId(PlaybackIds.VIEW_FROM_A_BLUE_MOON),
      ),
    )
  }
}

/**
 * A [CarouselSnapHelper] that facilitates playing video the currently-snapped View's adapter pos
 *
 * It changes the MediaItem being played and binds the correct PlayerView to the player
 */
class PlayerSnapHelper(
  val viewModel: () -> PlayerCarouselViewModel,
  val carousel: () -> RecyclerView,
  val adapter: () -> VideoCarouselAdapter,
) : CarouselSnapHelper() {

  private var oldSnapView: View? = null
  private var oldMediaItem: CarouselItem? = null

  override fun findSnapView(layoutManager: RecyclerView.LayoutManager?): View? {
    val snapView = super.findSnapView(layoutManager)
    Log.d("PlayerSnapHelper", "findSnapView: Found $snapView")

    if (snapView != null && snapView != oldSnapView) {
      val adapterPos = carousel().getChildAdapterPosition(snapView)
      val snapItem = adapter().items[adapterPos]

      if (oldMediaItem == null || oldMediaItem != snapItem) {
        viewModel().pause()
        viewModel().changeMediaItem(snapItem.mediaItem)
        viewModel().play()
      }

      viewModel().playIntoView(snapView.findViewById(R.id.item_fullscreen_player_playerview))
    }

    return snapView
  }
}

class VideoCarouselAdapter(
  val items: List<CarouselItem>,
  val inflater: () -> LayoutInflater,
  val viewModel: () -> PlayerCarouselViewModel,
) : Adapter<CarouselViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
    return CarouselViewHolder(
      inflater().inflate(R.layout.item_fullscreen_player, parent, false),
      viewModel = viewModel
    )
  }

  override fun getItemCount(): Int {
    return items.size
  }

  override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
    val item = items[position]
    holder.title.text = item.title
    holder.desc.text = item.description
  }
}

class CarouselViewHolder(
  itemRoot: View,
  viewModel: () -> PlayerCarouselViewModel,
) : ViewHolder(itemRoot) {
  @Suppress("MemberVisibilityCanBePrivate")
  val playerView: PlayerView = itemRoot.findViewById(R.id.item_fullscreen_player_playerview)
  val title: TextView = itemRoot.findViewById(R.id.include_carousel_metadata_title)
  val desc: TextView = itemRoot.findViewById(R.id.include_carousel_metadata_description)

  init {
    playerView.setOnClickListener { viewModel().togglePlay() }
  }
}

data class CarouselItem(
  val mediaItem: MediaItem,
  val title: String,
  val description: String,
)
