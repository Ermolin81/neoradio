# NeoRadio — Проектная память и статус

## 1. О проекте
- **Назначение:** Высокопроизводительный онлайн-радиоплеер для Windows (десктоп, трей, глобальные горячие клавиши) и смартфонов (PWA / Mobile Web).
- **Стек:** Python 3.10 + pywebview + pystray (десктоп), чистый HTML5/ES6/CSS3 + hls.js + Web Audio API (мобильный).
- **Публичный адрес мобильной версии:** `https://neoradio.shipstatic.com` (бессрочный домен, аккаунт Max78 / kuolematon812@gmail.com).

## 2. Архитектура и станции (18 активных)
- **Рок & Метал (7):**
  - Радио ULTRA (Москва, meta.fmgid.com)
  - Радио MAXIMUM (Москва, meta.fmgid.com)
  - Radio Rock Suomi (Supla Nelonen Media)
  - Radio City Finland (Bauer Media)
  - Rock Antenne Road Perlen (prod.radio-api.net)
  - Star FM - From Hell (prod.radio-api.net)
  - Rock Antenne Heavy Metal (prod.radio-api.net)
- **Готика & Dark (6):**
  - Ultra Dark Radio (api.laut.fm)
  - 0N Gothic Radio (prod.radio-api.net)
  - Rock Antenne Gothic (prod.radio-api.net)
  - Radio BOB! Gothic Rock (prod.radio-api.net)
  - Dark Metal-Radio (api.laut.fm)
  - ElectroZombies (api.laut.fm)
- **Синт & Deep (5):**
  - Synthwave Radio 24/7 (synthwaveradio.eu)
  - Nightride FM (stream.nightride.fm / status-json.xsl)
  - Datawave Darksynth (stream.nightride.fm / status-json.xsl)
  - Darksynth Radio (api.laut.fm)
  - DEEP ONE Radio (stream.deep1.ru / i.deep1.ru textfeed)

## 3. Ключевые решения и уроки (Troubleshooting)
1. **Буферизация и обрывы на мобильном (Root Cause и аудит от Argon):**
   - **Спам в Android MediaSession & Palette API:** Ранее navigator.mediaSession.metadata пересоздавался каждые 10 секунд с URL из iTunes. Android при каждом создании заново качал битмап, декодировал в UI-потоке, пересчитывал цветовую палитру шторки (Material You) и вызывал AudioTrack underrun. Решение: строгая дедупликация (`_lastMediaTitle === title && _lastMediaArtist === artist && _lastMediaArt === art`). Если трек не изменился, MediaSession вообще не трогается.
   - **Кэширование iTunes в памяти:** Поиск iTunes вынесен в кэш по ключу `${artist} - ${title}`. Запрос в Apple API отправляется строго один раз при смене трека, а не каждые 12 секунд. На смартфонах включен щадящий интервал 18с (30с при заблокированном экране).
   - **Конфликт HLS MSE и MP3:** При переключении с HLS (.m3u8) на MP3 вызывался hls.detachMedia(), который внутри hls.js асинхронно сбрасывал player.src и вызывал load(), сталкиваясь с новым player.src = url. Решение: жесткий сброс `player.pause(); if (!wasHls) { player.removeAttribute('src'); player.load(); }`.
   - **Мертвый сокет при паузе Live-потока:** Вызов `player.pause()` на живом Icecast не закрывал сокет, буфер забивался, и сервер рубил коннект по client-timeout. При снятии с паузы телефон играл 2 секунды старого буфера и падал. Решение: `player.removeAttribute('src'); player.load();` при паузе и остановке + добавление параметра `?_t=timestamp` к MP3 URL для чистого коннекта.
   - **Слушатель системной паузы (AudioFocus loss):** При входящих звонках, подсказках навигатора в авто или голосовых в Telegram Android ставит <audio> на паузу. Добавлен слушатель `player.addEventListener('pause')`, синхронизирующий стейт плеера.
   - **Авто-реконнект при смене базовых станций:** Слушатели `ended`, `stalled` и `error` автоматически перезапускают воспроизведение через 2 секунды.
2. **Отображение треков для всех радиостанций (включая Radio BOB и немецкие):**
   - `prod.radio-api.net` не отдавал CORS-заголовок для сторонних веб-доменов. Внедрен автоматический фоллбэк на `allorigins.win`, благодаря чему Radio BOB, Rock Antenne, Star FM и 0N Gothic теперь корректно отображают играющий трек и на мобильном сайте.
