package com.ztune.libretune.di

import android.content.Context
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.DataLogManager
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuDefinitionRepository
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.git.TuneVersionControl
import com.ztune.libretune.core.i18n.UnitPreferences
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
     * Provide [RealtimeDecoder] unscoped.
     *
     * The decoder depends on an [EcuDefinition] which changes per-connection,
     * so callers should create a fresh instance (or use a factory / assisted
     * inject) when the definition changes.
     */
    @Provides
    fun provideRealtimeDecoder(definition: EcuDefinition): RealtimeDecoder {
        return RealtimeDecoder(definition)
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
    fun provideDataLogManager(): DataLogManager {
        return DataLogManager()
    }

    @Provides
    @Singleton
    fun provideAppSettings(@ApplicationContext context: Context): AppSettings {
        return AppSettings(context)
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
    fun provideUnitPreferences(@ApplicationContext context: Context): UnitPreferences {
        return UnitPreferences.getInstance(context)
    }
}
