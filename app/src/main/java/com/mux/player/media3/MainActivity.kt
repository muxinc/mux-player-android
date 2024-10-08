package com.mux.player.media3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mux.player.media3.databinding.ActivityMainBinding
import com.mux.player.media3.databinding.ListitemExampleBinding
import com.mux.player.media3.examples.BasicPlayerActivity
import com.mux.player.media3.examples.ConfigurablePlayerActivity
import com.mux.player.media3.examples.SmartCacheActivity
import com.mux.player.media3.examples.carousel.PlayerCarouselActivity

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private val examplesView get() = binding.mainExampleList

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    examplesView.layoutManager = LinearLayoutManager(this)

    binding.mainExampleTb.apply {
      setLogo(R.drawable.mux_logo)
    }

    setUpExampleList()
  }

  private fun setUpExampleList() {
    val exampleAdapter = ExampleListAdapter(this, examples())
    examplesView.adapter = exampleAdapter
  }

  private fun examples() = listOf(
    Example(
      title = "Basic Player Screen",
      destination = Intent(this@MainActivity, BasicPlayerActivity::class.java)
    ),
    Example(
      title = "Configurable Player Screen",
      destination = Intent(this@MainActivity, ConfigurablePlayerActivity::class.java)
    ),
    Example(
      title = "Fullscreen Carousel",
      destination = Intent(this@MainActivity, PlayerCarouselActivity::class.java),
    ),
    Example(
      title = "Smart Caching",
      destination = Intent(this@MainActivity, SmartCacheActivity::class.java)
    ),
  )
}

data class Example(
  val title: String,
  val destination: Intent,
)

class ExampleListAdapter(
  private val context: Context,
  private val examples: List<Example>
) : RecyclerView.Adapter<ExampleListAdapter.Holder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
    val viewBinding = ListitemExampleBinding.inflate(
      LayoutInflater.from(context),
      parent,
      false
    )
    return Holder(
      viewBinding = viewBinding,
      itemView = viewBinding.root
    )
  }

  override fun getItemCount(): Int = examples.size

  override fun onBindViewHolder(holder: Holder, position: Int) {
    val example = examples[position]
    holder.viewBinding.exampleName.text = example.title
    holder.viewBinding.root.setOnClickListener { context.startActivity(example.destination) }
  }

  class Holder(
    val itemView: View,
    val viewBinding: ListitemExampleBinding
  ) : RecyclerView.ViewHolder(itemView)
}
