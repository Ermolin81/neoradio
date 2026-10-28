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
