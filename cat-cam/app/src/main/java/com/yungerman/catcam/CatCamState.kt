package com.yungerman.catcam

import kotlinx.coroutines.flow.MutableStateFlow

/** In-process state shared between the camera service, the send worker and the screen. */
object CatCamState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("המצלמה כבויה")
    val lastPhotoPath = MutableStateFlow<String?>(null)
    val sendStatus = MutableStateFlow("עדיין אין זיהויים")
}
