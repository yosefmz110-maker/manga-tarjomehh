package com.mangastudio.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class MangaStudioApp : Application() {

    companion object {
        private const val TAG = "MangaStudioApp"
        @Volatile
        var isOpenCvReady: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isOpenCvReady = try {
            OpenCVLoader.initLocal().also { loaded ->
                if (loaded) {
                    Log.i(TAG, "OpenCV initialized successfully.")
                } else {
                    Log.w(TAG, "OpenCV failed to initialize; fallback cleaner will be used.")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OpenCV initialization error", t)
            false
        }
    }
}
