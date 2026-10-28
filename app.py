import os
import sys
import json
import time
import datetime
import urllib.request
import urllib.parse
import re
from pathlib import Path
import webview

# Allow autoplay and smooth cross-origin audio streaming in Edge WebView2
os.environ["WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"] = "--disable-web-security --autoplay-policy=no-user-gesture-required"

# Pywebview window drag configuration: strictly limit dragging to title bar
webview.settings['DRAG_REGION_SELECTOR'] = '.titlebar'
webview.settings['DRAG_REGION_DIRECT_TARGET_ONLY'] = True


def get_base_path() -> Path:
    """Returns the base directory for resources and config, handling PyInstaller bundle."""
    if getattr(sys, 'frozen', False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent


def get_resource_path(relative_path: str) -> str:
    """Get absolute path to resource, prefers local file next to executable/script, falls back to PyInstaller bundle."""
    local_file = get_base_path() / relative_path
    if local_file.exists():
        return str(local_file)
    if hasattr(sys, '_MEIPASS'):
        return os.path.join(sys._MEIPASS, relative_path)
    return os.path.join(str(Path(__file__).resolve().parent), relative_path)


DEFAULT_STATIONS = [
    {
        "id": "radio_ultra",
        "name": "Радио ULTRA",
        "genre": "Alternative Rock & Nu-Metal • Moscow",
        "category": "rock",
        "url": "https://nashe1.hostingradio.ru/ultra-128.mp3",
        "format": "MP3 128k",
        "icon": "⚡"
    },
    {
        "id": "radio_maximum",
        "name": "Радио MAXIMUM",
        "genre": "Rock, Modern Rock & Alternative • Moscow",
        "category": "rock",
        "url": "https://maximum.hostingradio.ru/maximum128.mp3",
        "format": "MP3 128k",
        "icon": "⚡"
    },
    {
        "id": "radio_rock_suomi",
        "name": "Radio Rock Suomi",
        "genre": "Rock / Heavy Metal • Supla",
        "category": "rock",
        "url": "https://aud-stream-radiorock.nm-elemental.nelonenmedia.fi/playlist.m3u8",
        "format": "HLS HD",
        "icon": "⚡"
    },
    {
        "id": "radio_city",
        "name": "Radio City (Finland)",
        "genre": "Classic Rock & Metal • Live",
        "category": "rock",
        "url": "https://streaming.radioplay.fi/radiocity/hml_128.mp3?direct=true",
        "format": "MP3 128k",
        "icon": "🎸"
    },
    {
        "id": "rock_antenne_classic_perlen",
        "name": "Rock Antenne Road Perlen",
        "genre": "Classic Rock & Highway Hits • Road Trip",
        "category": "rock",
        "url": "https://stream.rockantenne.de/classic-perlen/stream/mp3",
        "format": "MP3 128k",
        "icon": "🛣️"
    },
    {
        "id": "star_fm_from_hell",
        "name": "Star FM - From Hell",
        "genre": "Heavy Metal & Hard Rock • Berlin",
        "category": "rock",
        "url": "https://stream.starfm.de/fromhell/mp3-192",
        "format": "MP3 192k",
        "icon": "🔥"
    },
    {
        "id": "rock_antenne_heavy_metal",
        "name": "Rock Antenne Heavy Metal",
        "genre": "Heavy Metal & Power • Germany",
        "category": "rock",
        "url": "https://stream.rockantenne.de/heavy-metal/stream/mp3",
        "format": "MP3 128k",
        "icon": "🤘"
    },
    {
        "id": "ultra_dark_radio",
        "name": "Ultra Dark Radio",
        "genre": "Darkwave & NDT • Blutengel, Mantus",
        "category": "goth",
        "url": "https://stream.laut.fm/ultradarkradio",
        "format": "MP3 128k",
        "icon": "🩸"
    },
    {
        "id": "on_gothic_radio",
        "name": "0N Gothic Radio",
        "genre": "Gothic Rock & Synth-Goth • Germany",
        "category": "goth",
        "url": "https://0n-gothic.radionetz.de/0n-gothic.mp3",
        "format": "MP3 128k",
        "icon": "🕸️"
    },
    {
        "id": "rock_antenne_gothic",
        "name": "Rock Antenne Gothic",
        "genre": "Gothic Rock & Dark Alternative",
        "category": "goth",
        "url": "https://s6-webradio.rockantenne.de/gothic/stream/mp3",
        "format": "MP3 128k",
        "icon": "🦇"
    },
    {
        "id": "radio_bob_gothic",
        "name": "Radio BOB! Gothic Rock",
        "genre": "Gothic Rock & Post-Punk • Germany",
        "category": "goth",
        "url": "https://streams.radiobob.de/gothic/mp3-192/streams.radiobob.de/",
        "format": "MP3 192k",
        "icon": "🕯️"
    },
    {
        "id": "dark_metal_radio",
        "name": "Dark Metal-Radio",
        "genre": "Gothic Metal & Symphonic Dark",
        "category": "goth",
        "url": "https://stream.laut.fm/dark-metal-radio",
        "format": "MP3 128k",
        "icon": "⚰️"
    },
    {
        "id": "electrozombies",
        "name": "ElectroZombies",
        "genre": "Darkwave & Gothic Synth • Germany",
        "category": "goth",
        "url": "https://stream.laut.fm/electrozombies",
        "format": "MP3 128k",
        "icon": "☣️"
    },
    {
        "id": "synthwave_radio",
        "name": "Synthwave Radio 24/7",
        "genre": "Retro Synth & Cyberpunk 24/7",
        "category": "synth",
        "url": "https://stream.synthwaveradio.eu/listen/synthwaveradio.eu/radio.mp3",
        "format": "MP3 320k",
        "icon": "🌆"
    },
    {
        "id": "nightride_fm",
        "name": "Nightride FM",
        "genre": "Synthwave & Chillwave 320k Ultra HD",
        "category": "synth",
        "url": "https://stream.nightride.fm/nightride.mp3",
        "format": "MP3 320k",
        "icon": "🚀"
    },
    {
        "id": "nightride_datawave",
        "name": "Datawave (Darksynth)",
        "genre": "Darksynth, Cyberpunk & EBM 320k",
        "category": "synth",
        "url": "https://stream.nightride.fm/datawave.mp3",
        "format": "MP3 320k",
        "icon": "💾"
    },
    {
        "id": "darksynth_radio",
        "name": "Darksynth Radio",
        "genre": "Raw Dark Synth & Industrial Beats",
        "category": "synth",
        "url": "https://stream.laut.fm/darksynthradio",
        "format": "MP3 128k",
        "icon": "🕹️"
    },
    {
        "id": "deep_one_radio",
        "name": "DEEP ONE Radio",
        "genre": "Deep House, Atmospheric & Melodic Chill • deep1.ru",
        "category": "synth",
        "url": "https://stream.deep1.ru/deep1mp3",
        "format": "MP3 128k",
        "icon": "🎧"
    }
]


class MetadataService:
    """Fetches real-time Track, Artist, Album, and Cover Art for live streams in background thread."""
    def __init__(self):
        self._cache = {}
        self._cache_ttl = 25  # seconds
        self._in_progress = set()

    def get_track_info(self, station_id, station_url):
        """Non-blocking call: returns cached info immediately (0ms). Spawns background worker if needed."""
        now = time.time()
        if station_id in self._cache:
            ts, data = self._cache[station_id]
            if now - ts < self._cache_ttl:
                return data

        # Trigger background fetch if not already in progress
        if station_id not in self._in_progress:
            self._in_progress.add(station_id)
            import threading

            def _worker():
                try:
                    info = self._fetch_info(station_id, station_url)
                    self._cache[station_id] = (time.time(), info)
                except Exception:
                    pass
                finally:
                    self._in_progress.discard(station_id)

            t = threading.Thread(target=_worker, daemon=True)
            t.start()

        # If previous cached value exists (even slightly expired), return it immediately while refreshing
        if station_id in self._cache:
            return self._cache[station_id][1]

        # Instant 0ms placeholder so UI never freezes
        return {"artist": "", "title": "", "album": "", "art": "", "is_speech": False}

    def _fetch_info(self, station_id, station_url):
        # 1. Radio Rock Suomi (Nelonen Media / Supla real-time API)
        if station_id == "radio_rock_suomi":
            return self._fetch_supla_rock()

        # 2. Radio City Finland (Bauer Media real-time API)
        if station_id == "radio_city":
            return self._fetch_bauer_city()

        # 3. Radio ULTRA & MAXIMUM (official real-time broadcast metadata)
        if station_id in ["radio_ultra", "radio_maximum"]:
            slug = "ultra" if station_id == "radio_ultra" else "maximum"
            fmgid_info = self._fetch_fmgid(slug)
            if fmgid_info:
                return fmgid_info

        # 4. DEEP ONE Radio (official textfeed)
        if station_id == "deep_one_radio":
            deep_info = self._fetch_deep_one()
            if deep_info:
                return deep_info

        # 5. Nightride FM & Datawave
        if "nightride" in station_id or "datawave" in station_id:
            night_info = self._fetch_nightride("datawave" if "datawave" in station_id else "nightride")
            if night_info:
                return night_info

        # 6. German rock & gothic stations (official real-time broadcast API)
        RADIO_API_MAP = {
            "radio_bob_gothic": "radiobobgothic",
            "rock_antenne_heavy_metal": "antenneheavy",
            "rock_antenne_classic_perlen": "rockantenneclassic",
            "rock_antenne_gothic": "rockantennegothic",
            "on_gothic_radio": "ongothic",
            "star_fm_from_hell": "starfmfromhell"
        }
        if station_id in RADIO_API_MAP:
            api_info = self._fetch_radio_api_net(RADIO_API_MAP[station_id])
            if api_info:
                return api_info

        # 7. Laut.fm channels (official current song API)
        if station_url and "laut.fm" in station_url:
            laut_info = self._fetch_laut(station_url)
            if laut_info:
                return laut_info

        # 5. Direct ICY Metadata probe for Icecast/Shoutcast streams
        raw_artist = ""
        raw_title = ""
        if station_url:
            raw_artist, raw_title = self._read_icy(station_url)

        if not raw_title and not raw_artist:
            return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}

        raw_artist = raw_artist.strip()
        raw_title = raw_title.strip()

        # Filter out stream self-branding promos (e.g. "ROCK ANTENNE - Classic Perlen")
        promo_names = ["rock antenne", "star fm", "radio bob", "maximum", "suomiradio", "0n gothic"]
        art_low = raw_artist.lower()
        tit_low = raw_title.lower()
        for p in promo_names:
            if p in art_low and (p in tit_low or not tit_low or tit_low in ["live", "stream", "gothic", "classic perlen"]):
                return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}
            if p in tit_low and not raw_artist:
                return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}

        # Lookup iTunes Catalog for Album Name & High-Res Cover Art
        album_info = self._lookup_itunes(raw_artist, raw_title)
        if album_info:
            return album_info

        return {
            "artist": raw_artist,
            "title": raw_title,
            "album": "",
            "art": "",
            "is_speech": False
        }

    def _fetch_laut(self, url):
        """Official Laut.fm real-time API for current playing song."""
        try:
            station_slug = url.strip('/').split('/')[-1]
            api_url = f"https://api.laut.fm/station/{station_slug}/current_song"
            req = urllib.request.Request(api_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                data = json.loads(res.read().decode('utf-8'))
                artist = data.get('artist', {}).get('name', '').strip()
                title = data.get('title', '').strip()
                if not artist and not title:
                    return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}
                itunes_info = self._lookup_itunes(artist, title)
                if itunes_info:
                    return itunes_info
                return {
                    "artist": artist,
                    "title": title,
                    "album": data.get('album', ''),
                    "art": data.get('image', ''),
                    "is_speech": False
                }
        except Exception:
            pass
        return None

    def _fetch_fmgid(self, slug):
        """Fetches current track from meta.fmgid.com for Ultra and Maximum."""
        try:
            url = f"https://meta.fmgid.com/stations/{slug}/current.json"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                data = json.loads(res.read().decode('utf-8'))
                artist = (data.get('artist') or '').strip()
                title = (data.get('title') or '').strip()
                album = (data.get('album') or '').strip()
                cover = data.get('cover') or ''
                root = data.get('root') or ''
                art_url = f"{root}{cover}" if (root and cover) else cover
                if not artist and not title:
                    return None
                itunes = self._lookup_itunes(artist, title)
                if itunes:
                    if not itunes.get('art') and art_url:
                        itunes['art'] = art_url
                    return itunes
                return {"artist": artist, "title": title, "album": album, "art": art_url, "is_speech": False}
        except Exception:
            pass
        return None

    def _fetch_deep_one(self):
        """Fetches current track for DEEP ONE Radio."""
        try:
            url = "https://i.deep1.ru/playingnow/info/now_playing.txt"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                raw = res.read().decode('utf-8', errors='ignore').strip()
                if raw and ' - ' in raw:
                    a, t = raw.split(' - ', 1)
                    itunes = self._lookup_itunes(a.strip(), t.strip())
                    if itunes:
                        return itunes
                    return {"artist": a.strip(), "title": t.strip(), "album": "", "art": "", "is_speech": False}
        except Exception:
            pass
        return None

    def _fetch_nightride(self, slug):
        """Fetches current track for Nightride and Datawave from Icecast status-json.xsl."""
        try:
            url = "https://stream.nightride.fm/status-json.xsl"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                data = json.loads(res.read().decode('utf-8'))
                sources = data.get('icestats', {}).get('source', [])
                if not isinstance(sources, list):
                    sources = [sources]
                for src in sources:
                    lurl = src.get('listenurl', '')
                    if slug in lurl:
                        full = (src.get('title') or '').strip()
                        a = (src.get('artist') or '').strip()
                        t = full
                        if not a and ' - ' in full:
                            a, t = full.split(' - ', 1)
                        if a or t:
                            itunes = self._lookup_itunes(a.strip(), t.strip())
                            if itunes:
                                return itunes
                            return {"artist": a.strip(), "title": t.strip(), "album": "", "art": "", "is_speech": False}
        except Exception:
            pass
        return None

    def _fetch_radio_api_net(self, station_slug):
        """Fetches real-time track metadata from prod.radio-api.net for German rock stations."""
        try:
            url = f"https://prod.radio-api.net/stations/now-playing?stationIds={station_slug}"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=2.5) as r:
                data = json.loads(r.read().decode('utf-8'))
                if data and len(data) > 0:
                    raw_title = data[0].get('title', '').strip()
                    if raw_title and ' - ' in raw_title:
                        a, t = raw_title.split(' - ', 1)
                        a = a.strip()
                        t = t.strip()
                        if a and t:
                            itunes = self._lookup_itunes(a, t)
                            if itunes:
                                return itunes
                            return {
                                "artist": a,
                                "title": t,
                                "album": "",
                                "art": "",
                                "is_speech": False
                            }
        except Exception:
            pass
        return None

    def _fetch_supla_rock(self):
        """Official Nelonen Media real-time playlist API for Radio Rock (Channel 52)."""
        try:
            url = "https://supla-playlist.nm-services.nelonenmedia.fi/playlist?channel=52&limit=1"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                data = json.loads(res.read().decode('utf-8'))
                items = data.get('items', [])
                if items:
                    item = items[0]
                    artist = item.get('artist', '').strip()
                    title = item.get('song', '').strip()
                    start_ts = item.get('timestamp', 0)

                    if not artist and not title:
                        return {
                            "artist": "",
                            "title": "",
                            "album": "",
                            "art": "",
                            "is_speech": True,
                            "show": "Ведущие в студии • Radio Rock"
                        }

                    # Determine track duration via iTunes lookup
                    itunes_info = self._lookup_itunes(artist, title)
                    duration_sec = 330  # Default 5.5 min max song length
                    if itunes_info and itunes_info.get("duration"):
                        duration_sec = itunes_info["duration"]

                    elapsed = time.time() - start_ts if start_ts else 0
                    if start_ts and elapsed > (duration_sec + 20):
                        # Track has completed, host/DJ or ads active
                        return {
                            "artist": "",
                            "title": "",
                            "album": "",
                            "art": "",
                            "is_speech": True,
                            "show": "Ведущие в студии • Radio Rock"
                        }

                    if itunes_info:
                        return {
                            "artist": itunes_info.get("artist", artist),
                            "title": itunes_info.get("title", title),
                            "album": itunes_info.get("album", ""),
                            "art": itunes_info.get("art", ""),
                            "is_speech": False
                        }

                    return {
                        "artist": artist,
                        "title": title,
                        "album": "",
                        "art": "",
                        "is_speech": False
                    }
        except Exception:
            pass
        return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}

    def _fetch_bauer_city(self):
        """Official Bauer Media live now-playing API for Radio City (stationCode cf1)."""
        try:
            url = "https://listenapi.planetradio.co.uk/api9.2/stations_nowplaying/FI"
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=3) as res:
                data = json.loads(res.read().decode('utf-8'))
                for item in data:
                    if item.get('stationCode') == 'cf1':
                        np = item.get('stationNowPlaying', {})
                        show_title = item.get('stationOnAir', {}).get('episodeTitle', 'Ведущие в студии • Radio City')
                        artist = np.get('nowPlayingArtist', '').strip()
                        title = np.get('nowPlayingTrack', '').strip()
                        art = np.get('nowPlayingImage', '')
                        duration = np.get('nowPlayingDuration', 0)
                        start_time_str = np.get('nowPlayingTime', '')

                        # Check if empty (speech / show)
                        if not artist and not title:
                            return {
                                "artist": "",
                                "title": "",
                                "album": "",
                                "art": "",
                                "is_speech": True,
                                "show": show_title
                            }

                        # Check if track has already ended based on duration
                        if start_time_str and duration > 0:
                            try:
                                t = datetime.datetime.strptime(start_time_str, "%Y-%m-%d %H:%M:%S")
                                elapsed = (datetime.datetime.now() - t).total_seconds()
                                if elapsed > (duration + 20):
                                    return {
                                        "artist": "",
                                        "title": "",
                                        "album": "",
                                        "art": "",
                                        "is_speech": True,
                                        "show": show_title
                                    }
                            except Exception:
                                pass

                        # Query iTunes for album name and high-res cover art
                        itunes_info = self._lookup_itunes(artist, title)
                        if itunes_info:
                            return {
                                "artist": itunes_info.get("artist", artist),
                                "title": itunes_info.get("title", title),
                                "album": itunes_info.get("album", ""),
                                "art": itunes_info.get("art") or art,
                                "is_speech": False
                            }

                        return {
                            "artist": artist,
                            "title": title,
                            "album": "",
                            "art": art,
                            "is_speech": False
                        }
        except Exception:
            pass
        return {"artist": "", "title": "", "album": "", "art": "", "is_speech": True}

    def _read_icy(self, url):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Icy-MetaData': '1'})
            with urllib.request.urlopen(req, timeout=2.5) as r:
                metaint = int(r.headers.get('icy-metaint', 0))
                if not metaint:
                    return "", ""
                r.read(metaint)
                b = r.read(1)
                mlen = ord(b) * 16 if b else 0
                if mlen == 0:
                    r.read(metaint)
                    b = r.read(1)
                    mlen = ord(b) * 16 if b else 0
                if mlen > 0:
                    raw = r.read(mlen).decode('utf-8', errors='ignore')
                    m = re.search(r"StreamTitle='([^']*)'", raw)
                    if m:
                        st = m.group(1).strip()
                        st = re.sub(r'\[[a-zA-Z0-9_\-]+\]', '', st).strip()
                        if ' - ' in st:
                            a, t = st.split(' - ', 1)
                            return a.strip(), t.strip()
                        elif ' / ' in st:
                            a, t = st.split(' / ', 1)
                            return a.strip(), t.strip()
                        else:
                            return "", st
        except Exception:
            pass
        return "", ""

    def _lookup_itunes(self, artist, title):
        query = f"{artist} {title}".strip()
        if not query:
            return None
        url = f"https://itunes.apple.com/search?term={urllib.parse.quote(query)}&entity=song&limit=1"
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=1.5) as res:
                data = json.loads(res.read().decode('utf-8'))
                if data.get('resultCount', 0) > 0:
                    item = data['results'][0]
                    duration_ms = item.get('trackTimeMillis', 0)
                    return {
                        "artist": item.get('artistName', artist),
                        "title": item.get('trackName', title),
                        "album": item.get('collectionName', ''),
                        "art": item.get('artworkUrl100', '').replace('100x100bb', '250x250bb'),
                        "duration": int(duration_ms / 1000) if duration_ms else 0,
                        "is_speech": False
                    }
        except Exception:
            pass
        return None


