package app.aaps.pump.apex.di

import android.content.Context
import app.aaps.pump.apex.store.ApexDao
import app.aaps.pump.apex.store.ApexDatabase
import app.aaps.pump.apex.store.ApexRoomStore
import app.aaps.pump.apex.store.ApexStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** The driver's database and the store on it, owned by the app graph. */
@ContributesTo(AppScope::class)
@BindingContainer
object ApexStoreBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(context: Context): ApexDatabase = ApexDatabase.build(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideDao(database: ApexDatabase): ApexDao = database.dao()

    @Provides
    fun provideStore(store: ApexRoomStore): ApexStore = store
}
