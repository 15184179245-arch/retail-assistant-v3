package com.retaildemo.v2.di

import android.content.Context
import com.retaildemo.v2.data.local.AppDatabase
import com.retaildemo.v2.data.remote.AppConfig
import com.retaildemo.v2.data.remote.AuthInterceptor
import com.retaildemo.v2.data.remote.RetryInterceptor
import com.retaildemo.v2.data.remote.retaildemoApi
import com.retaildemo.v2.data.remote.ServerUrlInterceptor
import com.retaildemo.v2.data.remote.UnauthorizedCleanupInterceptor
import com.retaildemo.v2.data.remote.buildDynamicTls
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppConfig(@ApplicationContext context: Context): AppConfig = AppConfig(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.build(context)

    @Provides
    fun provideProductDao(db: AppDatabase) = db.productDao()

    @Provides
    fun provideBillDao(db: AppDatabase) = db.billDao()

    @Provides
    fun provideCustomerDao(db: AppDatabase) = db.customerDao()

    @Provides
    fun provideSearchHistoryDao(db: AppDatabase) = db.searchHistoryDao()

    @Provides
    fun provideDraftDao(db: AppDatabase) = db.draftDao()

    @Provides
    fun provideProductViewDao(db: AppDatabase) = db.productViewDao()

    @Provides
    fun provideTempProductDao(db: AppDatabase) = db.tempProductDao()

    @Provides
    fun provideDataMarkDao(db: AppDatabase) = db.dataMarkDao()

    @Provides
    fun provideSupplementDao(db: AppDatabase) = db.supplementDao()

    @Provides
    fun providePrintJobDao(db: AppDatabase) = db.printJobDao()

    /** I20 三级货架（本地 Room，零网络）。 */
    @Provides
    fun provideShelfDao(db: AppDatabase) = db.shelfDao()

    /** H2 运行日志（本地留存 + 批量上报网关）。 */
    @Provides
    fun provideClientLogDao(db: AppDatabase) = db.clientLogDao()

    @Provides
    @Singleton
    fun provideOkHttp(config: AppConfig, appLogger: com.retaildemo.v2.data.repo.AppLogger): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // I17：agent_run 走 hermes -z 全链 30-60s，读超时须容纳（chat 同理长回答）
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // H2 放最外层：重试/重写之后网络层的最终成败才落一条日志，不逐次刷屏
            .addInterceptor(com.retaildemo.v2.data.remote.HttpLogInterceptor(appLogger))
            .addInterceptor(ServerUrlInterceptor(config))
            .addInterceptor(AuthInterceptor(config))
            .addInterceptor(UnauthorizedCleanupInterceptor(config))
            .addInterceptor(RetryInterceptor())
        // 信任策略动态化：trustAllTls 开关实时生效，无需重建 client
        val (trustManager, sslContext) = buildDynamicTls(config)
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { hostname, session ->
            if (config.trustAllTls) {
                true
            } else {
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }
        // B14 搜索限秒（2026-09-27 老板报「搜索转半天圈」实锤）：搜索/回执轮询是快进快出的
        // 短请求，却继承了全局 180s 读超时——隧道抖一下就干等 3 分钟才降级本地（店机 APP
        // 日志 109 条 Canceled 佐证）。这两类请求给 **8s 全程预算**（call.timeout 是总闸，
        // 含重试），超时即失败 → 搜索走本地兜底秒出、回执轮询静默跳过。AI 长回答不受影响。
        builder.addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.endsWith("/search") || path.endsWith("/settlement-receipts")) {
                // OkHttp 4.x：Call.timeout() 返回 Timeout 对象，再调它的 .timeout(duration, unit) 设预算
                chain.call().timeout().timeout(8, TimeUnit.SECONDS)
            }
            chain.proceed(chain.request())
        }
        builder.addInterceptor(
            HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
        )
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(config: AppConfig, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (config.serverUrl.endsWith("/")) config.serverUrl else config.serverUrl + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): retaildemoApi = retrofit.create(retaildemoApi::class.java)
}
