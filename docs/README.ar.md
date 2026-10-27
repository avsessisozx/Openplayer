<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>مشغّل فيديو وموسيقى مجاني ومفتوح المصدر وخالٍ من الإعلانات لأندرويد.<br>تحكم بالإيماءات، مفككات ترميز HW / HW+ / SW، بحث وترجمة للترجمات، فيديو 360°، تشغيل عبر الشبكة SMB / FTP / WebDAV.</b>
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> ·
  <a href="README.tr.md">Türkçe</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.it.md">Italiano</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.pt-BR.md">Português</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <b>العربية</b>
</p>

---

<div dir="rtl">

## ما هو؟

OpenPlayer مشغّل فيديو مفتوح المصدر لأندرويد، بلا إعلانات ولا تتبع. يقدّم كبرنامج حر الميزات المفيدة في المشغّلات المغلقة الشائعة: إيماءات الشاشة، وإدارة الترجمات، واختيار مفكك الترميز، ومصادر الشبكة وغيرها. كل شيء مكتوب من الصفر.

التطبيق متاح بـ 12 لغة. الترجمات آلية ولم يراجعها ناطقون أصليون؛ نرحب بالتصحيحات.

## التنزيل

نزّل `OpenPlayer.apk` من [صفحة الإصدارات](https://github.com/avsessisozx/Openplayer/releases) وثبّته (أندرويد 7.0 أو أحدث). قد تحتاج إلى السماح بالتثبيت من مصادر غير معروفة.

> **نزّل التطبيق من صفحة الإصدارات فقط.** مفتاح التوقيع علني (مفتوح المصدر)؛ قد يكون ملف APK من مصدر آخر نسخة معدّلة تُثبَّت فوق التطبيق الأصلي.

> **الحالة: تجريبي (بيتا).** بُني واختُبر على CI ومحاكي، لكن لم يُختبر على نطاق واسع على أجهزة حقيقية. أبلغ عن المشكلات في [Issues](https://github.com/avsessisozx/Openplayer/issues).

## الميزات

- **مفككات الترميز:** HW وHW+ (امتداد FFmpeg: AC3 وE-AC3 وDTS وTrueHD وFLAC وOpus…) وSW، ويمكن التبديل أثناء التشغيل. محرك libVLC احتياطي للصيغ التي لا يفتحها ExoPlayer.
- **التشغيل:** سرعة 0.25x–4x، و2x بالضغط المطوّل، وتكرار A-B، ومؤقت النوم، ومعادل صوتي، وتعزيز الصوت حتى 200%، وتأخير الصوت، ومتابعة التشغيل من حيث توقفت، وPiP، والتشغيل في الخلفية، وقفل الشاشة وقفل الأطفال، ولقطات الشاشة.
- **360° / VR:** فيديو وصور 360°، ومستشعر الحركة، ووضع نظارة VR.
- **الإيماءات:** مناطق يسار/وسط/يمين (السطوع، الصوت، السرعة، التقديم، الترجمة، التكبير)، والسحب الأفقي للتقديم، والنقر المزدوج، والتكبير بالقرص.
- **الترجمات:** SRT وWebVTT وASS/SSA وSAMI وMicroDVD وغيرها؛ ضبط التأخير/السرعة، واكتشاف ترميز الأحرف تلقائيًا، والأنماط والخط الخاص؛ البحث في OpenSubtitles.com (يلزم [مفتاح API](https://www.opensubtitles.com/consumers) خاص بك)؛ **ترجمة الترجمات** (MyMemory وLibreTranslate وذكاء اصطناعي متوافق مع OpenAI).
- **الملفات:** المجلدات التي تحتوي فيديوهات فقط، وشبكة/قائمة، والفرز، والمجلدات المخفية والمجلد الخاص (المقفل)، والعلامات المرجعية، ومدير الوسائط.
- **الشبكة:** SMB وFTP وWebDAV؛ وعناوين HTTP/HTTPS/RTSP/HLS/DASH.
- **مشغّل الموسيقى** (اختياري): عرض حسب الأغاني والألبومات والفنانين والمجلدات، وقائمة انتظار، ومعادل صوتي، وتعديل الوسوم.

## القيود المعروفة

لا يدعم ASS/SSA الكامل (libass)؛ لا تُعرض الترجمات الصورية (VobSub وPGS)؛ لا يوجد Chromecast ولا إزالة التداخل؛ ولا تُكتشف ترجمات الفيديوهات عبر الشبكة تلقائيًا.

## البناء

يُبنى كل تغيير عبر **GitHub Actions**. محليًا: JDK 17 وAndroid SDK وأمر `gradle assembleDebug`.

## الخصوصية والأمان

لا إعلانات ولا تتبع ولا تحليلات. تُحفظ كلمات مرور خوادم الشبكة وOpenSubtitles **دون تشفير** في التخزين الخاص بالتطبيق. `app/openplayer-test.jks` مفتاح توقيع تجريبي عام. التفاصيل: [SECURITY.md](../SECURITY.md).

## الرخصة

GNU AGPLv3 ([LICENSE](../LICENSE)). المكونات المستخدمة: [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).

</div>
