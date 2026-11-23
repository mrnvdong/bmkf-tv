package com.bmkf.tv

import com.bmkf.tv.data.model.MediaType
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.source.CoBePhimProvider
import com.bmkf.tv.data.source.ColaTvClient
import com.bmkf.tv.data.source.GaVangClient
import com.bmkf.tv.data.source.K20StremioProvider
import com.bmkf.tv.data.source.KhoPhimProvider
import com.bmkf.tv.data.source.KkPhimProvider
import com.bmkf.tv.data.source.ShowBoxProvider
import com.bmkf.tv.data.source.SportTvProvider
import com.bmkf.tv.data.source.VsMovProvider
import com.bmkf.tv.data.source.XoiCheClient
import com.bmkf.tv.data.source.XoiLacClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParsingTest {

    @Test
    fun kkPhimParsesRelativeImageUrlsAndEpisodes() {
        val provider = KkPhimProvider({ "https://phimapi.com" }, { "https://sc.k-20.xyz" })
        val listJson = """
            {
              "status": true,
              "data": {
                "APP_DOMAIN_CDN_IMAGE": "https://phimimg.com",
                "items": [
                  {
                    "name": "Thay Mặt Con Trai",
                    "origin_name": "On Behalf Of My Son",
                    "slug": "thay-mat-con-trai",
                    "type": "single",
                    "poster_url": "uploads/movies/poster.webp",
                    "thumb_url": "uploads/movies/thumb.webp",
                    "quality": "FHD",
                    "lang": "Vietsub",
                    "year": 2026
                  }
                ]
              }
            }
        """.trimIndent()

        val paged = provider.parseMovieListResponse(listJson, 1)
        assertEquals(1, paged.items.size)
        val item = paged.items.first()
        assertEquals(SourceId.KKPHIM, item.source)
        assertEquals("https://phimimg.com/uploads/movies/poster.webp", item.posterUrl)
        assertEquals("https://phimimg.com/uploads/movies/thumb.webp", item.backdropUrl)
        assertEquals("FHD", item.quality)
    }

    @Test
    fun vsMovNormalizesServerWhitespaceAndEmbedUrl() {
        val provider = VsMovProvider({ "https://vsmov.com/api" }, { "https://sc.k-20.xyz" })
        val detailJson = """
            {
              "status": true,
              "movie": {
                "name": "Bản Chất Của Lãng Mạn",
                "origin_name": "Be Melodramatic",
                "slug": "ban-chat-cua-lang-man",
                "content": "Mô tả phim",
                "type": "series",
                "poster_url": "https://vsmov.com/storage/images/p.jpg",
                "thumb_url": "https://vsmov.com/storage/images/t.jpg",
                "year": 2019
              },
              "episodes": [
                {
                  "server_name": "Vietsub\r\n                        #1",
                  "server_data": [
                    {
                      "name": "1",
                      "slug": "tap-1",
                      "link_embed": "https://v8.streamvsmov.com/video/0cae4bec-f73f-4446-ab57-f200c465a84a"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val detail = provider.parseMovieDetailResponse(detailJson, "ban-chat-cua-lang-man")
        assertEquals("Vietsub #1", detail.servers.first().serverName)
        assertEquals("Tập 1", detail.servers.first().episodes.first().name)
        assertTrue(detail.servers.first().episodes.first().embedUrl.contains("streamvsmov.com"))
    }

    @Test
    fun coBePhimParsesEpisodesAndExtractsRscStreamLink() {
        val provider = CoBePhimProvider(
            { "https://egluy2hhb21vaw5ndw9pbmhl.darkbytes.xyz/api/v1" },
            { "https://cobephim.wiki" },
            { "https://sc.k-20.xyz" }
        )

        val epsJson = """
            [
              {"id": 899176, "name": "1", "server": "Phụ đề (KK)", "slug": "tap-1", "season_number": 2},
              {"id": 802084, "name": "1", "server": "Song Ngữ (VS)", "slug": "tap-1", "season_number": 1}
            ]
        """.trimIndent()
        val servers = provider.parseEpisodesArray(epsJson)
        assertEquals(2, servers.size)
        assertTrue(servers.first().serverName.contains("(KK)"))

        val rscSample = """["${'$'}","${'$'}L20",null,{"episode":{"id":899176,"name":"1","server":"Phụ đề (KK)","slug":"tap-1","type":"m3u8","link":"https://a.kvp726.com/20261121/Ofd1WUBG/index.m3u8"}}]"""
        val extracted = provider.extractEpisodeStreamFromRsc(rscSample)
        assertNotNull(extracted)
        assertEquals("m3u8", extracted!!.first)
        assertEquals("https://a.kvp726.com/20261121/Ofd1WUBG/index.m3u8", extracted.second)
    }

    @Test
    fun k20StremioParsesManifestAndStreamHeadersAndSubtitles() {
        val provider = K20StremioProvider { "https://sc.k-20.xyz/manifest.json" }
        val streamJson = """
            {
              "streams": [
                {
                  "name": "NguonC • Direct CDN",
                  "title": "Vietsub #1 - FULL",
                  "url": "https://sc.k-20.xyz/nguonc-hls.m3u8?hash=abc&mode=direct",
                  "subtitles": [
                    {"id": "vsmov-sub-vie", "url": "https://sc.k-20.xyz/proxy-sub.vtt?url=test", "lang": "vie"}
                  ],
                  "behaviorHints": {
                    "proxyHeaders": {
                      "request": {
                        "Referer": "https://embed15.streamc.xyz/",
                        "Origin": "https://embed15.streamc.xyz"
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val streams = provider.parseStreamsResponse(streamJson)
        assertEquals(1, streams.size)
        val s = streams.first()
        assertEquals("https://embed15.streamc.xyz/", s.headers["Referer"])
        assertEquals(1, s.subtitles.size)
        assertEquals("vi", s.subtitles.first().language)

        val metaJson = """
            {
              "meta": {
                "id": "nguonc:dhindora-phan-2",
                "type": "series",
                "name": "Dhindora",
                "videos": [
                  {"id": "nguonc:dhindora-phan-2:tap-1", "title": "1", "season": 1, "episode": 1},
                  {"id": "nguonc:dhindora-phan-2:tap-2", "title": "2", "season": 1, "episode": 2}
                ]
              }
            }
        """.trimIndent()
        val detail = provider.parseMetaDetail(metaJson, "nguonc:dhindora-phan-2", MediaType.SERIES)
        assertEquals(1, detail.servers.size)
        assertEquals(2, detail.servers.first().episodes.size)
        assertEquals("Tập 1", detail.servers.first().episodes.first().name)
    }

    @Test
    fun showBoxParses4kMkvStreamsCinemetaSeasonsAndOpenSubtitles() {
        val provider = ShowBoxProvider(
            manifestUrlProvider = { "https://showbox.codiv.dpdns.org/cfg/manifest.json" },
            k20BaseUrlProvider = { "https://sc.k-20.xyz" }
        )

        val streamJson = """
            {
              "streams": [
                {
                  "name": "Showbox",
                  "title": "Game.of.Thrones.S01E01.2160p.HDR.mkv\n4K • 11.66 GB",
                  "url": "https://usa7-as13.shegu.net/vip/tv.9.S1E1.mkv?KEY1=abc",
                  "quality": "4K",
                  "size": "11.66 GB",
                  "behaviorHints": {
                    "proxyHeaders": {
                      "request": {
                        "Referer": "https://www.febbox.com/"
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val streams = provider.parseShowBoxStreamsResponse(streamJson)
        assertEquals(1, streams.size)
        assertFalse(streams.first().isHls)
        assertEquals("https://www.febbox.com/", streams.first().headers["Referer"])
        assertTrue(streams.first().name.contains("4K"))

        val metaJson = """
            {
              "meta": {
                "id": "tt0944947",
                "type": "series",
                "name": "Game of Thrones",
                "videos": [
                  {"id": "tt0944947:0:1", "name": "Special", "season": 0, "episode": 1},
                  {"id": "tt0944947:1:1", "name": "Winter Is Coming", "season": 1, "episode": 1}
                ]
              }
            }
        """.trimIndent()
        val detail = provider.parseCinemetaDetail(metaJson, "tt0944947", MediaType.SERIES)
        // Each season is split into ShowBox 4K + ShowBox 1080P (2 seasons x 2 tiers = 4 servers)
        assertEquals(4, detail.servers.size)
        assertEquals(1, detail.servers[0].season)
        assertTrue(detail.servers[0].serverName.contains("4K"))
        assertTrue(detail.servers[1].serverName.contains("1080P"))
        assertEquals(0, detail.servers.last().season)

        // Verify tier ordering separates 4K vs 1080P cleanly
        val mixedJson = """
            {
              "streams": [
                {"name": "Showbox", "title": "Ep.2160p.DV.mkv", "url": "https://cdn/4k_dv.mkv", "quality": "4K"},
                {"name": "Showbox", "title": "Ep.2160p.HDR.mkv", "url": "https://cdn/4k_hdr.mkv", "quality": "4K"},
                {"name": "Showbox", "title": "Ep.1080p.WEB.mp4", "url": "https://cdn/1080.mp4", "quality": "1080P"}
              ]
            }
        """.trimIndent()
        val mixed = provider.parseShowBoxStreamsResponse(mixedJson)
        val ordered4k = provider.orderStreamsByTier(mixed, ShowBoxProvider.TIER_4K)
        assertEquals("https://cdn/4k_hdr.mkv", ordered4k[0].url)
        assertEquals("https://cdn/4k_dv.mkv", ordered4k[1].url)
        assertEquals("https://cdn/1080.mp4", ordered4k[2].url)

        val ordered1080 = provider.orderStreamsByTier(mixed, ShowBoxProvider.TIER_1080P)
        assertEquals("https://cdn/1080.mp4", ordered1080[0].url)

        val subsJson = """
            {
              "subtitles": [
                {"lang": "eng", "url": "https://subs5.strem.io/en/file/1"},
                {"lang": "vie", "url": "https://subs5.strem.io/en/file/2"}
              ]
            }
        """.trimIndent()
        val subs = provider.parseOpenSubtitlesResponse(subsJson)
        assertEquals(2, subs.size)
        assertEquals("vi", subs.first().language)
        assertEquals("application/x-subrip", subs.first().mimeType)
    }

    @Test
    fun sportTvFiltersDonatePrioritizesDirectHlsAndParsesCollections() {
        val provider = SportTvProvider(
            configUrlProvider = {
                "https://nuviosports.xyz/eyJ0aW1lem9uZSI6IkFzaWEvU2FpZ29uIiwic3BvcnRzIjoiZm9vdGJhbGwsbW1hIn0/collections.json"
            }
        )
        assertEquals(
            "https://nuviosports.xyz/eyJ0aW1lem9uZSI6IkFzaWEvU2FpZ29uIiwic3BvcnRzIjoiZm9vdGJhbGwsbW1hIn0",
            provider.baseUrl()
        )

        val streamsJson = """
            {
              "streams": [
                {
                  "name": "🧿 Support Us",
                  "externalUrl": "https://nuviosports.xyz/donate"
                },
                {
                  "name": "🌐 Web Stream",
                  "title": "DaddyLive HD",
                  "externalUrl": "https://nuviosports.xyz/watch?url=test",
                  "score": 90
                },
                {
                  "name": "⚡ Direct Stream",
                  "title": "Sky Sports Main Event",
                  "url": "https://nuviosports.xyz/api/manifest.m3u8?url=https%3A%2F%2Fcdn.hdm1.net%2Fsky%2Findex.m3u8",
                  "score": 80,
                  "behaviorHints": {
                    "proxyHeaders": {
                      "request": {
                        "Referer": "https://wideiptv.top/"
                      }
                    }
                  }
                }
              ]
            }
        """.trimIndent()
        val streams = provider.parseStreamsResponse(streamsJson)
        // Unwrapped direct CDN first + proxy backup second + WebView third (donate filtered out)
        assertEquals(3, streams.size)
        assertFalse(streams.first().isWebViewFallback)
        assertTrue(streams.first().isHls)
        assertEquals("https://cdn.hdm1.net/sky/index.m3u8", streams.first().url)
        assertEquals("https://wideiptv.top/", streams.first().headers["Referer"])
        assertTrue(streams[1].url.contains("nuviosports.xyz/api/manifest.m3u8"))
        assertTrue(streams.last().isWebViewFallback)

        val collectionsJson = """
            [
              {
                "id": "collection-sports-replays",
                "title": "Sports Replays",
                "folders": [
                  {
                    "id": "folder-football-replays",
                    "title": "Football",
                    "catalogSources": [
                      {"catalogId": "nuvio_sports_replays_football_premier_league", "catalogName": "Premier League", "type": "tv"}
                    ]
                  }
                ]
              }
            ]
        """.trimIndent()
        val tabs = provider.parseCollectionsTabs(collectionsJson)
        assertEquals(1, tabs.size)
        assertEquals("tv|nuvio_sports_replays_football_premier_league", tabs.first().id)

        // Verify HighFly Sports manifest parsing + filtering Tip:/health & Streams Unavailable
        val hfProvider = SportTvProvider(
            configUrlProvider = { "https://nuviosports.xyz/cfg/collections.json" },
            highFlyUrlProvider = { "https://sports.highfly.dev/abc/manifest.json" }
        )
        assertEquals("https://sports.highfly.dev/abc", hfProvider.highFlyBaseUrl())

        val hfStreamsJson = """
            {
              "streams": [
                {"name": "Leaf (Strong) · SKY SPORTS", "title": "1920x1080", "url": "https://papacito.cfd/m3u/sky/live.m3u8?ft=jwt"},
                {"name": "Tip: Use an external player", "url": "https://sports.highfly.to/health"},
                {"name": "Streams Unavailable", "url": "https://www.google.com"}
              ]
            }
        """.trimIndent()
        val hfStreams = hfProvider.parseStreamsResponse(hfStreamsJson)
        assertEquals(1, hfStreams.size)
        assertTrue(hfStreams.first().isHls)
        assertTrue(hfStreams.first().url.contains("papacito.cfd"))
    }

    @Test
    fun xoiLacParsesHomepageMatchesListStreamAndDirectFlvUrl() {
        val xl = XoiLacClient { "https://xoilacz.ca" }
        val sampleHome = """
            <div class="grid-matches__item grid-matches__item-match match-football-item"
                 data-hot="1" data-status="4" data-sport="football">
                <a href="/truc-tiep/bologna-vs-udinese-luc-2100-ngay-22-11-2026/"
                   title="Bologna vs Udinese lúc 21:00 ngày 22/11/2026"></a>
                <div class="grid-match__league"><span>Serie A</span></div>
                <div class="grid-match__date">21:00 - 22.11</div>
                <img src='https://imgts.sportpulseapiz.com/football/team/v2y8m4zhdw6ql07/image/small' />
                <div class="grid-match__team--name grid-match__team--home-name">Bologna</div>
                <div class="grid-match__team--name grid-match__team--away-name">Udinese</div>
            </div>
        """.trimIndent()
        val matches = xl.parseHomepageMatches(sampleHome)
        assertEquals(1, matches.size)
        assertEquals("Bologna vs Udinese", matches.first().title)
        assertEquals("Serie A", matches.first().league)
        assertTrue(matches.first().isLive)

        val watchHtml = """
            <a title="Server 1" data-link="0"></a>
            <script>
            var list_stream = [["https:\/\/xlz.domainkqt.cc\/ajax\/chanel\/type\/8\/link\/channel2"],["https:\/\/xlz.domainkqt.cc\/ajax\/chanel\/type\/5\/link\/channel-2"]];
            </script>
        """.trimIndent()
        val listUrls = xl.parseListStreamUrls(watchHtml)
        assertEquals(2, listUrls.size)
        assertEquals("https://xlz.domainkqt.cc/ajax/chanel/type/8/link/channel2", listUrls[0])

        val embedHtml = """
            <script>
            var urlStream = "https://live2.domaincdn.cc/livecdn/channel-2.flv?auth_key=abc";
            var adsTvc = [{"file":"https://cdn.lfastcdn.com/2026/06/tvc-wc-2026-hd.mp4"}];
            </script>
        """.trimIndent()
        assertEquals(
            "https://live2.domaincdn.cc/livecdn/channel-2.flv?auth_key=abc",
            xl.extractUrlStream(embedHtml)
        )
    }

    @Test
    fun gaVangParsesHomepageMatchesAndCommentatorStreams() {
        val gv = GaVangClient { "https://gavang69.com/" }
        val sampleHome = """
            <div class="match-card relative bals-upcoming-match" data-accent="featured" data-streamer-ids="[&quot;69aa9525f5ec459ae275b43f&quot;,&quot;6a9776215126529dede9bf51&quot;]">
                <a href="https://gavang69.com/truc-tiep/liverpool-vs-manchester-united-ngay-22-11-2026/"
                   data-title="Liverpool vs Man Utd lúc 23:30 ngày 22/11/2026"></a>
                <span class="bals-competition-name">English Premier League</span>
                <div class="bals-match-time"><span>23:30</span><span>22/11</span>
                    <span class="bals-live-pill" style="display:none">Live</span>
                </div>
                <div class="bals-home-team-name">Liverpool</div>
                <div class="bals-away-team-name">Man Utd</div>
                <img data-src="https://static.sportsfeed.pro/football/team/liv.png" />
            </div>
            <div class="match-card relative bals-live-match" data-accent="hot" data-streamer-ids="[&quot;6acdcf4a1ba0f2f86b254fe9&quot;]">
                <a href="https://gavang69.com/truc-tiep/bologna-vs-udinese-ngay-22-11-2026/"
                   data-title="Bologna vs Udinese lúc 21:00 ngày 22/11/2026"></a>
                <span class="bals-competition-name">Italian Serie A</span>
                <div class="bals-match-time"><span>21:00</span><span>22/11</span>
                    <span class="bals-live-pill">Live</span>
                </div>
                <span class="bals-status-name">Hiệp 2</span><span class="bals-elapsed-time">46&#039;</span>
                <div class="bals-home-team-name">Bologna</div>
                <div class="bals-away-team-name">Udinese</div>
                <img data-src="https://static.sportsfeed.pro/football/team/bol.png" />
            </div>
            <div class="match-card relative bals-live-match" data-accent="" data-streamer-ids="[]">
                <a href="https://gavang69.com/truc-tiep/no-stream-tracker-only/" data-title="No Stream"></a>
                <div class="bals-home-team-name">Tracker A</div>
                <div class="bals-away-team-name">Tracker B</div>
            </div>
            <div class="match-card relative bals-upcoming-match" data-accent="hot" data-sport="basketball" data-streamer-ids="[&quot;6ad310e0f72fb30b98548582&quot;]">
                <a href="https://gavang69.com/basketball/new-york-knicks-vs-indiana-pacers-on-23-11-2026/"
                   data-title="New York Knicks vs Indiana Pacers lúc 08:00 ngày 23/11/2026"></a>
                <span class="bals-competition-name">National Basketball Association</span>
                <div class="bals-match-time"><span>08:00</span><span>23/11</span>
                    <span class="bals-live-pill" style="display:none">Live</span>
                </div>
                <div class="bals-home-team-name">New York Knicks</div>
                <div class="bals-away-team-name">Indiana Pacers</div>
            </div>
        """.trimIndent()

        val matches = gv.parseHomepageMatches(sampleHome)
        // Tracker-only match (0 streamers) is filtered out; 3 playable matches remain, LIVE sorted first
        assertEquals(3, matches.size)
        assertEquals("Bologna vs Udinese", matches[0].title)
        assertTrue(matches[0].isLive)
        assertEquals("Hiệp 2 46'", matches[0].statusText)
        assertEquals("Liverpool vs Man Utd", matches[1].title)
        assertFalse(matches[1].isLive)
        assertEquals(2, matches[1].streamerCount)
        assertEquals("basketball", matches[2].sport)

        val watchHtml = """
            <div class="commentator-card flex"
                 data-stream-url="https://playback.livetl002.com/live/6acdcf4a1ba0f2f86b254fe9.m3u8"
                 data-stream-url-flv="https://playback.livetl002.com/live/6acdcf4a1ba0f2f86b254fe9.flv"
                 data-cdn="tencent"
                 data-stream-name="Lá Chanh"></div>
            <div class="commentator-card flex"
                 data-stream-url="https://playback.livetl004.com/live/6acdcf4a1ba0f2f86b254fe9.m3u8"
                 data-stream-url-flv="https://playback.livetl004.com/live/6acdcf4a1ba0f2f86b254fe9.flv"
                 data-cdn="alibaba"
                 data-stream-name="Lá Chanh 2"></div>
        """.trimIndent()

        val streams = gv.parseWatchPageStreams(watchHtml, "https://gavang69.com")
        assertEquals(4, streams.size)
        assertTrue(streams[0].isHls)
        assertTrue(streams[0].name.contains("Lá Chanh"))
        assertTrue(streams[0].name.contains("Tencent"))
        assertEquals("https://gavang69.com/", streams[0].headers["Referer"])
        assertTrue(streams[1].isHls)
        assertTrue(streams[1].name.contains("Alibaba"))
        assertFalse(streams[2].isHls)
        assertTrue(streams[2].url.endsWith(".flv"))

        // Fallback encoded playyyz iframe parsing when no commentator-card exists
        val fallbackHtml = """
            <iframe src="https://playyyz4.cc/e?hls=https%3A%2F%2Fplayback.livetl002.com%2Flive%2Fabc.m3u8&flv=https%3A%2F%2Fplayback.livetl002.com%2Flive%2Fabc.flv"></iframe>
        """.trimIndent()
        val fallbackStreams = gv.parseWatchPageStreams(fallbackHtml, "https://gavang69.com")
        assertEquals(2, fallbackStreams.size)
        assertEquals("https://playback.livetl002.com/live/abc.m3u8", fallbackStreams[0].url)
        assertEquals("https://playback.livetl002.com/live/abc.flv", fallbackStreams[1].url)
    }

    @Test
    fun xoiCheParsesApiMatchesSkipsRtmpAndExtractsFallbackWatchStreams() {
        val xc = XoiCheClient { "https://xoiche.live/" }
        val apiJson = """
            {
              "count": 1,
              "results": [
                {
                  "slug": "bologna-vs-udinese-22-11-2026-807965",
                  "status": "live",
                  "start_time": "2026-11-22T21:00:00+07:00",
                  "home_score": 1,
                  "away_score": 1,
                  "match_time": "90+",
                  "is_hot": true,
                  "home_team_name": "Bologna",
                  "away_team_name": "Udinese",
                  "home_team_logo": "https://apiv3.apifootball.com/badges/4983_bologna.jpg",
                  "tournament_name": "Serie A",
                  "sport_slug": "football",
                  "sport_name": "Bóng đá",
                  "commentator_streams": [
                    {
                      "commentator": { "name": "BLV Khô Mật" },
                      "stream_url": "https://livecdn321.cdnfastter.xyz/live/live_b30ba5315c/index.m3u8?token=abc",
                      "backup_stream_url": "rtmp://rtmp.cdnfastter.xyz/live/live_b30ba5315c",
                      "flv_stream_url": "https://live.khoga.space/live/livehub13.flv"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val matches = xc.parseMatchesResponse(apiJson, "https://xoiche.live")
        assertEquals(1, matches.size)
        val m = matches.first()
        assertEquals("Bologna vs Udinese", m.title)
        assertTrue(m.isLive)
        assertEquals("1-1", m.scoreText)
        assertEquals("21:00 • 22/11", m.kickoffTime)
        assertEquals(listOf("BLV Khô Mật"), m.blvNames)
        // rtmp:// must be excluded; HLS comes before FLV
        assertEquals(2, m.streams.size)
        assertTrue(m.streams[0].isHls)
        assertTrue(m.streams[0].url.contains("cdnfastter.xyz"))
        assertFalse(m.streams[1].isHls)
        assertTrue(m.streams[1].url.endsWith(".flv"))

        val nuxtFallback = """
            <script>["https:\u002F\u002Flivecdn321.cdnfastter.xyz\u002Flive\u002Ftest\u002Findex.m3u8?token=1"]</script>
        """.trimIndent()
        val fb = xc.extractFallbackWatchStreams(nuxtFallback, "https://xoiche.live")
        assertEquals(1, fb.size)
        assertEquals("https://livecdn321.cdnfastter.xyz/live/test/index.m3u8?token=1", fb.first().url)
    }

    @Test
    fun colaTvParsesMatchesAndMultiCdnDetailStreams() {
        val cl = ColaTvClient { "https://colatv111.live/" }
        val listJson = """
            {
              "code": "0000",
              "data": {
                "upcoming-match": {
                  "matchId": "u1",
                  "sportId": 1,
                  "matchTime": 1795365000,
                  "matchStatus": 1,
                  "homeTeamName": "Liverpool",
                  "awayTeamName": "Man Utd",
                  "competitionName": "Ngoại hạng Anh",
                  "homeTeamLogo": "https://img.cltvap.cc/football/team/liv.png",
                  "anchorAppointmentVoList": []
                },
                "hull-vs-brighton-live": {
                  "matchId": "9gklzi1edldhm7x",
                  "sportId": 1,
                  "matchTime": 1795356000,
                  "matchStatus": 2,
                  "homeScore": [1, 0],
                  "awayScore": [0, 0],
                  "homeTeamName": "CLB Hull City",
                  "awayTeamName": "Brighton",
                  "competitionName": "Giải bóng đá Ngoại hạng Anh",
                  "homeTeamLogo": "https://img.cltvap.cc/football/team/hull.png!w80",
                  "videoUrl": "https://live4.hztx2020.com/live/official.m3u8?auth_key=abc",
                  "anchorAppointmentVoList": [
                    {
                      "nickName": "BLV REVIVE",
                      "playStreamAddress": "https://live05.wecile.app/live/33982309.flv",
                      "playStreamAddress2": "https://live05.wecile.app/live/33982309.m3u8",
                      "servers": ["https://live05.yefpwmc.app/live/33982309_tsc.m3u8"]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val matches = cl.parseMatchesList(listJson, "colatv111.live")
        assertEquals(2, matches.size)
        // LIVE match sorted first
        assertEquals("CLB Hull City vs Brighton", matches[0].title)
        assertTrue(matches[0].isLive)
        assertEquals("1-0", matches[0].scoreText)
        // 3 HLS streams (BLV primary + BLV CDN 2 + Official HD) followed by 1 FLV stream
        assertEquals(4, matches[0].streams.size)
        assertTrue(matches[0].streams[0].isHls)
        assertTrue(matches[0].streams[0].name.contains("BLV REVIVE"))
        assertTrue(matches[0].streams[1].url.contains("yefpwmc.app"))
        assertTrue(matches[0].streams[2].url.contains("hztx2020.com"))
        assertFalse(matches[0].streams[3].isHls)
        assertEquals("https://colatv111.live/", matches[0].streams[0].headers["Referer"])

        val detailJson = """
            {
              "success": true,
              "match": {
                "video_url": "https://live4.hztx2020.com/live/fresh.m3u8?auth_key=new",
                "anchorAppointmentVoList": [
                  {
                    "nickName": "BLV REVIVE",
                    "playStreamAddress": "https://live05.wecile.app/live/33982309.flv",
                    "playStreamAddress2": "https://live05.wecile.app/live/33982309.m3u8",
                    "servers": []
                  }
                ]
              }
            }
        """.trimIndent()
        val detailStreams = cl.parseMatchDetailStreams(detailJson, "colatv111.live")
        assertEquals(3, detailStreams.size)
        assertTrue(detailStreams.any { it.url == "https://live4.hztx2020.com/live/fresh.m3u8?auth_key=new" })
    }

    @Test
    fun khoPhimParsesPublisherApiAndPublicSsrHtml() {
        val kp = KhoPhimProvider(
            apiBaseUrlProvider = { "https://kp.streamhub.solutions/publisher" },
            apiKeyProvider = { "" },
            webBaseUrlProvider = { "https://khophim.top" }
        )

        // 1. Official Publisher API: /movies + /movies/{slug} + /movies/{id}/episodes
        val pubMoviesJson = """
            {
              "status": "success",
              "page": 1,
              "page_size": 24,
              "total": 10432,
              "items": [
                {
                  "item_type": "movie",
                  "id": 982,
                  "slug": "mua-he-nam-ay-2026",
                  "title": "Mùa Hè Năm Ấy",
                  "poster_url": "https://image.tmdb.org/t/p/original/abc.jpg",
                  "thumb_url": "https://image.tmdb.org/t/p/original/bg.jpg",
                  "year": 2026,
                  "quality": "FHD",
                  "episode_total": 40,
                  "latest_episode": "Tập 12"
                }
              ]
            }
        """.trimIndent()
        val paged = kp.parsePublisherPagedMovies(pubMoviesJson, 1)
        assertEquals(1, paged.items.size)
        assertTrue(paged.hasMore)
        assertEquals(SourceId.KHOPHIM, paged.items.first().source)
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", paged.items.first().posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/bg.jpg", paged.items.first().backdropUrl)

        val pubEpisodesJson = """
            [
              {
                "id": 14551,
                "episode_label": "Tập 01",
                "slug": "tap-1",
                "order_index": 1,
                "sources": [
                  {
                    "video_source_id": 16692,
                    "language_name": "Vietsub",
                    "embed_url": "https://kp.streamhub.solutions/embed/8/16692?ts=1790692943&sig=9f1c2a"
                  },
                  {
                    "video_source_id": 16693,
                    "language_name": "Thuyết Minh",
                    "embed_url": "https://kp.streamhub.solutions/embed/8/16693?ts=1790692943&sig=8e2b1d"
                  }
                ]
              }
            ]
        """.trimIndent()
        val servers = kp.parsePublisherEpisodes(pubEpisodesJson)
        assertEquals(2, servers.size)
        assertEquals("KhoPhim (Vietsub)", servers[0].serverName)
        assertEquals("KhoPhim (Thuyết Minh)", servers[1].serverName)
        assertEquals("https://kp.streamhub.solutions/embed/8/16692?ts=1790692943&sig=9f1c2a", servers[0].episodes.first().embedUrl)

        // 2. Public SSR HTML mode (/phim-moi card + /phim/{slug} RSC episodes)
        val publicListHtml = """
            <ul>
              <li><a class="flex gap-3" href="/phim/phon-hoa-lui-tan-phan-2">
                <span><img src="https://image.tmdb.org/t/p/original/g3cAPl.jpg"/></span>
                <span>
                  <span class="font-semibold text-white" title="Phồn Hoa Lụi Tàn (Phần 2)">Phồn Hoa Lụi Tàn (Phần 2)</span>
                  <span class="text-white/45" title="The Glory Fades (Season 2)">The Glory Fades (Season 2)</span>
                  <span>Phim Bộ</span><span>2026</span>
                  <span class="truncate">Thêm tập 12</span>
                  <span>FHD</span>
                </span>
              </a></li>
            </ul>
        """.trimIndent()
        val listItems = kp.parsePublicListHtml(publicListHtml)
        assertEquals(1, listItems.size)
        assertEquals("phon-hoa-lui-tan-phan-2", listItems.first().id)
        assertEquals("Phồn Hoa Lụi Tàn (Phần 2)", listItems.first().title)
        assertEquals("The Glory Fades (Season 2)", listItems.first().originTitle)
        assertEquals("2026", listItems.first().year)
        assertEquals("FHD", listItems.first().quality)
        assertEquals(MediaType.SERIES, listItems.first().mediaType)

        val publicDetailHtml = """
            <meta name="description" content="Mô tả &quot;Phồn Hoa&quot; hấp dẫn" />
            <h1>Phồn Hoa Lụi Tàn (Phần 2)</h1>
            <img src="https://image.tmdb.org/t/p/original/backdrop1.jpg" />
            <img src="https://image.tmdb.org/t/p/original/poster1.jpg" />
            <div>Tên gốc</div><div>The Glory Fades (Season 2)</div>
            <div>Năm</div><div>2026</div>
            <div>Chất lượng</div><div>FHD</div>
            <div>Số tập</div><div>26</div>
            <script>self.__next_f.push([1,"\"episodes\":[{\"id\":\"263417\",\"label\":\"Tập 01\",\"shortLabel\":\"01\",\"embedUrl\":\"https://a.khophim.top/embed/8/293787?ts=1795396354\\u0026sig=5d5d\"},{\"id\":\"263418\",\"label\":\"Tập 02\",\"shortLabel\":\"02\",\"embedUrl\":\"https://a.khophim.top/embed/8/293788?ts=1795396354\\u0026sig=5d5d\"}]"])</script>
        """.trimIndent()
        val detail = kp.parsePublicDetailHtml(publicDetailHtml, "phon-hoa-lui-tan-phan-2")
        assertEquals("Phồn Hoa Lụi Tàn (Phần 2)", detail.item.title)
        assertEquals("2026", detail.item.year)
        assertEquals(1, detail.servers.size)
        assertEquals(2, detail.servers.first().episodes.size)
        assertEquals(
            "https://a.khophim.top/embed/8/293787?ts=1795396354&sig=5d5d",
            detail.servers.first().episodes.first().embedUrl
        )
    }
}
