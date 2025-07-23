package com.gocavgo.entries.di

import com.gocavgo.entries.service.LocationService
import com.gocavgo.entries.service.RouteService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLocationService(): LocationService = LocationService()

    @Provides
    @Singleton
    fun provideRouteService(): RouteService = RouteService()
}