3. **Bluetooth в авто (руль и магнитола):**
   - Кнопки переключения треков на руле поддержаны через `nexttrack`, `previoustrack`, `seekforward`, `seekbackward`, `skipad` и глобальные слушатели `keydown` (`MediaTrackNext`, `MediaTrackPrevious`, `MediaPlayPause`). Переключение закольцовано по всему списку станций независимо от состояния фильтра.
4. **Сборка и развертывание:**
   - Десктоп: `NeoRadio.exe` (9.2 МБ, PyInstaller, Python 3.10).
   - Мобильный веб: `https://neoradio.shipstatic.com` (ShipStatic).
   - Нативный Android APK для авто: репозиторий `https://github.com/Ermolin81/neoradio`, автоматическая облачная сборка через GitHub Actions (`.github/workflows/build-apk.yml`).

## 4. Нативное Android-приложение (ExoPlayer + Media3) для авто
- **Причина перехода с PWA:** В мобильном браузере при движении в авто сотовые вышки переключаются (TCP half-open), а голосовые подсказки Яндекс.Навигатора глушат стрим без возврата. Браузер не умеет аппаратный Ducking (приглушение).
- **Реализация:**
  - Гибридная архитектура на Capacitor 6: веб-интерфейс крутится в WebView, но воспроизведение звука на Android делегировано нативному Kotlin-сервису `RadioService` (`MediaSessionService`).
  - `RadioService.kt`:
    1. Буфер воспроизведения 45 секунд в памяти (`DefaultLoadControl`), старт после 2.5 сек — мертвые зоны 4G и туннели проезжаются без единого заикания.
    2. Автоматический ретрай сокета до 10 попыток с задержкой 1.5 сек при смене вышек LTE.
    3. Аппаратный Ducking: `AudioAttributes` с `C.USAGE_MEDIA` и `handleAudioFocus = true`. Подсказки навигатора плавно опускают громкость радио до 20% и возвращают обратно без остановки потока.
    4. Пауза при отключении Bluetooth (`setHandleAudioBecomingNoisy(true)`).
    5. Закольцованный плейлист (`player.repeatMode = Player.REPEAT_MODE_ALL`) и перехватчик `MediaSession.Callback` (`onPlayerCommandRequest`): команды `COMMAND_SEEK_TO_PREVIOUS` и `COMMAND_SEEK_TO_NEXT` с кнопок руля (AVRCP) всегда принудительно вызывают `seekToPreviousMediaItem()` / `seekToNextMediaItem()`, устраняя стандартный баг ExoPlayer, когда нажатие кнопки «Назад» на стриме длиннее 3 секунд лишь перезапускало текущую станцию.
    6. Умный реконнект в туннелях: мониторинг `ConnectivityManager.NetworkCallback` (NET_CAPABILITY_INTERNET) с автоматическим переподключением (`seekToDefaultPosition()`, `prepare()`, `play()`) при восстановлении 4G/LTE; защита от плотного цикла ошибок при отсутствии соединения (`onPlayerError`).
  - `NativeRadioPlugin.kt` & `index.html`:
    1. Исключено задвоение звука (эхо на Радио ULTRA): в Android-приложении HTML5 `<audio>` и `navigator.mediaSession` наглухо отключены (`if (!isNativeAndroid)` с fallback-детекцией Capacitor WebView), Chromium не создает конкурирующую медиа-сессию.
    2. Метаданные (название трека, артист, обложка) обновляются через `ctrl.replaceMediaItem(index, updatedItem)` без рестарта аудио-потока и сокета Icecast (с очисткой обложки при отсутствии арта).
    3. Двусторонняя синхронизация и защита от зацикливания: отслеживание `lastMediaItemIndex` фильтрует служебные события `PLAYLIST_CHANGED`, исключая сброс метаданных, ложный статус «В эфире» при холодном старте и бесконечный цикл опроса; защита от race condition при быстром переключении станций.
  - Сборка: GitHub Actions компилирует чистый `app-debug.apk` в облаке за 1.5–2 минуты без локальной установки Android Studio на ПК. Готовый APK скачивается из артефактов сборки.
- **Готовый файл:** `P:\online_radio\NeoRadio-Car.apk` и `C:\Users\Max\Desktop\NeoRadio-Car.apk` (7.6 МБ).
