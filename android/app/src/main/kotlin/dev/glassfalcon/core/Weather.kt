// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Windy.com Point Forecast, surface wind + temperature at the pilot's own GPS position, for the
 * HUD's ambient-temp and forecast-wind symbology. Wind is a real flight-safety input on a Mavic 2
 * (rated ~10.7 m/s / 24 mph wind resistance), so this reads as a first-class instrument, not decor.
 *
 * The API key is a SECRET and is NEVER stored in source or baked into a distributed APK, it lives
 * only in this device's private SharedPreferences (same model as RcButtons/Consent), entered in
 * Settings by each user with their own free Windy key. Public builds ship with no key and simply
 * show no weather until one is entered.
 */
object WindyWeather {
    private const val PREFS = "glassfalcon_weather"
    private const val KEY_POINT = "windy_point_key"
    private const val KEY_MAP = "windy_map_key"
    private const val KEY_WEBCAM = "windy_webcam_key"
    private const val POINT_URL = "https://api.windy.com/api/point-forecast/v2"

    // Windy keys are secrets → EncryptedSharedPreferences (Keystore-backed), with a one-time
    // migration off the old plaintext store.
    private fun prefs(ctx: Context) =
        SecureStore.encryptedPrefs(ctx, "glassfalcon_weather_secure").also {
            SecureStore.migratePlaintextPrefs(ctx, PREFS, it)
        }

    fun pointKey(ctx: Context): String = prefs(ctx).getString(KEY_POINT, "") ?: ""
    fun mapKey(ctx: Context): String = prefs(ctx).getString(KEY_MAP, "") ?: ""
    fun webcamKey(ctx: Context): String = prefs(ctx).getString(KEY_WEBCAM, "") ?: ""

    fun setKeys(ctx: Context, point: String?, map: String?, webcam: String?) {
        prefs(ctx).edit().apply {
            point?.let { putString(KEY_POINT, it.trim()) }
            map?.let { putString(KEY_MAP, it.trim()) }
            webcam?.let { putString(KEY_WEBCAM, it.trim()) }
            apply()
        }
    }

