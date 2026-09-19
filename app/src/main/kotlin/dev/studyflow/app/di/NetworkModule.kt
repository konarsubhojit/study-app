package dev.studyflow.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.studyflow.app.BuildConfig
import dev.studyflow.core.datastore.EncryptedTokenStore
import dev.studyflow.core.network.ApiConfig
import dev.studyflow.core.network.KtorStudyFlowApi
import dev.studyflow.core.network.StudyFlowApi
import dev.studyflow.core.network.auth.HttpTokenRefresher
import dev.studyflow.core.network.auth.TokenRefresher
import dev.studyflow.core.network.auth.TokenStore
import dev.studyflow.core.network.http.studyFlowHttpClient
import dev.studyflow.core.network.version.ClientVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/**
 * Wires the typed API client into the application graph (issue #63).
 *
 * The base URL and the client version come from `BuildConfig`, so pointing the app at a local mock
 * backend is `-Pstudyflow.apiBaseUrl=http://10.0.2.2:8080` rather than an edit to a constant that
 * somebody eventually commits by accident.
 *
 * One engine is shared by the API client and the token refresher: they need separate *clients* —
 * the refresh call must not go through the auth plugin that triggered it — but a second connection
 * pool and dispatcher would be waste.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun apiConfig(): ApiConfig =
        ApiConfig(
            baseUrl = BuildConfig.API_BASE_URL,
            clientVersion =
                requireNotNull(ClientVersion.parseOrNull(BuildConfig.API_CLIENT_VERSION)) {
                    "API_CLIENT_VERSION '${BuildConfig.API_CLIENT_VERSION}' is not a major.minor.patch version"
                },
        )

    @Provides
    @Singleton
    fun httpClientEngine(): HttpClientEngine = OkHttp.create()

    @Provides
    @Singleton
    fun tokenStore(
        @ApplicationContext context: android.content.Context,
    ): TokenStore = EncryptedTokenStore(context)

    @Provides
    @Singleton
    fun tokenRefresher(
        engine: HttpClientEngine,
        config: ApiConfig,
    ): TokenRefresher = HttpTokenRefresher(engine, config)

    @Provides
    @Singleton
    fun httpClient(
        engine: HttpClientEngine,
        config: ApiConfig,
        tokenStore: TokenStore,
        tokenRefresher: TokenRefresher,
    ): HttpClient =
        studyFlowHttpClient(
            engine = engine,
            config = config,
            tokenStore = tokenStore,
            tokenRefresher = tokenRefresher,
        )

    @Provides
    @Singleton
    fun studyFlowApi(
        client: HttpClient,
        config: ApiConfig,
    ): StudyFlowApi = KtorStudyFlowApi(client, config)
}
