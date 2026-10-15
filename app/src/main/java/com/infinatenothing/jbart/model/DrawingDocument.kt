package com.infinatenothing.jbart.model

import android.graphics.Bitmap

class DrawingDocument(
    val objects: MutableList<DrawObject> = mutableListOf(),
    var width: Int = 0,
    var height: Int = 0,
    var backgroundColor: Int = 0xFFFFFFFF.toInt(),
    var bitmap: Bitmap? = null,
)
