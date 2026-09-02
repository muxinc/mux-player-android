package com.mux.player.offline

import com.mux.player.AbsRobolectricTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraDataTests : AbsRobolectricTest() {

  @Test
  fun `a pssh survives a round trip`() {
    // real pssh's aren't valid utf-8 or json-safe, hence the base64 in between
    val pssh = byteArrayOf(0, 1, 2, -3, -128, 127, 0)

    val parsed = ExtraData.fromUtf8Bytes(ExtraData(widevinePssh = pssh).toUtf8Bytes())

    assertArrayEquals(pssh, parsed.widevinePssh)
  }

  @Test
  fun `clear content round trips with no pssh`() {
    val parsed = ExtraData.fromUtf8Bytes(ExtraData().toUtf8Bytes())

    assertNull(parsed.widevinePssh)
  }

  @Test
  fun `data that isn't ours parses as empty instead of throwing`() {
    // DownloadRequest.data defaults to empty, and downloads from older SDKs have no ExtraData
    assertNull(ExtraData.fromUtf8Bytes(byteArrayOf()).widevinePssh)
    assertNull(ExtraData.fromUtf8Bytes("not json".toByteArray()).widevinePssh)
    assertNull(ExtraData.fromUtf8Bytes("""{"widevinePssh":"!not base64!"}""".toByteArray()).widevinePssh)
  }
}
