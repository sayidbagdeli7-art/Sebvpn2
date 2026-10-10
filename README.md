# AutoVPN — راهنمای بیلد

این یک پروژه‌ی Android Studio (Kotlin + Jetpack Compose) هست که:
- دو لینک سابسکریپشن رو که دادی از قبل داخل کد (`SubscriptionManager.kt`) گذاشته شده.
- با هر بار زدن دکمه‌ی «اتصال»: هر دو سابسکریپشن رو تازه دانلود می‌کنه، تمام کانفیگ‌های vmess/vless/trojan/ss داخلشون رو پارس می‌کنه، پینگ واقعی هر کدوم رو با خودِ هسته‌ی Xray می‌گیره، و کم‌پینگ‌ترین رو خودکار وصل می‌کنه.
- فقط یک دکمه‌ی گرد وسط صفحه داره (اتصال / قطع اتصال) + وضعیت + اسم سرور + پینگ.

## چیزی که خودم نتونستم اینجا انجام بدم (و باید توی Android Studio انجام بدی)

من نمی‌تونم در این محیط APK کامپایل‌شده بسازم چون به Android SDK/NDK دسترسی ندارم.
کد کامل و آماده رو نوشتم؛ برای بیلد گرفتن این مراحل رو انجام بده:

### ۱) دانلود هسته‌ی Xray (فایل aar)
1. برو به: https://github.com/2dust/AndroidLibXrayLite/releases
2. آخرین ریلیز رو باز کن و فایل `libv2ray.aar` رو دانلود کن.
3. اون رو داخل پوشه‌ی `app/libs/` (کنار همین پروژه) بذار — دقیقاً با همین اسم `libv2ray.aar`.
4. فایل placeholder (`PUT_libv2ray.aar_HERE.txt`) رو حذف کن.

### ۲) باز کردن در Android Studio
1. پوشه‌ی پروژه (`AutoVpnApp`) رو با Android Studio (نسخه‌ی جدید، Gradle 8+) باز کن.
2. بذار Gradle Sync کامل بشه (به اینترنت نیاز داره تا Dependencyها مثل OkHttp، Compose و... دانلود بشن).
3. بعد از Sync، روی نام کلاس‌های `Libv2ray` و `CoreController` در فایل
   `app/src/main/java/com/autovpn/app/vpn/VpnTunnelService.kt` دوبار کلیک/Ctrl+Click کن تا مطمئن بشی
   اسم متدهای واقعی داخل aar (که gomobile ساخته) دقیقاً با چیزی که نوشتم یکی هست
   (`newCoreController`, `startLoop`, `stopLoop`, `measureOutboundDelay`, `CoreCallbackHandler`).
   این‌ها طبق مستندات رسمی نوشته شدن ولی ممکنه نسخه‌ی جدیدتر یه‌کم فرق کنه؛ اگر فرق داشت
   فقط اسم متد رو در همون دو فایل (`VpnTunnelService.kt` و `PingTester.kt`) اصلاح کن.

### ۳) گرفتن APK
- از منوی Android Studio: `Build > Build Bundle(s) / APK(s) > Build APK(s)`
- یا از ترمینال: `./gradlew assembleDebug` (بعد از دانلود Gradle wrapper از طریق Android Studio)

### ۴) تست روی گوشی
- APK رو نصب کن، دکمه‌ی اتصال رو بزن، اجازه‌ی VPN رو تایید کن.
- بار اول ممکنه چند ثانیه طول بکشه (دانلود سابسکریپشن + پینگ گرفتن از همه‌ی سرورها).

## ساختار پروژه

```
app/src/main/java/com/autovpn/app/
  MainActivity.kt                  UI تک‌دکمه‌ای (Compose)
  model/ProxyConfig.kt              مدل داده‌ی هر سرور
  parser/ShareLinkParser.kt         تبدیل لینک vmess/vless/trojan/ss به JSON خروجی Xray
  subscription/SubscriptionManager.kt   دانلود و استخراج لینک‌ها از دو سابسکریپشن
  xray/XrayConfigBuilder.kt         ساخت کانفیگ کامل Xray
  xray/PingTester.kt                تست پینگ واقعی همه‌ی سرورها با هسته Xray
  vpn/VpnTunnelService.kt           سرویس VPN اندروید که واقعاً تونل رو برقرار می‌کنه
```

## نکات مهم

- **لینک‌های سابسکریپشن** رو هر جا خواستی عوض کنی، فقط لیست
  `SubscriptionManager.SUBSCRIPTION_URLS` رو ویرایش کن.
- برنامه از اپلیکیشن خودش رو از تونل VPN مستثنی می‌کنه (`addDisallowedApplication`) تا
  اتصال خروجی خودِ Xray به لوپ نیفته — این روش ساده و رایج جایگزین callback قدیمی‌تر `protect()` هست.
- اگر می‌خوای امنیت بیشتر (imprisonment از اپ‌های دیگه، DNS اختصاصی، Kill-switch و ...) اضافه کنی،
  باید تنظیمات `VpnService.Builder` رو در `VpnTunnelService.kt` گسترش بدی.
- این پروژه یک نسخه‌ی مقدماتیه، نه یک محصول production-ready؛ مثل هر اپ VPN واقعی، قبل از استفاده‌ی
  جدی حتماً روی چند تا گوشی تست کن و نسخه‌ی aar رو به‌روز نگه دار.
