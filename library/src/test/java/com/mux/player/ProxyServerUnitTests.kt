package com.mux.player

import com.mux.player.cacheing.CDNConnection
import com.mux.player.cacheing.HttpParser
import com.mux.player.cacheing.PlayerConnection
import com.mux.player.cacheing.ProxyServer
import org.junit.Assert
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer

class ProxyServerUnitTests {
    @Test
    fun urlDecodingAndEncoding() {
        val pServer = ProxyServer(6000)
        val cdnUrl = URL("https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8")
        val localUrl = pServer.encodeUrl(cdnUrl)
        assert(localUrl.toString().equals("http://localhost:6000/1~demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8", false))
        val decodedUrl = pServer.decodeUrl(localUrl)
        assert(cdnUrl.toString().equals(decodedUrl.toString(), false))
    }

    val mainManifest = """
        #EXTM3U
        #EXT-X-VERSION:1
        ## Created with Unified Streaming Platform (version=1.11.20-26889)

        # variants
        #EXT-X-STREAM-INF:BANDWIDTH=493000,CODECS="mp4a.40.2,avc1.66.30",RESOLUTION=224x100,FRAME-RATE=24
        tears-of-steel-audio_eng=64008-video_eng=401000.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=932000,CODECS="mp4a.40.2,avc1.66.30",RESOLUTION=448x200,FRAME-RATE=24
        tears-of-steel-audio_eng=128002-video_eng=751000.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1197000,CODECS="mp4a.40.2,avc1.77.31",RESOLUTION=784x350,FRAME-RATE=24
        tears-of-steel-audio_eng=128002-video_eng=1001000.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1727000,CODECS="mp4a.40.2,avc1.100.40",RESOLUTION=1680x750,FRAME-RATE=24,VIDEO-RANGE=SDR
        tears-of-steel-audio_eng=128002-video_eng=1501000.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2468000,CODECS="mp4a.40.2,avc1.100.40",RESOLUTION=1680x750,FRAME-RATE=24,VIDEO-RANGE=SDR
        tears-of-steel-audio_eng=128002-video_eng=2200000.m3u8

        # variants
        #EXT-X-STREAM-INF:BANDWIDTH=68000,CODECS="mp4a.40.2"
        tears-of-steel-audio_eng=64008.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=136000,CODECS="mp4a.40.2"
        tears-of-steel-audio_eng=128002.m3u8
    """.trimIndent()

    val subManifest = """
        #EXTM3U
        #EXT-X-VERSION:1
        ## Created with Unified Streaming Platform (version=1.11.20-26889)
        #EXT-X-MEDIA-SEQUENCE:1
        #EXT-X-TARGETDURATION:4
        #USP-X-TIMESTAMP-MAP:MPEGTS=900000,LOCAL=1970-01-01T00:00:00Z
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-1.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-2.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-3.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-4.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-5.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-6.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-7.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-8.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-9.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-10.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-11.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-12.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-13.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-14.ts
        #EXTINF:4, no desc
        tears-of-steel-audio_eng=64008-video_eng=401000-15.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun manifestTransformationTest() {
        val pServer = ProxyServer(6000)
        val socket = Socket()
        val playerConnection = PlayerConnection(socket, pServer)
        val cdnConnection = CDNConnection(playerConnection, pServer)
        cdnConnection.openConnection(URL("https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8"))
        val parser = HttpParser(ByteArrayInputStream("".toByteArray()))
        // Rewrite Main manifest
        parser.body = ByteBuffer.wrap(mainManifest.toByteArray())
        var rewrittenManifest = cdnConnection.rewriteManifest(parser)
        checkManifest(mainManifest, rewrittenManifest)
        // Rewrite sub manifest
        parser.body = ByteBuffer.wrap(subManifest.toByteArray())
        rewrittenManifest = cdnConnection.rewriteManifest(parser)
        checkManifest(subManifest, rewrittenManifest)
    }

    private fun checkManifest(originalManifest:String, rewrittenManifest:String) {
        val bain1 = ByteArrayInputStream(originalManifest.toByteArray())
        val bain2 = ByteArrayInputStream(rewrittenManifest.toByteArray())
        val reader1 = BufferedReader(InputStreamReader(bain1, Charsets.ISO_8859_1))
        val reader2 = BufferedReader(InputStreamReader(bain2, Charsets.ISO_8859_1))
        var line1 = reader1.readLine()
        var line2 = reader2.readLine()
        while(line1 != null) {
            if (line1.startsWith("tears")) {
                assert(line2.equals("http://localhost:6000/1~demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/$line1"))
            } else {
                assert(line1.equals(line2, false))
            }
            line1 = reader1.readLine()
            line2 = reader2.readLine()
        }
    }
}