package ru.queuejw.lumetro.components.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WeatherFetcher {
    private val APIKEY_LIST = arrayOf(
        "71a91885a2524ca8801c67bc9b3d354c",
        "e35f96422e814236a133a38fc8f25d7c"
    )

    data class WeatherData(
        val temp: String = "",
        val text: String = "",
        val icon: String = "",
        val feelsLike: String = "",
        val windDir: String = "",
        val windScale: String = "",
        val humidity: String = "",
        val city: String = "",
        val forecast: List<ForecastDay> = emptyList()
    )

    data class ForecastDay(
        val date: String = "",
        val tempMax: String = "",
        val tempMin: String = "",
        val icon: String = "",
        val text: String = ""
    )

    private fun readStream(conn: HttpURLConnection): String {
        return if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }
        else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /**
     * 根据城市名称获取 location ID
     */
    private suspend fun getLocationId(city: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = APIKEY_LIST[(System.currentTimeMillis() / 1000 % APIKEY_LIST.size).toInt()]
                val encodedCity = URLEncoder.encode(city, "UTF-8")
                val url = "https://geoapi.qweather.com/v2/city/lookup?location=$encodedCity&key=$apiKey"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val json = readStream(conn)
                val obj = org.json.JSONObject(json)
                val location = obj.optJSONArray("location")
                if (location != null && location.length() > 0) {
                    val first = location.optJSONObject(0)
                    first?.optString("id") ?: null
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("Weather", "getLocationId error", e)
                null
            }
        }
    }

    suspend fun fetchWeather(city: String = "北京"): WeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = APIKEY_LIST[(System.currentTimeMillis() / 1000 % APIKEY_LIST.size).toInt()]
                
                val locationId = getLocationId(city)
                if (locationId == null) {
                    Log.e("Weather", "Failed to get location ID for city: $city")
                    return@withContext null
                }

                val nowLink = "https://devapi.qweather.com/v7/weather/now?location=$locationId&key=$apiKey"
                val nowConn = URL(nowLink).openConnection() as HttpURLConnection
                nowConn.connectTimeout = 5000; nowConn.readTimeout = 5000
                val nowJson = readStream(nowConn)
                val nowObj = org.json.JSONObject(nowJson).optJSONObject("now")

                val forecastLink = "https://devapi.qweather.com/v7/weather/3d?location=$locationId&key=$apiKey"
                val fcConn = URL(forecastLink).openConnection() as HttpURLConnection
                fcConn.connectTimeout = 5000; fcConn.readTimeout = 5000
                val fcJson = readStream(fcConn)
                val fcArr = org.json.JSONObject(fcJson).optJSONArray("daily")
                val forecast = mutableListOf<ForecastDay>()
                if (fcArr != null) {
                    for (i in 0 until fcArr.length()) {
                        val d = fcArr.optJSONObject(i)
                        forecast.add(ForecastDay(
                            date = d?.optString("fxDate") ?: "",
                            tempMax = d?.optString("tempMax") ?: "",
                            tempMin = d?.optString("tempMin") ?: "",
                            icon = d?.optString("iconDay") ?: "",
                            text = d?.optString("textDay") ?: ""
                        ))
                    }
                }

                WeatherData(
                    temp = nowObj?.optString("temp") ?: "",
                    text = nowObj?.optString("text") ?: "",
                    icon = nowObj?.optString("icon") ?: "",
                    feelsLike = nowObj?.optString("feelsLike") ?: "",
                    windDir = nowObj?.optString("windDir") ?: "",
                    windScale = nowObj?.optString("windScale") ?: "",
                    humidity = nowObj?.optString("humidity") ?: "",
                    city = city,
                    forecast = forecast
                )
            } catch (e: Exception) {
                Log.e("Weather", "fetch error", e)
                null
            }
        }
    }
}