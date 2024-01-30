package com.mux.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheDatastoreInstrumentationTests {

  private val appContext get() = InstrumentationRegistry.getInstrumentation().targetContext

  // todo -test cases
  //  basic init: Dirs created, temp dir clear, db file in place
  //  open-close: (if possible) Like the caller handled an exception we weren't a part of, making sure db not allocated
  //  open-close-open: Same conditions as basic init should be satisfied
  //  open-open: Same conditions as basic init
  //
  // todo - more test cases
  //  writing:
  //  createTempDownloadFile: creates file, file has basename in it
  //  moveFromTempFile: File written when moveFromTempFile, has content of temp file
  //    .. even if there was another file first
  //  writeRecord: Returns successful if a new row
  //    still returns successful if not a new row
  //  readRecord: Works for segments
  //    Works for not-segments
  //    Misses gracefully if the file underneath is deleted

  @Test
  fun testBasicInitialization() {
    // todo
    val
  }
}