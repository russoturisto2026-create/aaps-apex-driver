package app.aaps.pump.atc3.di

import app.aaps.pump.atc3.Atc3Fragment
import app.aaps.pump.atc3.ui.Atc3ScanActivity
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class Atc3Module {

    @ContributesAndroidInjector abstract fun contributesAtc3Fragment(): Atc3Fragment
    @ContributesAndroidInjector abstract fun contributesAtc3ScanActivity(): Atc3ScanActivity
}
