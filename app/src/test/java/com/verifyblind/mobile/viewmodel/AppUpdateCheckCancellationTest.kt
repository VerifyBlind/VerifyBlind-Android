package com.verifyblind.mobile.viewmodel

import com.verifyblind.mobile.api.KimlikApi
import com.verifyblind.mobile.api.RetrofitClient
import com.verifyblind.mobile.util.AppLog
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * Güncelleme kontrolü coroutine iptalini YUTMAMALI (VERIFYBLIND-ANDROID-8).
 *
 * Activity yeniden kurulunca ya da kapatılınca kapsam iptal edilir; `isAppOutdated` içindeki
 * `catch (e: Exception)` `JobCancellationException`'ı da yakalayıp "Güncelleme kontrolü başarısız:
 * Job was cancelled" warning'ini gönderiyordu (21 olay). İptal arıza değildir ve yayılmalıdır.
 *
 * Ağ katmanı `RetrofitClient.api` (bir `object` içindeki `lazy`) — test, lazy'nin değerini
 * istenen istisnayı fırlatan bir vekille değiştirir ve sonunda geri koyar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppUpdateCheckCancellationTest {

    private val captured = mutableListOf<SentryEvent>()

    // `api$delegate` bir `object`'te static final → alan değiştirilemez. İçindeki `Lazy`
    // nesnesinin (SynchronizedLazyImpl) final olmayan `_value` alanı değiştirilir.
    private val lazyApi: Any = RetrofitClient::class.java.getDeclaredField("api\$delegate")
        .apply { isAccessible = true }
        .get(null)!!
    private val lazyValueField = lazyApi.javaClass.getDeclaredField("_value")
        .apply { isAccessible = true }
    private var originalValue: Any? = null

    @Before
    fun setUp() {
        captured.clear()
        Sentry.init({ options: SentryOptions ->
            options.dsn = "https://public@localhost/1"
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                captured += event
                null
            }
        }, /* globalHubMode = */ true)
        AppLog.setOnlineProbeForTest { true }
        originalValue = lazyValueField.get(lazyApi)
    }

    @After
    fun tearDown() {
        lazyValueField.set(lazyApi, originalValue)
        AppLog.setOnlineProbeForTest(null)
        Sentry.close()
    }

    /** `getAppConfig` çağrısı [failure]'ı fırlatır (askıya alma noktasında iptal edilmiş gibi). */
    private fun appConfigThrows(failure: RuntimeException) {
        val api = Proxy.newProxyInstance(
            KimlikApi::class.java.classLoader, arrayOf(KimlikApi::class.java)
        ) { _, method, _ ->
            if (method.name == "getAppConfig") throw failure
            throw UnsupportedOperationException(method.name)
        } as KimlikApi
        lazyValueField.set(lazyApi, api)
    }

    private fun newViewModel(): MainViewModel =
        MainViewModel(RuntimeEnvironment.getApplication()).also { captured.clear() }

    @Test
    fun cancellation_isRethrown_andSendsNoEvent() {
        val vm = newViewModel()
        appConfigThrows(CancellationException("Job was cancelled"))

        try {
            runBlocking { vm.isAppOutdated() }
            fail("iptal yayılmalıydı")
        } catch (e: CancellationException) {
            // beklenen
        }
        assertEquals(0, captured.size)
    }

    /** Kontrast: gerçek bir hata hâlâ warning olarak gider ve fail-open davranışı korunur. */
    @Test
    fun otherFailure_staysWarning_andFailsOpen() {
        val vm = newViewModel()
        appConfigThrows(IllegalStateException("boom"))

        val outdated = runBlocking { vm.isAppOutdated() }

        assertFalse(outdated)
        assertEquals(1, captured.size)
        assertEquals(SentryLevel.WARNING, captured[0].level)
    }
}
