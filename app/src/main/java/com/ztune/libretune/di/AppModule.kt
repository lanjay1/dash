package com.ztune.libretune.di

import android.content.Context
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.DataLogManager
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuDefinitionRepository
import com.ztune.libretune.core.TuneManager
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
        settings: AppSettings
    ): EcuConnectionManager {
        return EcuConnectionManager(scope, settings)
    }

    @Provides
    @Singleton
    fun provideTuneManager(): TuneManager {
        return TuneManager()
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
}
