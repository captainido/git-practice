package com.yungerman.catcam

import android.net.Uri
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process state shared between the camera service and the screen. */
object CatCamState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("המצלמה כבויה")

    /** Latest ML Kit "Cat" confidence (0..1) from the live analysis. */
    val catConfidence = MutableStateFlow(0f)

    /** The same decision the service uses to capture, so the screen never disagrees with it. */
    val catDetected = MutableStateFlow(false)

    /** Wall-clock time of the next automatic capture while the cat stays in view (0 = none). */
    val nextCaptureAt = MutableStateFlow(0L)

    val detectionsToday = MutableStateFlow(0)
    val lastDetectionTime = MutableStateFlow<String?>(null)
    val lastPhotoUri = MutableStateFlow<Uri?>(null)

    /** Emits after every saved photo (drives the shutter flash on screen). */
    val photoSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Set by the screen while it is visible; the service attaches a live preview to it. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)
}