class TrayManager:
    """Manages the Windows System Tray (NotifyIcon) using native WinForms."""
    def __init__(self, api):
        self.api = api
        self.tray = None
        self.form = None
        self.has_shown_balloon = False

    def setup(self, window):
        try:
            import clr
            clr.AddReference('System.Windows.Forms')
            clr.AddReference('System.Drawing')
            from System import Action
            from System.Windows.Forms import NotifyIcon, ContextMenuStrip, ToolStripMenuItem, MouseButtons, ToolTipIcon
            from System.Drawing import SystemIcons, Icon
            import webview.platforms.winforms as wf

            self.form = wf.BrowserView.instances.get(window.uid)
            if not self.form:
                return

            def _do_setup():
                self.tray = NotifyIcon()
                
                # Load custom high-res radio icon if present
                ico_path = get_resource_path("icon.ico")
                if os.path.exists(ico_path):
                    try:
                        custom_ico = Icon(ico_path)
                        self.tray.Icon = custom_ico
                        if self.form:
                            self.form.Icon = custom_ico
                    except Exception:
                        self.tray.Icon = self.form.Icon or SystemIcons.Application
                else:
                    self.tray.Icon = self.form.Icon or SystemIcons.Application

                self.tray.Text = "NeoRadio - FM Streamer"
                self.tray.Visible = True

                menu = ContextMenuStrip()
                item_show = ToolStripMenuItem("Открыть NeoRadio")
                item_show.Click += lambda s, e: self.api.restore()

                item_exit = ToolStripMenuItem("Выход")
                item_exit.Click += lambda s, e: self.api.quit_completely()

                menu.Items.Add(item_show)
                menu.Items.Add(item_exit)
                self.tray.ContextMenuStrip = menu

                def on_mouse_click(s, e):
                    if e.Button == MouseButtons.Left:
                        self.api.restore()

                self.tray.MouseClick += on_mouse_click

            if self.form.InvokeRequired:
                self.form.Invoke(Action(_do_setup))
            else:
                _do_setup()
        except Exception as e:
            print(f"Error setting up System Tray: {e}")

    def show_balloon(self):
        try:
            if self.tray and not self.has_shown_balloon:
                self.has_shown_balloon = True
                from System import Action
                from System.Windows.Forms import ToolTipIcon

                def _tip():
                    try:
                        self.tray.ShowBalloonTip(2000, "NeoRadio", "Радио свернуто в трей и играет в фоне", ToolTipIcon.Info)
                    except Exception:
                        pass

                if self.form and self.form.InvokeRequired:
                    self.form.Invoke(Action(_tip))
                else:
                    _tip()
        except Exception:
            pass

    def dispose(self):
        try:
            if self.tray:
                from System import Action

                def _clean():
                    try:
                        self.tray.Visible = False
                        self.tray.Dispose()
                    except Exception:
                        pass

                if self.form and self.form.InvokeRequired:
                    try:
                        self.form.Invoke(Action(_clean))
                    except Exception:
                        pass
                else:
                    _clean()
                self.tray = None
        except Exception:
            pass