    /**
     * Blocking POST, call off the main thread. Returns null on any failure (no key, no network,
     * bad response) so the HUD just shows nothing rather than a stale or fake reading.
     *
     * Response arrays are time series aligned to `ts` (epoch ms). Index 0 is the earliest forecast
     * step, effectively "now." Surface wind arrives as u (eastward) / v (northward) m/s components;
     * temp-surface is Kelvin.
     */
    fun fetchPoint(lat: Double, lon: Double, key: String): WeatherNow? {
        if (key.isBlank()) return null
        return try {
            val body = JSONObject()
                .put("lat", lat).put("lon", lon)
                .put("model", "gfs")
                .put("parameters", org.json.JSONArray(listOf("wind", "temp")))
                .put("levels", org.json.JSONArray(listOf("surface")))
                .put("key", key)
                .toString()
            val conn = (URL(POINT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val u = json.optJSONArray("wind_u-surface") ?: return null
            val v = json.optJSONArray("wind_v-surface") ?: return null
            val t = json.optJSONArray("temp-surface") ?: return null
            if (u.length() == 0) return null
            val ue = u.getDouble(0); val ve = v.getDouble(0)
            val speed = kotlin.math.hypot(ue, ve)
            // Meteorological "wind FROM" bearing, 0=N, 90=E.
            var fromDeg = Math.toDegrees(kotlin.math.atan2(-ue, -ve))
            if (fromDeg < 0) fromDeg += 360.0
            val tempC = t.getDouble(0) - 273.15
            WeatherNow(tempC.toFloat(), speed.toFloat(), fromDeg.toFloat(), System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
    }
}

/** One resolved surface observation for the HUD. windFromDeg is the direction the wind blows FROM.
 *  windGustMps is the peak gust when the source provides one (Open-Meteo does; Windy's GFS point
 *  forecast doesn't), null means "no gust datum," NOT "no gusts." Gust is the number that actually
 *  flips a Mavic, so the HUD colors off whichever of sustained/gust is worse. */
data class WeatherNow(
    val tempC: Float,
    val windMps: Float,
    val windFromDeg: Float,
    val fetchedMs: Long,
    val windGustMps: Float? = null,
    // Extra conditions for a fully weather-aware copilot (Open-Meteo current fields).
    val precipMm: Float? = null,       // precipitation in the current hour, mm
    val cloudPct: Int? = null,         // total cloud cover %
    val visibilityM: Float? = null,    // horizontal visibility, metres
    val humidityPct: Int? = null,      // relative humidity %
    val weatherCode: Int? = null,      // WMO weather code
    val isDay: Boolean? = null,
) {
    /** Human-readable current condition from the WMO weather code (WW). */
    val conditionText: String get() = when (weatherCode) {
        0 -> "clear"; 1 -> "mainly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61, 63, 65 -> "rain"; 66, 67 -> "freezing rain"; 71, 73, 75 -> "snow"; 77 -> "snow grains"
        80, 81, 82 -> "rain showers"; 85, 86 -> "snow showers"
        95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "unknown"
    }
    /** True when conditions are hazardous for a small drone (precip, storms, poor visibility). */
    val isHazardous: Boolean get() =
        (weatherCode ?: 0) in setOf(45, 48, 56, 57, 66, 67, 95, 96, 99) ||
        (precipMm ?: 0f) > 0.2f || (visibilityM ?: 99999f) < 1000f
}

/**
 * Open-Meteo current conditions, a keyless, no-registration source of REAL current surface wind
 * (not a multi-hour forecast step like Windy's GFS point forecast) plus wind GUSTS, refreshed by
 * Open-Meteo roughly every 15 minutes. This is the fast, flight-safety wind read for fast-changing
 * conditions: no API key, no per-user setup, and a generous free allowance (~10k calls/day) so it
 * can be polled every minute in flight without any rate-limit worry. Used as the PRIMARY wind/temp
 * source; Windy (if the pilot set a key) stays as a fallback.
 *
 * Polling faster than ~15 min returns the same values (that's Open-Meteo's own update cadence), so
 * there's no accuracy gain past a ~60s in-flight cadence, just courtesy headroom under the quota.
 */
object OpenMeteo {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"

    /** Blocking GET, call off the main thread. Returns null on any failure so the HUD shows
     *  nothing rather than a stale/fake reading. */
    fun fetchCurrent(lat: Double, lon: Double): WeatherNow? {
        return try {
            val url = "$BASE?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m," +
                "precipitation,cloud_cover,visibility,relative_humidity_2m,weather_code,is_day" +
                "&wind_speed_unit=ms&temperature_unit=celsius"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000; readTimeout = 8000
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            conn.disconnect()
            val cur = json.optJSONObject("current") ?: return null
            val speed = cur.optDouble("wind_speed_10m", Double.NaN)
            val fromDeg = cur.optDouble("wind_direction_10m", Double.NaN)
            val tempC = cur.optDouble("temperature_2m", Double.NaN)
            if (speed.isNaN() || fromDeg.isNaN()) return null
            val gust = cur.optDouble("wind_gusts_10m", Double.NaN)
            val precip = cur.optDouble("precipitation", Double.NaN)
            val cloud = cur.optInt("cloud_cover", -1)
            val vis = cur.optDouble("visibility", Double.NaN)
            val humidity = cur.optInt("relative_humidity_2m", -1)
            val code = cur.optInt("weather_code", -1)
            val day = cur.optInt("is_day", -1)
            WeatherNow(
                tempC = if (tempC.isNaN()) 0f else tempC.toFloat(),
                windMps = speed.toFloat(),
                windFromDeg = ((fromDeg % 360.0 + 360.0) % 360.0).toFloat(),
                fetchedMs = System.currentTimeMillis(),
                windGustMps = if (gust.isNaN()) null else gust.toFloat(),
                precipMm = if (precip.isNaN()) null else precip.toFloat(),
                cloudPct = if (cloud < 0) null else cloud,
                visibilityM = if (vis.isNaN()) null else vis.toFloat(),
                humidityPct = if (humidity < 0) null else humidity,
                weatherCode = if (code < 0) null else code,
                isDay = if (day < 0) null else day == 1,
            )
        } catch (e: Exception) {
            null
        }
    }
}
