package com.mux.player.internal

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import kotlin.jvm.Throws

@Throws
@JvmSynthetic
@OptIn(UnstableApi::class)
internal fun executePost(
  uri: Uri,
  headers: Map<String, List<String>>,
  requestBody: ByteArray,
  dataSourceFactory: DataSource.Factory,
  ): ByteArray {
  val dataSpec = DataSpec.Builder()
    .setUri(uri)
    .setHttpRequestHeaders(headers.mapValues { it.value.last() })
    .setHttpBody(requestBody)
    .build()

  val dataSource = dataSourceFactory.createDataSource()
  try {
    dataSource.open(dataSpec)
    DataSourceInputStream(dataSource, dataSpec).use { bodyInputStream ->
      return bodyInputStream.readBytes()
    }
  } finally {
    runCatching { dataSource.close() }
  }
}
