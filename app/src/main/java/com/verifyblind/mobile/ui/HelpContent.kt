package com.verifyblind.mobile.ui

import com.verifyblind.mobile.R

data class ScreenGuide(
    val title: String,
    val iconRes: Int,
    val iconBgRes: Int,
    val iconTintHex: String,
    val purpose: String,
    val steps: String,
    val troubleshooting: String,
    val securityNote: String
)

data class FaqCategory(
    val title: String,
    val titleColorHex: String,
    val items: List<FaqItem>
)

data class FaqItem(
    val question: String,
    val answer: String
)

object HelpContent {

    val screenGuides: List<ScreenGuide> = listOf(

        ScreenGuide(
            title = "Cüzdan (Ana Ekran)",
            iconRes = R.drawable.ic_wallet,
            iconBgRes = R.drawable.bg_step_icon_blue,
            iconTintHex = "#0060AA",
            purpose = "Uygulama açıldığında karşınıza gelen ana ekrandır. Henüz kart eklemediyseniz boş bir karşılama görseli ve \"Kimlik Kartı Ekle\" butonu görürsünüz; kart kayıtlıyken kart görseliniz ve \"QR ile Giriş Yap\" butonu yer alır.",
            steps = "Boş durumda:\n1. \"Kimlik Kartı Ekle\" butonuyla 4 adımlı kart ekleme akışına başlayın.\n2. Butonun altındaki \"Nasıl Çalışır?\" bağlantısı sizi bu rehbere getirir.\n3. Sağ üstteki dişli simgesinden Ayarlar'a geçebilirsiniz.\n\nKart kayıtlıyken:\n1. \"QR ile Giriş Yap\" butonuyla doğrudan QR tarama akışı açılır.\n2. Kart görseline dokunduğunuzda da aynı QR tarama akışı başlar.\n3. Alttaki \"Kimliği Sil\" bağlantısı kayıtlı kartı cihazdan kaldırır.",
            troubleshooting = "• Kart görünmüyor: Henüz kart eklememişsiniz. Kart Ekleme butonu ile kimlik kartınızı ekleyin. VerifyBlind üyelik sistemi yoktur, dolayısıyla kartlarınız ve kişisel bilgileriniz yalnızca telefonunuzda saklanır. Bu yüzden uygulamayı kaldırıp tekrar kurduysanız kayıtlı kartlarınız telefonunuzdan silinmiştir. Tekrar eklemeniz gerekir.\n• Açılışta biyometrik isteniyor: Ayarlar'daki \"Biyometrik Kilitleme\" özelliği açık demektir; her uygulama açılışında biyometrik girişi yapmak istemiyorsanız bu özelliği kapatabilirsiniz.\n• \"QR ile Giriş Yap\" butonuna bastığınızda \"Bağlantı kuruluyor\" mesajı uzun sürüyor veya bağlantı hatası veriyor: İnternet bağlantınızı kontrol edip birkaç saniye bekleyin.",
            securityNote = "Kayıtlı kart verisi yalnızca cihazınızda, Android Keystore destekli şifreleme ile saklanır. Sunucumuzda kart verinizin bir kopyası tutulmaz."
        ),

        ScreenGuide(
            title = "Kart Ekleme Akışı (4 Adım)",
            iconRes = R.drawable.ic_add_card,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = "Kimlik kartınızı uygulamaya eklemek için kullanılan ekrandır. Üst kısımda Hazırlık → MRZ → NFC → Yüz adımlarını gösteren bir ilerleme şeridi vardır ve her adımdan bir öncekine geri butonuyla dönebilirsiniz.",
            steps = "1. Hazırlık: 4 maddelik hazırlık ipuçlarını ve \"Aydınlatma Metnini Oku\" bağlantısını gördükten sonra KVKK onay kutusunu işaretleyin ve \"Başla\" butonuna basın.\n2. MRZ: Kameranın açtığı çerçeveye kimlik kartının arka yüzünü (Ilk satırı \"I<\" veya \"P<\" ile başlayan bölge) tutun; bu bölüm otomatik okunacaktır.\n3. NFC: Ekran NFC adımına geçtiğinde kartınızı telefonun arkasına dokundurun; titreşim ve yeşil tamam göstergesi gelene kadar sabit tutun. Sorunsuz çalışması için varsa telefonunuzun kılıfını çıkarın.\n4. Yüz: NFC tamamlanınca canlılık testi için biyometrik onay alt menüsü açılır; \"Onaylıyorum\" dediğinizde ön kamera açılır ve canlılık testi başlar. Ekranın üstünde görünen yönergeleri izleyin ve sizden yapmanız istenen 5 hareketi (Başınızı sağa/sola çevirme, göz kırpma, gülümseme...) yapın. \nBittiğinde \"Kayıt Başarılı\" ekranını ve \"Ana Sayfaya Dön\" butonunu görürsünüz.",
            troubleshooting = "• Üst köşedeki geri okuyla istediğiniz adıma dönüp baştan deneyebilirsiniz.\n• NFC kapalıysa otomatik olarak NFC ayarlarına yönlendirme dialog'u açılır.\n• NFC ardarda üç kez başarısız olursa \"Bağlantı Başarısız\" dialog'u çıkar; kapatıp baştan deneyin.\n• Telefonda NFC yoksa kart ekleme başlatılamaz — uygulama bunu kart ekleme başında bildirir.",
            securityNote = "MRZ, NFC ve Yüz tanıma adımlarında okunan veriler uçtan uca şifreli şekilde güvenli AWS Nitro Enclave ortamına gönderilir, kontroller sonrası imzalı örneği üretilir ve cep telefonunuza geri gönderilir. Hiçbir kimlik bilginiz sunucuda saklanmaz."
        ),

        ScreenGuide(
            title = "NFC Okuma",
            iconRes = R.drawable.ic_nfc_waves,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = "Kimlik kartınızın içindeki ICAO 9303 standardındaki çipten devlet imzalı verileri güvenle okuyan adımdır. Ekranda büyük bir NFC daire animasyonu ve ilerleme çubuğu vardır.",
            steps = "1. \"Kimlik kartınızın çipini telefonunuzun arka yüzüne dokundurun\" yazısı çıkınca kimliğinizi telefonunuzun NFC okuyucusuna dokundurun.\n2. NFC okuyucu genelde telefonun arka yüzünün üst ya da orta bölgesindedir — bağlantı bulunana kadar yavaşça kaydırın.\n3. Titreşim gelip ekranda \"Okunuyor...\" + ilerleme çubuğu görüldüğünde telefonu sabit tutun (~10 sn).\n4. Daire yeşile döndüğünde okuma tamamdır, otomatik olarak yüz doğrulama adımına geçilir.",
            troubleshooting = "• NFC kapalı uyarısı: Diyalogtaki \"Ayarlara Git\" ile sistem NFC ayarına gidin, açın, geri dönün.\n• Metal/karbon fiber kılıflar NFC'yi engeller — telefon ve kart kılıflarını çıkarın.\n• Bazı kartlarda PACE-MRZ ve BAC doğrulamaları başarısız olur; bu durumda kart PACE-CAN gerektirdiği için \"CAN Kodu Gerekli\" dialog'u açılır (deneme sayısından bağımsız, ilk denemede de gelebilir). Kartın ön yüzündeki 6 haneli CAN kodunu girip kartı tekrar okutun.\n• Bunun dışında üçüncü başarısız denemeden sonra ayrı bir \"BAĞLANTI BAŞARISIZ\" dialog'u çıkar — kapatıp baştan deneyin.\n• Yalnızca NFC çipli yeni nesil TC Kimlik Kartı veya elektronik pasaport desteklenir; eski nüfus cüzdanı çalışmaz.",
            securityNote = "Çipten okunan veriler CSCA/CRL sertifika zinciri kullanılarak Enclave içinde doğrulanır; sahte, kopya ya da iptal edilmiş bir belge bu adımı geçemez."
        ),

        ScreenGuide(
            title = "Yüz (Canlılık) Doğrulama",
            iconRes = R.drawable.ic_face_smile,
            iconBgRes = R.drawable.bg_step_icon_green,
            iconTintHex = "#2E7D32",
            purpose = "Kartı elinde tutan kişinin gerçekten kart sahibi olduğunu kanıtlayan adımdır. NFC sonrasında biyometrik onay alt menüsü çıkar, onayladığınızda ön kamera açılır ve 5 hareketten oluşan bir canlılık testi başlar.",
            steps = "1. Telefonu göz hizanızda dik tutun, ön kamerada beliren oval içine yüzünüzü hizalayın.\n2. Üstte sayaç 30 saniyeden geri sayar; aşağıdaki yönergeyi okuyun (\"Başını SOLA Çevir\", \"Başını SAĞA Çevir\", \"Gözlerini Kırp\", \"Gülümse\").\n3. Yönergeleri tek tek uygulayın — her doğru harekette sayaç bir sonraki yönergeye geçer (toplam 5 hareket).\n4. Tüm hareketler bittiğinde otomatik olarak \"Kayıt Başarılı\" ekranına geçilir.",
            troubleshooting = "• Ortam çok karanlık/parlaksa yüz tanınmayabilir; ekranın parlaklığı otomatik maksimuma çıkarılır, ortamı dengeli aydınlatın.\n• Gözlük, şapka veya maske takmayın — Hazırlık ekranında da bu hatırlatılır.\n• 30 saniyede tamamlanamazsa zaman aşımı ekranı gelir; \"Tekrar Dene\" ile baştan başlatabilirsiniz.\n• Yüz, çipteki fotoğrafa en az %65 oranında eşleşmelidir; tutarsız aydınlatma veya hareket bu oranı düşürür.",
            securityNote = "Ön kameradan alınan kareler cihazda işlenir; çipteki fotoğraf ile karşılaştırma cihaz üzerindeki yüz tanıma modeli ile yapılır. Başarısız denemelerin selfie dosyası silinir, başarılı kayıttan sonra ham görüntü saklanmaz."
        ),

        ScreenGuide(
            title = "QR ile Giriş ve Onay",
            iconRes = R.drawable.bg_qr_scan,
            iconBgRes = R.drawable.bg_step_icon_purple,
            iconTintHex = "#6A1B9A",
            purpose = "Bir partnerin sitesinde veya uygulamasında kimlik doğrulaması istendiğinde kullanılan akıştır. İki şekilde başlar: ya partner sayfasındaki QR'ı tararsınız ya da partner sayfasındaki \"VerifyBlind ile Doğrula\" bağlantısına dokunursunuz; ikisi de aynı onay ekranını açar.",
            steps = "1. Cüzdan'daki \"QR ile Giriş Yap\" butonuna (veya kart görseline) dokunarak QR tarayıcıyı açın.\n2. Tarayıcıyı partner ekranındaki QR koda doğrultun; QR yakalandığında \"Lütfen Bekleyiniz\" ekranı görünür.\n3. Açılan Onay alt menüsünde partnerin logosu/baş harfleri, adı ve istenen veri listesi (örn: \"Size özel oluşturulmuş kod\" veya \"Yaş doğrulaması (18+)\") yer alır.\n4. \"Aydınlatma Metnini Oku\" bağlantısını okuyup KVKK onay kutusunu işaretleyin; \"Onayla\" butonu aktifleşir.\n5. \"Onayla\"ya bastığınızda parmak izi/yüz kilidi açılır; doğruladığınızda partner sayfasında işlem tamamlanır ve uygulamada \"Başarılı! ✅\" mesajı çıkar.",
            troubleshooting = "• \"Geçersiz QR\" hatası: QR partner tarafından yeni üretilmiş olmalı; sayfayı yenileyip yeniden tarayın.\n• Onay penceresinde \"Reddet\" derseniz işlem iptal edilir.\n• İşlemi tamamen geri almak için: Ayarlar > İşlem Geçmişi'ne girin, ilgili paylaşımı sağa kaydırıp \"Kimlik Paylaşımını Geri Al\"ı seçin.",
            securityNote = "Her partnerle paylaşılan kimlik kodu o partnere özgü ve birbiriyle ilişkisizdir; iki farklı partner aynı kullanıcıyı eşleştiremez. Kimlik bilgileriniz asla partnerle paylaşılmaz."
        ),

        ScreenGuide(
            title = "İşlem Geçmişi",
            iconRes = R.drawable.ic_history,
            iconBgRes = R.drawable.bg_step_icon_blue,
            iconTintHex = "#0060AA",
            purpose = "Yapılan kart kaydı, partnerlerle yapılan paylaşımlar ve geri alma işlemlerinin kronolojik listesidir. Ayarlar > İşlem Geçmişi yolundan açılır ve ilk açılışta cihaz biyometrisi sorulur.",
            steps = "1. Ekran açıldığında biyometrik doğrulama isteği gelir — onaylayın; başarısız olursa kilit ekranına dokunarak tekrar deneyebilirsiniz.\n2. Yedekleme bağlı değilse listenin üstünde uyarı banner'ı çıkar; \"Yedekle\" butonu sizi doğrudan Ayarlar > Şifreli Yedekleme'ye götürür.\n3. Bir kaydı SOLA kaydırın: \"Kaydı Sil\" onayı çıkar — onaylarsanız o satır geçmişten silinir.\n4. Bir kaydı SAĞA kaydırın: işlem tipine göre dialog açılır.\n   • Paylaşım kaydında: \"Kimlik Paylaşımını Geri Al\" — partnerden silinme talebi gönderilir.\n   • Kart kaydında: \"Kart Kaydı Rızasını Geri Çek\" — biyometrik doğrulamadan sonra hem rıza geri çekilir hem de kart cüzdandan kaldırılır.",
            troubleshooting = "• Liste boş görünüyorsa: Yedekten geri yüklediyseniz partnerlerin yüklenmesi birkaç saniye sürebilir; sayfayı yeniden açın.\n• Geri çekme/geri alma sırasında \"Bağlantı Hatası\" gelirse internet bağlantısını kontrol edip tekrar deneyin.\n• Daha önce geri çekilmiş kayıtlar tekrar kaydırılınca işlem yapmaz, satır eski haline döner.",
            securityNote = "Geçmiş satırları cihaz üzerinde şifrelenmiş olarak saklanır. Şifreli Yedekleme bağlı olduğunda silme ve geri alma işlemlerinin ardından bulut yedeği otomatik olarak güncellenir.\n•Lütfen doğrulama işlem geçmişinin sadece telefonunuzda VerifyBlind uygulamasının içinde saklandığını unutmayın. Eğer yedekleme bağlantısı yapmazsanız uygulamayı kaldırdığınızda geçmişiniz silinir. Yedekleme bağlantısı yaptığınız takdirde her hangi bir telefonda aynı yedekleme bağlantısı yapıp kart eklediğinizde işlem geçmişinizi görebilirsiniz.\n•Yedekleme bağlantısı, işlem geçmişinizi bulut hesabınızda şifreli olarak saklar, VerifyBlind uygulamanıza kart eklemeniz haricinde kimse tarafından okunamaz."
        ),

        ScreenGuide(
            title = "Ayarlar",
            iconRes = R.drawable.ic_settings,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = "Cüzdan ekranının sağ üstündeki dişli simgesinden açılan ekrandır. Tek bir liste halinde 8 başlık sunar.",
            steps = "Sırasıyla:\n1. Biyometrik Kilitleme (anahtar) — Açıldığında uygulama her açılışta biyometri ister; varsayılan kapalıdır.\n2. İşlem Geçmişi — Geçmiş ekranına gider.\n3. Sistem Güvenliği — Enclave/PCR0 detayını gösterir.\n4. Nasıl Çalışır — Bu rehbere döner.\n5. SSS — Sıkça sorulan sorular web sayfası.\n6. Şifreli Yedekleme — Bulut yedeği menüsünü açar.\n7. Kartımı Engelle — Yalnızca kayıtlı kart varsa görünür; çalıntı/kayıp kartı kalıcı engeller.\n8. Verilerimi Sil — Cüzdanı sıfırlar, uygulamayı yeniden başlatır.",
            troubleshooting = "• Biyometri anahtarı açılmıyor: Cihaza tanımlı parmak izi/yüz olmalı; Android güvenlik ayarlarından önce biyometriyi tanımlayın.\n• \"Kartımı Engelle\" satırı görünmüyor: Henüz kayıtlı bir kart yok ya da kart silinmiş demektir.\n• \"Verilerimi Sil\" sonrası uygulama açılmıyorsa: Sistem uygulamayı yeniden başlatır; ekran kararırsa elle yeniden açın.",
            securityNote = "\"Verilerimi Sil\" cüzdan veritabanını, gizli SharedPreferences'ı, Keystore anahtarını ve buluttaki oturumu hep birlikte temizler — uygulama ilk kurulum haline döner. Buluttaki yedek dosyasını silmek istiyorsanız bağlantıyı önce kesmeyin, doğrudan bulut sağlayıcınızdan silin."
        ),

        ScreenGuide(
            title = "Sistem Güvenliği",
            iconRes = R.drawable.ic_shield,
            iconBgRes = R.drawable.bg_step_icon_green,
            iconTintHex = "#2E7D32",
            purpose = "Uygulamanın bağlandığı AWS Nitro Enclave örneğine ilişkin doğrulama bilgilerini gösterir. Ayarlar > Sistem Güvenliği yolundan açılır.",
            steps = "Ekranda dört bölüm vardır:\n1. Üstte \"DONANIM KORUMASI AKTİF\" başlığı ve AWS Nitro Enclave imzası.\n2. Enclave Parmak İzi (PCR0): Sunucudaki kodun matematiksel özetidir.\n3. Yetkili Yayıncı: verifyblind.com — kodun imzalı sahibi.\n4. Doğrulama Durumu: ✅ Donanım Tarafından Onaylı, ⚠️ Geliştirici Modu (Mock) ya da ❌ Doğrulanamadı. Altında son kontrol zamanı yer alır.\n5. En altta \"Kaynak Kodunu İncele\" satırı GitHub deposunu açar.",
            troubleshooting = "• \"❌ Doğrulanamadı\" görüyorsanız uygulamayı kullanmayın; uygulamayı güncelleyin ya da destek ile iletişime geçin.\n• \"Henüz kontrol edilmedi\" yazıyorsa el sıkışma henüz tamamlanmamıştır; Cüzdan ekranına dönüp internet bağlantınızı kontrol edin.",
            securityNote = "PCR0 değeri sunucudaki kodun parmak izidir. VerifyBlind tarafından imzalanmış bilinen PCR0 değerleriyle eşleşmediğinde uygulama sunucuyla işlem yapmayı reddeder."
        ),

        ScreenGuide(
            title = "Şifreli Yedekleme",
            iconRes = R.drawable.ic_lock_small,
            iconBgRes = R.drawable.bg_step_icon_teal,
            iconTintHex = "#00897B",
            purpose = "Cüzdan verilerinizi (kart kaydı, geçmiş, partner listesi) şifreli olarak bulut hesabınızda yedeklemenizi sağlar. Ayarlar > Şifreli Yedekleme satırından açılır; ayrıca İşlem Geçmişi ekranındaki banner üzerinden de başlatılabilir.",
            steps = "Bağlantı kurmak için:\n1. \"Şifreli Yedekleme\" satırına dokunun.\n2. \"Bulut Sağlayıcı Seçin\" dialog'unda Dropbox veya Google Drive'ı seçin.\n3. Açılan oturum akışında ilgili hesabınızla giriş yapın ve uygulama izinlerini onaylayın.\n4. Otomatik olarak ilk yedek alınır; ekranda \"Son yedek: ...\" görünür.\n\nManuel eşitleme / bağlantı yönetimi:\n1. Bağlıyken \"Şifreli Yedekleme\" satırına dokunun.\n2. \"Şimdi Eşitle\" derseniz biyometrik doğrulamadan sonra eşitleme çalışır.\n3. \"Bağlantıyı Kes\" derseniz oturum ayrılır — bulut hesabındaki yedek dosyası silinmez.",
            troubleshooting = "• Dropbox/Google Drive girişi çekirdek tarayıcıyı açar — engelleyiciniz varsa kapatın.\n• \"Eşitleme Hatası\" mesajı: Bulut sağlayıcıdan dönen hata gösterilir; çoğunlukla internet veya izin sorunudur, tekrar deneyin.\n• Yeni telefona geçişte: VerifyBlind'i kurun, aynı şifreli yedek dosyasını üreten bulut hesabıyla bağlanın, kart ekleyin, eşitleme tamamlanır.",
            securityNote = "Yedek dosyası verifyblind_backup.json adıyla bulutta tutulur; içindeki geçmiş kayıtları zaten cihazda şifrelidir, bulut sağlayıcısı bile içerikleri okuyamaz. Yedeği geri yüklemek için aynı bulut hesabına bağlanmanız gerekir."
        )
    )

