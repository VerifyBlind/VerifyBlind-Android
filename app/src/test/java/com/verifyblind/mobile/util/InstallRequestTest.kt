package com.verifyblind.mobile.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallRequestTest {

    private val nonce = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val pkHash = "a".repeat(64)
    private val now = 1_800_000_000L

    @Test
    fun `gecerli metin istege cozulur`() {
        val request = InstallRequest.parse("nonce=$nonce&pk_hash=$pkHash", now - 60, now)
        assertEquals(InstallRequest.Request(nonce, pkHash), request)
    }

    @Test
    fun `bos ya da reklam metni yok sayilir`() {
        assertNull(InstallRequest.parse(null, now, now))
        assertNull(InstallRequest.parse("utm_source=google-play&utm_medium=organic", now, now))
    }

    @Test
    fun `eski tiklama yeniden islenmez`() {
        assertNull(InstallRequest.parse("nonce=$nonce&pk_hash=$pkHash", now - 16 * 60, now))
        assertNull(InstallRequest.parse("nonce=$nonce&pk_hash=$pkHash", 0, now))
    }

    @Test
    fun `bicime uymayan degerler reddedilir`() {
        assertNull(InstallRequest.parse("nonce=a'b&pk_hash=$pkHash", now, now))
        assertNull(InstallRequest.parse("nonce=$nonce&pk_hash=xyz", now, now))
        assertNull(InstallRequest.parse("nonce=$nonce", now, now))
    }
}
