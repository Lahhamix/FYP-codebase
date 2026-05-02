package com.example.ble_viewer

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate

class SolemateApplication : Application() {

    companion object {
        fun markSessionUnlocked() {
            AppLockManager.markSessionUnlocked()
        }

        fun setAuthInProgress(value: Boolean) {
            AppLockManager.setAuthInProgress(value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Keep a consistent look regardless of system dark mode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        AutoShareScheduler.reschedule(this)
        AppLockManager.init(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Lock all activities to portrait orientation.
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                TextSizeScaleManager.applyTo(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                TextSizeScaleManager.applyTo(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}