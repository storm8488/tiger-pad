package com.damou.tigertouch.cam

import android.util.Log
import com.damou.tigertouch.drivers.Addr
import com.damou.tigertouch.model.AccountPrinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/*
 * Where a printer's camera streams, as an MJPEG URL. Not in the firmware: the
 * Panda Touch has no room for video. Found once per printer, on the LAN:
 *  - Klipper: Moonraker's own list of webcams (/server/webcams/list), the
 *    first enabled one that serves MJPEG. Its URLs are relative to the web
 *    front-end (Mainsail / Fluidd on port 80), not to Moonraker;
 *  - Creality K1 / K2: mjpg-streamer on :8080, else the camera behind the
 *    Fluidd front-end on :4408;
 *  - OctoPrint: the stream URL of its webcam settings.
 */
object Cameras {
    private const val TAG = "cameras"

    /** Moonraker services that are not MJPEG (WebRTC, HLS): this viewer cannot show them. */
    private val NOT_MJPEG = listOf("webrtc", "hlsstream", "jmuxer")

    private fun client(base: OkHttpClient) = base.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** The MJPEG stream URL of [p]'s camera, or null when it has none this app can show. */
    suspend fun find(p: AccountPrinter, lanHttp: OkHttpClient): String? = withContext(Dispatchers.IO) {
        if (p.cloud || p.ip.isEmpty()) return@withContext null
        val http = client(lanHttp)
        try {
            when (p.brand) {
                "klipper" -> klipper(p, http)
                "creality" -> creality(p, http)
                "octoprint" -> octoprint(p, http)
                else -> null
            }
        } catch (e: Exception) {
            Log.i(TAG, "${p.brand}: no camera found (${e.javaClass.simpleName})")
            null
        }
    }

    private fun getJson(http: OkHttpClient, url: String, apiKey: String = ""): JSONObject? {
        val b = Request.Builder().url(url)
        if (apiKey.isNotEmpty()) b.header("X-Api-Key", apiKey)
        return http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: return null)
        }
    }

    /** A snapshot URL answers with a JPEG: the camera is there. */
    private fun answersJpeg(http: OkHttpClient, url: String): Boolean = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            r.isSuccessful && (r.header("Content-Type") ?: "").startsWith("image/")
        }
    } catch (e: Exception) {
        false
    }

    /** A URL as Moonraker or OctoPrint give it: absolute, or relative to the web front-end of [host]. */
    private fun resolve(host: String, url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> "http://$host$url"
        else -> "http://$host/$url"
    }

    private fun klipper(p: AccountPrinter, http: OkHttpClient): String? {
        val host = Addr.hostOnly(p.ip)
        val root = getJson(http, "http://${Addr.withPort(p.ip, 7125)}/server/webcams/list", p.key) ?: return null
        val cams = root.optJSONObject("result")?.optJSONArray("webcams") ?: return null
        for (i in 0 until cams.length()) {
            val c = cams.optJSONObject(i) ?: continue
            if (!c.optBoolean("enabled", true)) continue
            val service = c.optString("service")
            if (NOT_MJPEG.any { service.startsWith(it) }) continue
            val stream = c.optString("stream_url")
            if (stream.isNotEmpty()) return resolve(host, stream)
        }
        return null
    }

    private fun creality(p: AccountPrinter, http: OkHttpClient): String? {
        val host = Addr.hostOnly(p.ip)
        if (answersJpeg(http, "http://$host:8080/?action=snapshot")) return "http://$host:8080/?action=stream"
        if (answersJpeg(http, "http://$host:4408/webcam/?action=snapshot")) return "http://$host:4408/webcam/?action=stream"
        return null
    }

    private fun octoprint(p: AccountPrinter, http: OkHttpClient): String? {
        if (p.key.isEmpty()) return null
        val host = Addr.hostOnly(p.ip)
        val s = getJson(http, "http://${Addr.withPort(p.ip, 80)}/api/settings", p.key) ?: return null
        val cam = s.optJSONObject("webcam") ?: return null
        if (!cam.optBoolean("webcamEnabled", true)) return null
        val stream = cam.optString("streamUrl")
        return if (stream.isEmpty()) null else resolve(host, stream)
    }
}
