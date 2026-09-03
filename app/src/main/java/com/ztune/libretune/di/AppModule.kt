package com.ztune.libretune.di

import android.content.Context
import com.ztune.libretune.core.autotune.AutoTuneController
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.DataLogManager
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuDefinitionRepository
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.git.TuneVersionControl
import com.ztune.libretune.core.i18n.UnitPreferencesStore
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.realtime.RealtimeDecoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Qualifier for the application-lifetime [CoroutineScope].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt DI module for LibreTune.
 *
 * Installs into [SingletonComponent] so all provided objects are application-scoped.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ------------------------------------------------------------------
    //  Application scope
    // ------------------------------------------------------------------

    /**
     * A [CoroutineScope] bound to the application lifetime.
     * Used by long-lived managers that outlive any single Activity.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------
    //  Realtime
    // ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideRealtimeChannelStore(): RealtimeChannelStore {
        return RealtimeChannelStore()
    }

    /**
     * Provide a singleton [RealtimeDecoder] bound to a default (empty) [EcuDefinition].
     *
     * The decoder is intrinsically per-connection (it depends on the active
     * [EcuDefinition]); this provider only satisfies compile-time DI graph
     * resolution for singleton consumers like [TuneManager].
     *
     * IMPORTANT: We deliberately inline the `EcuDefinition.default()` call
     * here instead of exposing it as a separate `@Provides` method.
     * Exposing `EcuDefinition` as a Hilt binding caused a
     * `Dagger/MissingBinding` error in `:app:hiltJavaCompileDebug` because
     * Hilt's component scanner failed to pick up the standalone provider
     * (likely a KSP caching quirk). Inlining the construction side-steps
     * that issue entirely.
     *
     * Callers that need a decoder for the *current* ECU connection should
     * construct one explicitly via `RealtimeDecoder(connectionManager
     * .activeDefinition!!)` rather than injecting this default instance.
     */
    @Provides
    @Singleton
    fun provideRealtimeDecoder(): RealtimeDecoder {
        return RealtimeDecoder(EcuDefinition.default())
    }

    // ------------------------------------------------------------------
    //  Core managers
    // ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideEcuDefinitionRepository(@ApplicationContext context: Context): EcuDefinitionRepository {
        return EcuDefinitionRepository(context)
    }

    @Provides
    @Singleton
    fun provideEcuConnectionManager(
        @ApplicationScope scope: CoroutineScope,
        settings: AppSettings,
        channelStore: RealtimeChannelStore,
        dataLogManager: DataLogManager
    ): EcuConnectionManager {
        return EcuConnectionManager(scope, settings, channelStore, dataLogManager)
    }

    @Provides
    @Singleton
    fun provideTuneManager(
        @ApplicationContext context: Context,
        decoder: RealtimeDecoder
    ): TuneManager {
        return TuneManager(context, decoder)
    }

    @Provides
    @Singleton
    fun provideDataLogManager(@ApplicationContext context: Context): DataLogManager {
        return DataLogManager(context)
    }

    @Provides
    @Singleton
    fun provideAppSettings(@ApplicationContext context: Context): AppSettings {
        return AppSettings(context)
    }

    // ------------------------------------------------------------------
    //  AutoTune
    // ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideAutoTuneController(): AutoTuneController {
        return AutoTuneController()
    }

    // ------------------------------------------------------------------
    //  Git version control
    // ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideTuneVersionControl(@ApplicationContext context: Context): TuneVersionControl {
        return TuneVersionControl(context)
    }

    // ------------------------------------------------------------------
    //  i18n / unit preferences
    // ------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideUnitPreferences(@ApplicationContext context: Context): UnitPreferencesStore {
        return UnitPreferencesStore.getInstance(context)
    }
}
