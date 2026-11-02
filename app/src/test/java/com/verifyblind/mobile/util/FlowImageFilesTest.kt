package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Akış bitince yüz ve çip görüntüleri önbellekte kalmaz (biyometrik rıza metninin sözü);
 * akışla ilgisi olmayan dosyalara dokunulmaz.
 */
class FlowImageFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun deletesFlowImagesAndEventFrames_keepsUnrelatedFiles() {
        val dir = tmp.root
        listOf(
            "chip_temp.jpg", "dg2_stream.bin", "chip_aligned.png", "selfie_best.png",
            "approved_selfie.png", "event_blink_0.jpg", "login_selfie.png",
            "login_move_proof.json", "partner_logo.png",
        ).forEach { File(dir, it).writeText("x") }

        FlowImageFiles.deleteAll(dir)

        assertEquals(listOf("partner_logo.png"), dir.list()!!.sorted())
    }

    /** Geri bildirim kutusuna giden iki kare kutu kapanana kadar kalır. */
    @Test
    fun keptPathsSurvive() {
        val dir = tmp.root
        val selfie = File(dir, "selfie_best.png").apply { writeText("x") }
        val chip = File(dir, "chip_aligned.png").apply { writeText("x") }
        File(dir, "chip_temp.jpg").writeText("x")

        FlowImageFiles.deleteAll(dir, keep = listOf(selfie.absolutePath, chip.absolutePath, null))

        assertEquals(listOf("chip_aligned.png", "selfie_best.png"), dir.list()!!.sorted())
    }
}
