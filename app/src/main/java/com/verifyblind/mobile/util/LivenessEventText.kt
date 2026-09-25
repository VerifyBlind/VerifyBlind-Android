package com.verifyblind.mobile.util

import com.verifyblind.mobile.R

/** Hareketin komutu — kart ekleme ve doğrulama ekranında aynı metin. */
val EventCollector.Event.commandRes: Int
    get() = when (this) {
        EventCollector.Event.BLINK -> R.string.liveness_face_blink
        EventCollector.Event.SMILE -> R.string.liveness_face_smile
        EventCollector.Event.MOUTH_OPEN -> R.string.liveness_face_mouth_open
        EventCollector.Event.DOUBLE_BLINK -> R.string.liveness_face_double_blink
    }

/** Hareketin NASIL yapılacağı — komutun altında, komutla aynı anda. */
val EventCollector.Event.hintRes: Int
    get() = when (this) {
        EventCollector.Event.BLINK -> R.string.liveness_ev_hint_blink
        EventCollector.Event.SMILE -> R.string.liveness_ev_hint_smile
        EventCollector.Event.MOUTH_OPEN -> R.string.liveness_ev_hint_mouth_open
        EventCollector.Event.DOUBLE_BLINK -> R.string.liveness_ev_hint_double_blink
    }
