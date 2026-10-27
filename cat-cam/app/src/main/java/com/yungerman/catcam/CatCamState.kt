package com.yungerman.catcam

import android.graphics.RectF
import android.net.Uri
import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-process state shared between the camera service and the screen. */
object CatCamState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("המצלמה כבויה")

    /** Best cat score (0..1) in the latest analyzed frame. */
    val catScore = MutableStateFlow(0f)

    /** The latest frame alone passes the threshold (not yet confirmed). */
    val catHit = MutableStateFlow(false)

    /** Confirmed over several frames: the exact condition that triggers photos. */
    val catConfirmed = MutableStateFlow(false)

    /** Where the cat is, for drawing on the live view; null when unknown or not drawable. */
    val catBox = MutableStateFlow<CatBox?>(null)

    /** Wall-clock time of the next automatic capture while the cat stays in view (0 = none). */
    val nextCaptureAt = MutableStateFlow(0L)

    val detectionsToday = MutableStateFlow(0)
    val lastDetectionTime = MutableStateFlow<String?>(null)
    val lastPhotoUri = MutableStateFlow<Uri?>(null)

    /** Emits after every saved photo (drives the shutter flash on screen). */
    val photoSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Set by the screen while it is visible; the service attaches a live preview to it. */
    val previewSurface = MutableStateFlow<Preview.SurfaceProvider?>(null)

    /** [rect] is normalized (0..1) to an upright frame whose width/height ratio is [frameAspect]. */
    data class CatBox(val rect: RectF, val frameAspect: Float, val score: Float)
}
