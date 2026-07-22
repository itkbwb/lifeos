package com.lifeos.app.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiFactory {
    fun create(baseUrl: String, accessClientId: String = "", accessClientSecret: String = ""): LifeOsApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        if (accessClientId.isNotBlank() && accessClientSecret.isNotBlank()) {
            clientBuilder.addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("CF-Access-Client-Id", accessClientId)
                    .addHeader("CF-Access-Client-Secret", accessClientSecret)
                    .build()
                chain.proceed(request)
            }
        }

        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LifeOsApi::class.java)
    }
}
