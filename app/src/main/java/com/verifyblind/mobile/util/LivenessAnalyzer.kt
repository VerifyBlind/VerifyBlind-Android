package com.verifyblind.mobile.util

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.hypot

class LivenessAnalyzer(
    /**
     * @param otherFaceCount kadrajdaki DİĞER (ana yüzün yarısından büyük) yüz sayısı.
     *   Canlılık boyunca tek yüz kuralını uygulamak için gerekli; eskiden bu bilgi hiç
     *   dışarı çıkmıyordu ve fazla yüzler sessizce atılıyordu.
     */
    private val onFaceDetected: (face: Face, imageProxy: ImageProxy, otherFaceCount: Int) -> Unit,
    // Her karede (yüz bulunsa da bulunmasa da) ortalama parlaklık (0..255).
    // Karanlık/aşırı-parlak ortam uyarısı için kullanılır.
    private val onFrameLuma: ((luma: Float) -> Unit)? = null,
    /**
     * Bu karede dudak konturu ölçülsün mü — yalnız gerektiğinde (ağız açma durağı). Her karede
     * ikinci bir dedektör çalıştırmak kare hızını düşürür ve göz kırpma gibi kısa olayları kaçırtır.
     */
    private val contourWanted: (() -> Boolean)? = null,
    /**
     * İç dudak açıklığı (üst dudağın alt kenarı ile alt dudağın üst kenarı arası / ağız genişliği).
     * [onFaceDetected]'dan HEMEN ÖNCE, aynı iş parçacığında çağrılır. Ölçülemezse null.
     */
    private val onContour: ((lipOpen: Float?) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // For Eyes/Smile
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .enableTracking()
                .build()
        )
    }

    /**
     * DUDAK KONTURU için ayrı dedektör.
     *
     * 🔴 Neden ayrı: ML Kit kontur kipinde YALNIZ EN BELİRGİN yüzü algılar ve takip numarası
     * vermez — ana dedektörde açılsa "kadrajda ikinci yüz" kuralı ve takip numarası kırılırdı.
     *
     * 🔴 Neden kontur: sahada (2026-09-24) ağız açıldığında ML Kit'in alt dudak NOKTASI aşağı değil
     * yukarı gitti (ağız köşesi hattına göre −0,10) ve gülümseme olasılığı 0,82'ye çıktı; "ağzını
     * aç" komutu yalnız somurtma hareketiyle geçilebildi. Konturda iç dudak kenarları doğrudan
     * ölçülüyor.
     */
    private val contourDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Ucuz parlaklık ölçümü: Y (luma) düzleminden seyrek örnekleme.
            // Yüz tespitinden BAĞIMSIZ — karanlıkta yüz bulunamasa bile uyarı verebilmek için.
            onFrameLuma?.invoke(averageLuma(mediaImage))

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        // ⚠️ Kadrajdaki DİĞER yüzler eskiden sessizce atılıyordu. Artık sayılıyor:
                        // "canlılık boyunca tek yüz" kuralı ancak ikinci bir yüzün görüldüğünü
                        // bilirsek uygulanabilir — saldırının şekli tam olarak budur (ekranda
                        // kart sahibi, kadrajda jesti yapan başka biri).
                        //
                        // Küçük/uzaktaki yüzler sayılmaz: arkadan geçen biri meşru kullanıcıyı
                        // reddettirmemeli. Eşik ana yüzün yarısı.
                        val primary = faces[0]
                        val minW = primary.boundingBox.width() * 0.5f
                        val others = faces.count { it !== primary && it.boundingBox.width() >= minW }
                        if (onContour != null && contourWanted?.invoke() == true) {
                            // Görüntü hâlâ açık: kapatma onFaceDetected'ın sonunda.
                            contourDetector.process(image)
                                .addOnSuccessListener { cf ->
                                    onContour.invoke(innerLipOpen(cf.firstOrNull()))
                                    onFaceDetected(primary, imageProxy, others)
                                }
                                .addOnFailureListener {
                                    onContour.invoke(null)
                                    onFaceDetected(primary, imageProxy, others)
                                }
                        } else {
                            onFaceDetected(primary, imageProxy, others)
                        }
                    } else {
                        imageProxy.close()
                    }
                }
                .addOnFailureListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    /**
     * İç dudak açıklığı: üst dudağın ALT kenarının ortası ile alt dudağın ÜST kenarının ortası
     * arası, iç ağız genişliğine (üst dudak alt kenarının iki ucu) bölünmüş. Kapalı ağızda ~0;
     * ölçek ve kafa eğiminden bağımsız.
     */
    private fun innerLipOpen(face: Face?): Float? {
        val upper = face?.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: return null
        val lower = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: return null
        if (upper.size < 3 || lower.size < 3) return null
        val u = upper[upper.size / 2]
        val l = lower[lower.size / 2]
        val width = hypot(upper.last().x - upper.first().x, upper.last().y - upper.first().y)
        if (width < 1f) return null
        return hypot(l.x - u.x, l.y - u.y) / width
    }

    /** YUV_420_888 Y düzleminden ~2048 örnekle ortalama parlaklık (0..255). Hatada 128 (nötr). */
    private fun averageLuma(image: android.media.Image): Float {
        return try {
            val y = image.planes[0].buffer
            val n = y.remaining()
            if (n <= 0) return 128f
            val step = maxOf(1, n / 2048)
            var sum = 0L
            var count = 0
            var i = 0
            while (i < n) {
                sum += (y.get(i).toInt() and 0xFF)
                count++
                i += step
            }
            if (count > 0) sum.toFloat() / count else 128f
        } catch (e: Exception) {
            128f
        }
    }
}