class RadioApi:
    def __init__(self):
        self._window = None
        self.stations_file = get_base_path() / "stations.json"
        self.tray_manager = TrayManager(self)
        self.metadata_service = MetadataService()

    def set_window(self, window):
        self._window = window

    def init_tray(self):
        if self._window:
            self.tray_manager.setup(self._window)

    def get_stations(self):
        if self.stations_file.exists():
            try:
                with open(self.stations_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list) and len(data) > 0:
                        return data
            except Exception as e:
                print(f"Error loading stations: {e}")
        self.save_stations(DEFAULT_STATIONS)
        return list(DEFAULT_STATIONS)

    def save_stations(self, stations):
        try:
            with open(self.stations_file, "w", encoding="utf-8") as f:
                json.dump(stations, f, indent=2, ensure_ascii=False)
            return True
        except Exception as e:
            print(f"Error saving stations: {e}")
            return False

    def get_track_info(self, station_id, station_url):
        """Asynchronously called from JavaScript frontend to get now playing track, artist, album, art."""
        try:
            return self.metadata_service.get_track_info(station_id, station_url)
        except Exception as e:
            print(f"Error getting track info: {e}")
            return {"artist": "", "title": "", "album": "", "art": ""}

    def get_favorites(self):
        fav_file = get_base_path() / "favorites.json"
        if fav_file.exists():
            try:
                with open(fav_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, list):
                        return data
            except Exception as e:
                print(f"Error loading favorites: {e}")
        return []

    def save_favorites(self, fav_ids):
        fav_file = get_base_path() / "favorites.json"
        try:
            with open(fav_file, "w", encoding="utf-8") as f:
                json.dump(fav_ids, f, indent=2, ensure_ascii=False)
            return True
        except Exception as e:
            print(f"Error saving favorites: {e}")
            return False

    def minimize(self):
        if self._window:
            self._window.minimize()

    def close(self):
        """Minimize to system tray on red button click so playback continues uninterrupted."""
        if self._window:
            self._window.hide()
            self.tray_manager.show_balloon()

    def restore(self):
        """Restore window from system tray and bring to foreground."""
        if self._window:
            self._window.show()
            self._window.restore()
            if self.tray_manager and self.tray_manager.form:
                try:
                    from System import Action
                    def _focus():
                        try:
                            self.tray_manager.form.WindowState = self.tray_manager.form.WindowState.Normal
                            self.tray_manager.form.BringToFront()
                            self.tray_manager.form.Activate()
                        except Exception:
                            pass
                    if self.tray_manager.form.InvokeRequired:
                        self.tray_manager.form.Invoke(Action(_focus))
                    else:
                        _focus()
                except Exception:
                    pass

    def quit_completely(self):
        """Cleanly exit application from tray context menu."""
        if self.tray_manager:
            self.tray_manager.dispose()
        if self._window:
            self._window.destroy()


