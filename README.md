# VerifyBlind — Android

**Bu, VerifyBlind Android uygulamasının (`com.verifyblind.mobile`) herkese açık kaynak kodudur.**
Telefonunuzda çalışan uygulamanın **tam olarak burada gördüğünüz koddan** derlendiğini kendiniz
kanıtlayabilirsiniz — bize güvenmeniz gerekmez, matematiğe güvenirsiniz.

**This is the public source code of the VerifyBlind Android app (`com.verifyblind.mobile`).**
You can prove for yourself that the app running on your phone was built from **exactly the code you
see here** — you don't have to trust us, you trust the math.

🌐 [verifyblind.com](https://verifyblind.com) · 📦 [Releases](https://github.com/VerifyBlind/VerifyBlind-Android/releases) · 🍎 [iOS](https://github.com/VerifyBlind/VerifyBlind-iOS) · 🔐 [Enclave](https://github.com/VerifyBlind/VerifyBlind-Enclave)

**[🇹🇷 Türkçe](#türkçe) · [🇬🇧 English](#english)**

---

## Türkçe

### Bu repo nedir?

VerifyBlind, Türk dijital kimliğiyle (NFC'li kimlik kartı + canlılık doğrulaması) çalışan,
**sıfır-bilgi (zero-knowledge)** bir kimlik doğrulama sistemidir: kimlik numaranız (TCKN) cihazınızdan
ve güvenli enclave'den asla dışarı çıkmaz. Bu kadar iddialı bir güvenlik vaadinin tek anlamlı
ispatı, **çalıştırdığınız kodu doğrulayabilmenizdir**. Bu repo tam da bunun için var.

> Bu repo, özel geliştirme monorepo'sunun **salt-okunur kaynak aynasıdır**. Burada gördüğünüz her
> commit, aşağıdaki doğrulamada karşılaştırılan derleme çıktısını üretir.

### Telefonunuzdaki uygulamayı nasıl doğrularsınız?

Fikir basit: GitHub Actions her sürümde uygulamayı **yeniden-üretilebilir (reproducible)** şekilde
derler ve kod bileşenlerinin (DEX dosyaları) SHA-256 kriptografik özetlerini bir GitHub Release'ine
(`dex-hashes.json`) yayınlar. Doğrulama aracı telefonunuzdaki APK'nın DEX'lerini aynı şekilde
hash'leyip bu yayınlanan değerlerle karşılaştırır. Tüm satırlar eşleşirse, telefonunuzdaki kod
bit-bit GitHub'daki kodla aynıdır.

**Gereken:** Telefonda **USB Hata Ayıklama** açık (Ayarlar → Geliştirici Seçenekleri → USB Hata
Ayıklama; Geliştirici Seçenekleri gizliyse Ayarlar → Telefon Hakkında → Derleme Numarası'na 7 kez dokunun)
ve USB ile bağlı.

**Otomatik (Windows):**
```powershell
# Scripti indirip çalıştırın — ADB yoksa kendisi kurar:
irm https://cdn.verifyblind.com/autoverifyscripts/verify-windows.ps1 -OutFile verify-windows.ps1
./verify-windows.ps1
```
> Çalışmazsa: PowerShell'de `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` deyip tekrar deneyin.

**Otomatik (macOS / Linux):**
```bash
# Önce ADB: macOS → brew install android-platform-tools | Ubuntu → sudo apt install adb
curl -O https://cdn.verifyblind.com/autoverifyscripts/verify-unix.sh
chmod +x verify-unix.sh
./verify-unix.sh
```

Script önce versiyon ve commit hash'ini doğrudan telefonun belleğinden okur, sonra her
`classes*.dex` dosyasının SHA-256'sını hesaplayıp ilgili `build-<versionCode>` release'indeki
beklenen değerlerle karşılaştırır. Sonunda yeşil **DOĞRULAMA BAŞARILI** görürseniz, uygulamayı
çalıştıran kodun tamamen o commit'ten derlendiği matematiksel olarak kanıtlanmış olur.

Telefonunuzdaki sürüm ve commit'i uygulama içinde **Ayarlar ekranının en altındaki sürüm
satırında** da görebilirsiniz.

### Manuel doğrulama (ileri düzey)
```bash
adb shell pm path com.verifyblind.mobile          # APK yolunu bulun
adb pull <yol>/base.apk base.apk                   # APK'yı çekin ("package:" önekini atın)
unzip -q base.apk "*.dex" -d dex && sha256sum dex/*.dex
```
Çıktıyı ilgili [Release](https://github.com/VerifyBlind/VerifyBlind-Android/releases) sayfasındaki
`dex-hashes.json` ve **DEX Hash'leri** tablosuyla karşılaştırın — tüm satırlar birebir eşleşmelidir.

### Neden buna güvenebilirsiniz?
Bizim iddialarımıza değil, üç bağımsız gerçeğe güveniyorsunuz: (1) scriptin her satırını
çalıştırmadan önce okuyabilirsiniz; (2) karşılaştırılan hash'ler GitHub Actions'ın
**değiştirilemez** build kayıtlarından (ham log + release) gelir; (3) SHA-256 öyle bir özet
fonksiyonudur ki farklı bir kodun aynı hash'i üretmesi pratik olarak imkânsızdır. Aradaki herhangi
bir adımda (build sunucusu, dağıtım kanalı, cihaz depolaması) farklı bir kod gizlenseydi eşleşme olmazdı.

### Build nasıl çalışır?
[`.github/workflows/build-android.yml`](.github/workflows/build-android.yml) — `SOURCE_DATE_EPOCH`
ile zaman damgaları sabitlenir, AAB derlenir, DEX hash'leri çıkarılır ve her sürüm için
`build-<versionCode>` etiketli bir GitHub Release yayınlanır (`dex-hashes.json` + `app-release.aab`).

---

## English

### What is this repo?

VerifyBlind is a **zero-knowledge** identity verification system built on the Turkish digital ID
(NFC ID card + liveness check): your national ID number never leaves your device or the secure
enclave. The only meaningful proof of such a strong security claim is that **you can verify the code
you are actually running**. That is exactly what this repo is for.

> This repo is a **read-only source mirror** of the private development monorepo. Every commit you
> see here produces the build artifact that the verification below compares against.

### How to verify the app on your phone

The idea is simple: on every release, GitHub Actions builds the app **reproducibly** and publishes
the SHA-256 hashes of its code components (DEX files) to a GitHub Release (`dex-hashes.json`). The
verification tool hashes the DEX files inside the APK on your phone the same way and compares them
to those published values. If every line matches, the code on your phone is bit-for-bit identical to
the code on GitHub.

**Requires:** **USB debugging** enabled (Settings → Developer options → USB debugging; if Developer
options is hidden, tap Settings → About phone → Build number 7 times) and the phone connected via USB.

**Automatic (Windows):**
```powershell
irm https://cdn.verifyblind.com/autoverifyscripts/verify-windows.ps1 -OutFile verify-windows.ps1
./verify-windows.ps1
```
> If it won't run: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` in PowerShell, then retry.

**Automatic (macOS / Linux):**
```bash
# ADB first: macOS → brew install android-platform-tools | Ubuntu → sudo apt install adb
curl -O https://cdn.verifyblind.com/autoverifyscripts/verify-unix.sh
chmod +x verify-unix.sh
./verify-unix.sh
```

The script reads the version and commit hash directly from your phone's memory, then computes the
SHA-256 of each `classes*.dex` file and compares it against the expected values in the matching
`build-<versionCode>` release. A green **VERIFICATION SUCCESSFUL** at the end is mathematical proof
that the running code was built entirely from that commit.

You can also see your installed version and commit in the app, on the **version line at the bottom of
the Settings screen**.

### Manual verification (advanced)
```bash
adb shell pm path com.verifyblind.mobile          # find the APK path
adb pull <path>/base.apk base.apk                  # pull it (drop the "package:" prefix)
unzip -q base.apk "*.dex" -d dex && sha256sum dex/*.dex
```
Compare the output against `dex-hashes.json` and the **DEX Hashes** table on the matching
[Release](https://github.com/VerifyBlind/VerifyBlind-Android/releases) — every line must match exactly.

### Why you can trust this
You're not trusting our claims but three independent facts: (1) you can read every line of the script
before running it; (2) the compared hashes come from GitHub Actions' **immutable** build records (raw
logs + release); (3) SHA-256 is a digest function for which it is practically impossible for different
code to produce the same hash. If different code had been slipped in at any step (build server,
distribution channel, device storage) the hashes would not match.

### How the build works
[`.github/workflows/build-android.yml`](.github/workflows/build-android.yml) pins timestamps with
`SOURCE_DATE_EPOCH`, builds the AAB, extracts the DEX hashes, and publishes a GitHub Release tagged
`build-<versionCode>` for each version (`dex-hashes.json` + `app-release.aab`).
