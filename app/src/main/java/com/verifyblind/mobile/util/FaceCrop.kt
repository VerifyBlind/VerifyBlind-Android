package com.verifyblind.mobile.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Olay dizisi karesinin yüz çevresinden kırpılması — saf hesap, Android'e bağımlı değil (birim
 * testli).
 *
 * ## Neden kırpma
 *
 * Mesafe tabanlı ölçüm (parallaks) arka planı istiyordu, kareler tam gidiyordu. Artık yalnız yüz
 * ölçülüyor: kırpma enclave'e aynı bayt bütçesinde daha çok yüz pikseli verir (kimlik ve olay
 * ölçümü) ve kullanıcının odasını gereksiz yere taşımaz.
 *
 * ## Koordinatlar
 *
 * ML Kit yüz kutusu DÖNDÜRÜLMÜŞ (dik) görüntü uzayında; `ImageProxy.toBitmap()` ise sensör
 * yönünde. Tam çözünürlüklü kareyi döndürüp sonra kırpmak ana iş parçacığında ~8 MB'lık ara
 * bitmap demek — olay anında kare kaybına yol açıyordu (çift kırpmanın ikincisi kaçıyordu). Bu
 * yüzden kırpma dikdörtgeni sensör uzayına çevrilir; kırpma, küçültme ve döndürme tek adımda
 * yapılır.
 */
object FaceCrop {

    /** Kırpma karesinin kenarı = yüz kutusunun uzun kenarı × bu kat. Kafa, saç ve çene sığsın. */
    const val SCALE = 2.2f

    /** [l, t, r, b] — tamsayı dikdörtgen. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * Dik uzaydaki yüz kutusunun çevresinde KARE bir kırpma. Kadraja sığmazsa önce içeri
     * KAYDIRILIR, yine sığmazsa küçültülür — yüz kenardayken kırpmanın merkezden kaymasını
     * kabul ederiz, kesilmesini değil.
     *
     * @param uprightW dik görüntünün genişliği, @param uprightH yüksekliği.
     */
    fun squareAround(faceLeft: Int, faceTop: Int, faceRight: Int, faceBottom: Int,
                     uprightW: Int, uprightH: Int, scale: Float = SCALE): Box {
        val side = min(
            (max(faceRight - faceLeft, faceBottom - faceTop) * scale).roundToInt(),
            min(uprightW, uprightH),
        ).coerceAtLeast(1)
        val cx = (faceLeft + faceRight) / 2
        val cy = (faceTop + faceBottom) / 2
        val left = (cx - side / 2).coerceIn(0, uprightW - side)
        val top = (cy - side / 2).coerceIn(0, uprightH - side)
        return Box(left, top, left + side, top + side)
    }

    /**
     * Dik uzaydaki bir dikdörtgenin SENSÖR (döndürülmemiş) uzaydaki karşılığı.
     *
     * Dik görüntü, sensör görüntüsünün saat yönünde [rotation] derece döndürülmüşüdür.
     * @param sensorW sensör görüntüsünün genişliği, @param sensorH yüksekliği.
     */
    fun toSensor(box: Box, rotation: Int, sensorW: Int, sensorH: Int): Box = when (((rotation % 360) + 360) % 360) {
        0 -> box
        // 90°: dik (x', y') ← sensör (x = y', y = H − x')
        90 -> Box(box.top, sensorH - box.right, box.bottom, sensorH - box.left)
        180 -> Box(sensorW - box.right, sensorH - box.bottom, sensorW - box.left, sensorH - box.top)
        // 270°: dik (x', y') ← sensör (x = W − y', y = x')
        270 -> Box(sensorW - box.bottom, box.left, sensorW - box.top, box.right)
        else -> throw IllegalArgumentException("Desteklenmeyen dönüş: $rotation")
    }
}