SINGLE_INSTANCE_PORT = 42019


def activate_existing_instance() -> bool:
    """Check if another instance of NeoRadio is running. If so, tell it to show and exit."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.8)
    try:
        s.connect(('127.0.0.1', SINGLE_INSTANCE_PORT))
        s.sendall(b'SHOW\n')
        s.close()
        return True
    except Exception:
        return False


def start_instance_listener(api: 'RadioApi'):
    """Start background socket server to listen for activation requests from subsequent instances."""
    import socket
    import threading

    def _listen():
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(('127.0.0.1', SINGLE_INSTANCE_PORT))
            server.listen(5)
        except Exception:
            return

        while True:
            try:
                conn, _ = server.accept()
                data = conn.recv(64)
                conn.close()
                if b'SHOW' in data:
                    api.restore()
            except Exception:
                break

    t = threading.Thread(target=_listen, daemon=True)
    t.start()


def main():
    if activate_existing_instance():
        sys.exit(0)

    api = RadioApi()
    start_instance_listener(api)

    html_file = get_resource_path("index.html")

    window = webview.create_window(
        title="NeoRadio",
        url=html_file,
        width=690,
        height=830,
        resizable=False,
        frameless=True,
        easy_drag=False,
        js_api=api,
        background_color="#0B0C10"
    )
    api.set_window(window)

    def on_loaded():
        api.init_tray()
        try:
            window.evaluate_js('if (typeof loadStationsFromBackend === "function") loadStationsFromBackend();')
        except Exception:
            pass

    def on_closed():
        api.quit_completely()

    window.events.loaded += on_loaded
    window.events.closed += on_closed

    # Use native Microsoft Edge WebView2 on Windows
    webview.start(gui="edgechromium", debug=False)


if __name__ == "__main__":
    main()
