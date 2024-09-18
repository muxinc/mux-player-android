package com.mux.player.media3.examples

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mux.player.media3.R
import com.mux.player.media3.databinding.ActivityPlayerCarouselBinding

/**
 * Shows a Carousel of list items backed by players
 */
class PlayerCarouselActivity : AppCompatActivity() {

  lateinit var viewBinding: ActivityPlayerCarouselBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    viewBinding = ActivityPlayerCarouselBinding.inflate(layoutInflater)
    setContentView(viewBinding.root)
  }
}