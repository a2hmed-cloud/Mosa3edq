package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class WeatherInfo(
    val cityName: String,
    val temperature: Double,
    val weatherCode: Int,
    val windSpeed: Double,
    val descriptionArabic: String
)

data class GeocodingResponse(val results: List<GeocodingResult>?)
data class GeocodingResult(val name: String, val latitude: Double, val longitude: Double, val country: String?)

data class OpenMeteoResponse(val current_weather: CurrentWeather?)
data class CurrentWeather(val temperature: Double, val windspeed: Double, val weathercode: Int)

interface GeocodingApi {
    @GET("v1/search")
    suspend fun searchCity(
        @Query("name") name: String,
        @Query("count") count: Int = 1,
        @Query("language") language: String = "ar"
    ): GeocodingResponse
}

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current_weather") currentWeather: Boolean = true
    ): OpenMeteoResponse
}

class WeatherRepository {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val geocodingRetrofit = Retrofit.Builder()
        .baseUrl("https://geocoding-api.open-meteo.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    private val weatherRetrofit = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    private val geocodingService = geocodingRetrofit.create(GeocodingApi::class.java)
    private val weatherService = weatherRetrofit.create(OpenMeteoApi::class.java)

    suspend fun getWeatherForCity(city: String): Result<WeatherInfo> = withContext(Dispatchers.IO) {
        try {
            val geoResult = geocodingService.searchCity(city)
            val firstResult = geoResult.results?.firstOrNull()
                ?: return@withContext Result.failure(Exception("لم يتم العثور على المدينة: $city"))

            val weatherData = weatherService.getWeather(firstResult.latitude, firstResult.longitude)
            val current = weatherData.current_weather
                ?: return@withContext Result.failure(Exception("عفواً، تعذر جلب بيانات الطقس حالياً"))

            val arDesc = getWeatherCodeArabicDescription(current.weathercode)
            val info = WeatherInfo(
                cityName = firstResult.name,
                temperature = current.temperature,
                weatherCode = current.weathercode,
                windSpeed = current.windspeed,
                descriptionArabic = arDesc
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getWeatherCodeArabicDescription(code: Int): String {
        return when (code) {
            0 -> "مشمس وصافٍ"
            1, 2, 3 -> "غائم جزئياً"
            45, 48 -> "ضبابي"
            51, 53, 55 -> "رذاذ خفيف"
            61, 63, 65 -> "أمطار هاطلة"
            71, 73, 75 -> "تساقط ثلوج"
            80, 81, 82 -> "زخات مطرية قوية"
            95, 96, 99 -> "عاصفة رعدية"
            else -> "معتدل"
        }
    }
}