    val faqCategories: List<FaqCategory> = listOf(

        FaqCategory(
            title = "🛡️  GÜVENLİK & GİZLİLİK",
            titleColorHex = "#0060AA",
            items = listOf(
                FaqItem(
                    question = "Kimlik bilgilerim nerede saklanıyor?",
                    answer = "Doğrulama sırasında kimlik verileriniz uçtan uca şifrelenip AWS Nitro Enclave adındaki, dışarıdan erişilemeyen izole çalışma ortamına gönderilir. Burada kontrol edilip imzalı bir kanıt üretildikten sonra tüm kimlik bilgileriniz tamamen silinir. Kart kaydınız ise yalnızca telefonunuzda, Android Keystore destekli şifrelemeyle saklanır."
                ),
                FaqItem(
                    question = "TC Kimlik Numaram partnerlerle paylaşılıyor mu?",
                    answer = "Hayır. VerifyBlind sıfır bilgi (zero-knowledge) prensibiyle çalışır. Her iş ortağı için size özel, başka partnerlerle ilişkisiz bir kimlik kodu üretilir; bu kod kişisel bilgilerinizi içermez ve iki farklı partner aynı kullanıcıyı eşleştirmek için bu kodları kullanamaz. Hiçbir zaman doğum tarihiniz paylaşılmaz. Yalnızca partner'in sorduğu yaş kriterini karşılayıp karşılamadığınız bilgisi evet veye hayır şeklinde (sizin son onayınızdan sonra) yanıtlanır."
                ),
                FaqItem(
                    question = "Yüz fotoğrafım kaydediliyor mu?",
                    answer = "Hayır. Canlılık testindeki kareler cihazınızda işlenir; çipteki fotoğrafla karşılaştırma da yine cihaz üzerinde ve disksiz, donanımsal olarak izole Nitro Enclave sunucuda yapılır. Başarılı kayıttan sonra ham görüntü saklanmaz, başarısız denemelerin selfie dosyaları da silinir."
                ),
                FaqItem(
                    question = "Verilerim şifreli mi?",
                    answer = "Evet. Cihaz ile sunucu arasındaki tüm trafik uçtan uca şifrelidir. Yük AES-GCM ile, anahtar transferi RSA-OAEP ile yapılır; çözme yalnızca Enclave içinde gerçekleşebilir. Cihazınızdaki cüzdan verisi de Android Keystore destekli şifrelemeyle korunur."
                ),
                FaqItem(
                    question = "Telefonum çalınırsa bilgilerime erişilebilir mi?",
                    answer = "Cüzdandaki kart verisini çözmek için cihaz biyometrisiyle anahtar açma adımı gerekir; biyometri olmadan ham kart verisi okunamaz. Ek olarak Ayarlar'daki \"Biyometrik Kilitleme\" anahtarını açtığınızda uygulama her açılışta da biyometri sorar. Kart kaybolduysa Ayarlar > Kartımı Engelle ile sunucu tarafında da kartı kalıcı olarak engelleyebilirsiniz."
                )
            )
        ),

        FaqCategory(
            title = "📲  KULLANIM",
            titleColorHex = "#2E7D32",
            items = listOf(
                FaqItem(
                    question = "Bir partnerle paylaştığım doğrulamayı geri alabilir miyim?",
                    answer = "Evet. Ayarlar > İşlem Geçmişi ekranını açın, ilgili paylaşımı SAĞA kaydırın ve \"Kimlik Paylaşımını Geri Al\"ı onaylayın. Sunucu, partnerin sizinle ilişkilendirilmiş kayıtları silmesi için talep gönderir. (Bu özellik yalnızca geri alma desteği olan partnerlerde kullanılabilir.)"
                ),
                FaqItem(
                    question = "Kimlik doğrulama nasıl yapılır?",
                    answer = "Kartınızı bir kez ekledikten sonra iki yöntem var:\n• Cüzdan ekranındaki \"QR ile Giriş Yap\" butonu ile partnerin QR kodunu tarayın, ya da\n• Mobil tarayıcıdan partner sayfasındaki \"VerifyBlind ile Doğrula\" bağlantısına dokunun — uygulama otomatik açılır.\nAçılan onay ekranında partner adını ve istenen bilgileri görür, KVKK kutusunu işaretler, parmak izi/yüz ile onaylarsınız."
                ),
                FaqItem(
                    question = "Partner QR kodu nasıl çalışır?",
                    answer = "QR'ın içinde tek seferlik bir işlem kodu vardır. Uygulamanız QR'ı tarayınca partner bilgilerini sunucudan çekip Onay alt menüsünü açar; siz \"Onayla\" diyene kadar hiçbir cevap gönderilmez."
                ),
                FaqItem(
                    question = "Mobil sitelerde QR'sız nasıl çalışır?",
                    answer = "Telefonunuzda VerifyBlind uygulaması yüklüyse partner sitesindeki \"VerifyBlind ile Doğrula\" bağlantısı otomatik olarak uygulamayı çalıştırır. Onay alt menüsü doğrudan görünür."
                ),
                FaqItem(
                    question = "İşlem geçmişimi nerede görebilirim?",
                    answer = "Ayarlar ekranındaki \"İşlem Geçmişi\" satırından tüm kart ekleme, paylaşım ve geri alma kayıtlarını görüntüleyebilirsiniz. İlk açılışta biyometrik doğrulama istenir."
                ),
                FaqItem(
                    question = "Yedek nasıl alınır, yeni telefona nasıl taşınır?",
                    answer = "Ayarlar > Şifreli Yedekleme'ye gidip Dropbox veya Google Drive hesabınızla bağlantı kurun; ilk yedek otomatik alınır, sonraki değişiklikler de eşitlenir. Yeni telefonda uygulamayı kurup kart ekledikten sonra aynı bulut hesabıyla bağlandığınızda cüzdanınız geri yüklenir."
                )
            )
        ),

        FaqCategory(
            title = "🔧  SORUN GİDERME",
            titleColorHex = "#D67400",
            items = listOf(
                FaqItem(
                    question = "Telefonum kartın NFC çipini okumuyor. Ne yapmalıyım?",
                    answer = "Sırayla deneyin:\n✓ Telefon ayarlarından NFC'nin açık olduğundan emin olun (kart ekleme akışı kapalıysa zaten ayarlara yönlendirir).\n✓ Metal/karbon fiber kılıfları çıkarın.\n✓ Telefonu kartın üzerinde yavaşça kaydırıp anten konumunu bulun (genellikle arka yüzün orta veya üst kısmı).\n✓ Titreşim ve \"Okunuyor\" yazısı gelene kadar hareketsiz tutun."
                ),
                FaqItem(
                    question = "Yüz doğrulama sırasında nelere dikkat etmeliyim?",
                    answer = "✓ Dengeli aydınlatılmış bir ortamda olun, sırtınız ışığa dönük olmasın.\n✓ Gözlük, şapka veya maske takmayın.\n✓ Telefonu göz hizanızda dik tutun, sallamayın.\n✓ Ekrandaki yönergeyi (sola/sağa bak, göz kırp, gülümse) sakin biçimde uygulayın — toplam 5 hareket ve 30 saniyeniz var.\n✓ Çipteki fotoğrafa benzerlik %65 eşiğinin altındaysa adımın geçmesi mümkün olmaz; ortamı düzeltip tekrar deneyin."
                ),
                FaqItem(
                    question = "İnternet bağlantısı olmadan kullanabilir miyim?",
                    answer = "Kart ekleme ve doğrulama sunucuyla şifreli iletişim gerektirir, internet zorunludur. İnternet yokken cüzdandaki kayıtlı kart görseli ve işlem geçmişi görüntülenebilir ama yeni bir doğrulama yapılamaz."
                ),
                FaqItem(
                    question = "Kartım çalındı/kayboldu, ne yapmalıyım?",
                    answer = "Ayarlar > Kartımı Engelle satırına dokunun (yalnızca kayıtlı kart varken görünür) ve onaylayın. Kart, sunucu tarafında kalıcı olarak engellenir ve bu kartla bir daha hiç bir cihazda doğrulama yapılamaz. İşlem geri alınamaz. Uygulamayı kullanmak için yeni kimlik çıkarmanız veya farklı bir kimlik/pasaport kullanmanız gerekir."
                )
            )
        ),

        FaqCategory(
            title = "📦  DESTEK & KAPSAM",
            titleColorHex = "#6A1B9A",
            items = listOf(
                FaqItem(
                    question = "Yüz doğrulama neden gerekli?",
                    answer = "Yüz doğrulama, kimlik kartını fiziksel olarak elinde tutan kişinin gerçekten kart sahibi olduğunu kanıtlar. Böylece kartınızı ele geçiren biri sizin yerinize kayıt yaptıramaz."
                ),
                FaqItem(
                    question = "Hangi belgeler destekleniyor?",
                    answer = "NFC çipli yeni nesil Türkiye Cumhuriyeti kimlik kartları ve elektronik pasaportlar (MRZ + ICAO 9303 çip) desteklenir. Eski tip nüfus cüzdanı veya sürücü belgesi desteklenmez."
                ),
                FaqItem(
                    question = "Telefonumda NFC yoksa uygulamayı kullanabilir miyim?",
                    answer = "Hayır. Çipteki devlet imzalı veriyi okumak için NFC zorunludur. Kart ekleme başlamadan önce uygulama NFC'nin varlığını ve açık olup olmadığını kontrol edip sizi uyarır."
                )
            )
        )
    )
}
