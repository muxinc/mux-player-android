package com.mux.video.media3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mux.video.media3.databinding.ActivityMainBinding
import com.mux.video.media3.databinding.ListitemExampleBinding
import com.mux.video.media3.examples.BasicPlayerActivity

class MainActivity : AppCompatActivity() {

  private lateinit var binding: ActivityMainBinding
  private val examplesView by binding::mainExampleList

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    examplesView.layoutManager = LinearLayoutManager(this)
  }

  private fun setUpExampleList() {
    val exampleAdapter = ExampleListAdapter(this, examples)
    examplesView.adapter = exampleAdapter
  }

  private val examples = listOf(
    Example(
      title = "Basic Foreground Player",
      destination = Intent(this@, BasicPlayerActivity::class.java)
    )
  )
}

data class Example(
  val title: String,
  val destination: Intent
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
    holder.itemView.setOnClickListener { context.startActivity(example.destination) }
  }

  class Holder(
    val itemView: View,
    val viewBinding: ListitemExampleBinding
  ) : RecyclerView.ViewHolder(itemView)
}
