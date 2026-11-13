package se.rob.pixelpanel

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Page specs for Pixel Settings. Texts are Pixel's own Swedish strings (AOSP values-sv). */
object SettingsPages {

    class Spec(val id: String, val title: String, val items: List<Item>)

    // m3_ref_palette (Material extended palette tokens) used by homepage_*_background / _foreground
    private val bg90 = mapOf(
        "blueV" to 0xffbde9ff, "blue" to 0xffd0e4ff, "pink" to 0xffffd8ef, "orange" to 0xffffdcc3, "yellow" to 0xffffe07c,
        "green" to 0xffbeefbb, "grey" to 0xffe3e3e3, "cyan" to 0xffacedff, "red" to 0xffffdadc, "purple" to 0xffeedcfe)
    private val bg80 = mapOf(
        "blueV" to 0xff67d4ff, "blue" to 0xffa1c9ff, "pink" to 0xffffaee4, "orange" to 0xffffb683, "yellow" to 0xfffcbd00,
        "green" to 0xff80da88, "grey" to 0xffc7c7c7, "cyan" to 0xff60d5f3, "red" to 0xffffb3ae, "purple" to 0xffd9bafd)
    private val fg30 = mapOf(
        "blueV" to 0xff004d68, "blue" to 0xff04409f, "pink" to 0xff8d0053, "orange" to 0xff753403, "yellow" to 0xff6d3a01,
        "green" to 0xff00522c, "grey" to 0xff474747, "cyan" to 0xff004e5d, "red" to 0xff8a1a16, "purple" to 0xff5629a4)

    private fun homeRow(a: PixelSettingsActivity, title: String, icon: Int, color: String, summary: (() -> String?)?, onClick: () -> Unit): Row {
        val bg = ((if (a.sc.night) bg80 else bg90)[color] ?: 0xffe3e3e3).toInt()
        val fg = (fg30[color] ?: 0xff474747).toInt()
        val d = a.getDrawable(icon)!!.mutate().apply { setTint(fg) }
        return Row(title, summary, d, bg, homepage = true, onClick = onClick)
    }

    private fun i(action: String) = Intent(action)
    private fun cls(pkg: String, c: String) = Intent().setClassName(pkg, c)

    fun home(a: PixelSettingsActivity) = PageView(a, null, homeItems(a), isHome = true)

    fun open(a: PixelSettingsActivity, id: String) { byId(a, id)?.let { a.push(it) } }
    fun byId(a: PixelSettingsActivity, id: String): PageView? = spec(a, id)?.let { PageView(a, it.title, it.items) }

    private fun homeItems(a: PixelSettingsActivity): List<Item> = listOf(
        Category(null),
        homeRow(a, "Nätverk och internet", R.drawable.pset_network, "blueV", { networkSummary(a) }) { open(a, "network") },
        homeRow(a, "Anslutna enheter", R.drawable.pset_connected_device, "blueV", { "Bluetooth och parkoppling" }) { open(a, "connected") },
        Category(null),
        homeRow(a, "Appar", R.drawable.pset_apps, "blue", { "Senaste apparna, standardappar" }) { open(a, "apps") },
        homeRow(a, "Aviseringar", R.drawable.pset_notification, "pink", { "Aviseringshistorik och konversationer" }) { open(a, "notifs") },
        homeRow(a, "Ljud och vibration", R.drawable.pset_sound, "pink", { "Volym, haptik, Stör ej" }) { open(a, "sound") },
        homeRow(a, "Lägen", R.drawable.pset_modes, "pink", { if (NotifStore.dndOn()) "Stör ej är på" else "Stör ej, läggdags och mer" }) {
            a.openSystem(i("android.settings.ZEN_MODE_SETTINGS"), i(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
        },
        homeRow(a, "Skärm och tryck", R.drawable.pset_display, "orange", { "Mörkt tema, teckenstorlek, ljusstyrka" }) { open(a, "display") },
        homeRow(a, "Bakgrund och stil", R.drawable.pset_wallpaper, "orange", { "Färger, ikoner med tema, apprutnät" }) { open(a, "style") },
        Category(null),
        homeRow(a, "Lagring", R.drawable.pset_storage, "yellow", { storageSummary() }) { open(a, "storage") },
        homeRow(a, "Batteri", R.drawable.pset_battery, "green", { batterySummary(a) }) { open(a, "battery") },
        homeRow(a, "System", R.drawable.pset_system_dashboard, "grey", { "Språk, rörelser, tid och säkerhetskopiering" }) { open(a, "system") },
        homeRow(a, "Om telefonen", R.drawable.pset_about, "grey", { deviceName(a) }) { open(a, "about") },
        Category(null),
        homeRow(a, "Säkerhet och integritet", R.drawable.pset_safety_center, "red", { "Appsäkerhet, enhetslås och behörigheter" }) {
            a.openSystem(i("android.settings.SAFETY_CENTER"), i(Settings.ACTION_SECURITY_SETTINGS))
        },
        homeRow(a, "Plats", R.drawable.pset_location, "cyan", { if (locationOn(a)) "På" else "Av" }) { open(a, "location") },
        homeRow(a, "Säkerhet och nödläge", R.drawable.pset_emergency, "red", { "SOS-larm, medicinska uppgifter och varningar" }) {
            a.openSystem(i("android.settings.EMERGENCY_SETTINGS"), cls("com.android.settings", "com.android.settings.Settings\$EmergencyDashboardActivity"),
                i(Settings.ACTION_SECURITY_SETTINGS))
        },
        homeRow(a, "Lösenord, nycklar och konton", R.drawable.pset_accounts, "cyan", { "Sparade lösenord, autofyll, synkroniserade konton" }) {
            a.openSystem(i(Settings.ACTION_SYNC_SETTINGS))
        },
        homeRow(a, "Digitalt välmående och föräldrakontroller", R.drawable.pset_supervision, "cyan", { "Skärmtid, apptimer, läggdagsscheman" }) {
            a.openSystem(a.packageManager.getLaunchIntentForPackage("com.samsung.android.forest") ?: i(Settings.ACTION_SETTINGS),
                i("com.google.android.apps.wellbeing.action.DASHBOARD"))
        },
        Row("Google", { "Tjänster och inställningar" }, GoogleG(a), if (a.sc.night) 0xffc7c7c7.toInt() else 0xffe3e3e3.toInt(), homepage = true) {
            a.openSystem(cls("com.google.android.gms", "com.google.android.gms.app.settings.GoogleSettingsLink"),
                cls("com.google.android.gms", "com.google.android.gms.app.settings.GoogleSettingsActivity"),
                i(Settings.ACTION_SYNC_SETTINGS))
        },
        Category(null),
        homeRow(a, "Tillgänglighet", R.drawable.pset_accessibility, "purple", { "Skärm, interaktion och ljud" }) {
            a.openSystem(i(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        homeRow(a, "Tips och support", R.drawable.pset_support, "purple", { "Hjälpartiklar, telefon och chatt" }) {
            a.openSystem(a.packageManager.getLaunchIntentForPackage("com.samsung.android.voc") ?: i(Settings.ACTION_DEVICE_INFO_SETTINGS))
        },
    )

    fun spec(a: PixelSettingsActivity, id: String): Spec? = when (id) {
        "network" -> network(a)
        "connected" -> connected(a)
        "connprefs" -> connectionPrefs(a)
        "sound" -> sound(a)
        "display" -> display(a)
        "battery" -> battery(a)
        "saver" -> batterySaver(a)
        "batteryinfo" -> batteryInfo(a)
        "about" -> about(a)
        "system" -> system(a)
        "location" -> location(a)
        "datasaver" -> dataSaver(a)
        "style" -> ThemePicker.spec(a)
        "apps" -> SubPages.apps(a)
        "allapps" -> SubPages.allApps(a)
        "notifs" -> SubPages.notifications(a)
        "appnotifs" -> SubPages.appNotifs(a)
        "storage" -> SubPages.storage(a)
        else -> null
    }
    val allIds = listOf("network", "connected", "connprefs", "sound", "display", "battery", "saver", "batteryinfo", "about", "system", "location", "datasaver", "style", "apps", "notifs", "storage")

    // ---- settings access ------------------------------------------------------------------------

    private fun sysInt(c: Context, k: String, d: Int = 0) = try { Settings.System.getInt(c.contentResolver, k, d) } catch (_: Exception) { d }
    private fun secInt(c: Context, k: String, d: Int = 0) = try { Settings.Secure.getInt(c.contentResolver, k, d) } catch (_: Exception) { d }
    private fun globInt(c: Context, k: String, d: Int = 0) = try { Settings.Global.getInt(c.contentResolver, k, d) } catch (_: Exception) { d }

    /** Writes a setting directly when allowed, otherwise through Shizuku. */
    private fun put(a: PixelSettingsActivity, table: String, key: String, v: String, after: (() -> Unit)? = null) {
        val cr = a.contentResolver
        val ok = try {
            when (table) {
                "global" -> Settings.Global.putString(cr, key, v)
                "secure" -> Settings.Secure.putString(cr, key, v)
                else -> if (Settings.System.canWrite(a)) Settings.System.putString(cr, key, v) else false
            }
        } catch (_: Exception) { false }
        if (ok) { after?.invoke(); return }
        a.shell("settings put $table $key '$v'", after)
    }
    private fun toggle(a: PixelSettingsActivity, table: String, key: String, def: Int = 0) = Toggle(
        get = { when (table) { "global" -> globInt(a, key, def); "secure" -> secInt(a, key, def); else -> sysInt(a, key, def) } != 0 },
        set = { on -> put(a, table, key, if (on) "1" else "0") })

    // ---- Nätverk och internet --------------------------------------------------------------------

    private fun networkSummary(a: Context): String {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            globInt(a, Settings.Global.AIRPLANE_MODE_ON) == 1 -> "Flygplansläge är på"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wifi, mobil, surfzon"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobil, wifi, surfzon"
            else -> "Ej ansluten"
        }
    }

    private fun network(a: PixelSettingsActivity) = Spec("network", "Nätverk och internet", listOf(
        Category(null),
        Row("Internet", { internetSummary(a) }, icon(a, R.drawable.pset_network)) {
            a.openSystem(i(Settings.ACTION_WIFI_SETTINGS))
        },
        Row("Samtal och sms", { telephonyName(a) }, sym(a, Sym.CALL)) {
            a.openSystem(i(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), i(Settings.ACTION_WIRELESS_SETTINGS))
        },
        Row("SIM-kort", { telephonyName(a) }, sym(a, Sym.SIGNAL_CELLULAR_ALT)) {
            a.openSystem(i("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"), i(Settings.ACTION_DATA_ROAMING_SETTINGS))
        },
        Row("Flygplansläge", null, sym(a, Sym.AIRPLANEMODE_ACTIVE), toggle = Toggle(
            get = { globInt(a, Settings.Global.AIRPLANE_MODE_ON) == 1 },
            set = { on -> a.shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}") })),
        Row("Surfzon och internetdelning", { "Av" }, sym(a, Sym.WIFI_TETHERING)) {
            a.openSystem(cls("com.android.settings", "com.android.settings.TetherSettings"), i(Settings.ACTION_WIRELESS_SETTINGS))
        },
        Category(null),
        Row("Databesparing", { if (dataSaverOn(a)) "På" else "Av" }, sym(a, Sym.DATA_SAVER)) { open(a, "datasaver") },
        Row("VPN", { "Inga" }, sym(a, Sym.VPN_KEY)) { a.openSystem(i(Settings.ACTION_VPN_SETTINGS)) },
        Row("Privat DNS", { privateDnsSummary(a) }, sym(a, Sym.DNS)) { privateDns(a) },
    ))

    private fun internetSummary(a: Context): String {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Inte ansluten"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Ansluten till wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobildata · ${telephonyName(a)}"
            else -> "Ansluten"
        }
    }
    private fun telephonyName(a: Context) = try {
        a.getSystemService(android.telephony.TelephonyManager::class.java).networkOperatorName.ifBlank { "Inget SIM-kort" }
    } catch (_: Exception) { "" }
    private fun dataSaverOn(a: Context) = a.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus ==
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    private fun dataSaver(a: PixelSettingsActivity) = Spec("datasaver", "Databesparing", listOf(
        MainSwitch("Använd Databesparing", { dataSaverOn(a) }) { on -> a.shell("cmd netpolicy set restrict-background $on") },
        Category(null),
        Row("Obegränsad data", { "Appar som får använda data obegränsat" }) {
            a.openSystem(i(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).setData(Uri.parse("package:${a.packageName}")),
                i(Settings.ACTION_DATA_USAGE_SETTINGS))
        },
        Footer("Med Databesparing kan du minska dataanvändningen genom att förhindra att vissa appar skickar och tar emot data i bakgrunden."),
    ))

    private fun privateDnsSummary(a: Context) = when (try { Settings.Global.getString(a.contentResolver, "private_dns_mode") } catch (_: Exception) { null }) {
        "off" -> "Av"
        "hostname" -> (try { Settings.Global.getString(a.contentResolver, "private_dns_specifier") } catch (_: Exception) { null }) ?: "Värdnamn"
        else -> "Automatiskt"
    }
    private fun privateDns(a: PixelSettingsActivity) {
        val cur = when (try { Settings.Global.getString(a.contentResolver, "private_dns_mode") } catch (_: Exception) { null }) { "off" -> 0; "hostname" -> 2; else -> 1 }
        PDialog.choose(a, "Privat DNS", listOf("Av", "Automatiskt", "Värdnamn för privat DNS-leverantör"), cur) { sel ->
            when (sel) {
                0 -> put(a, "global", "private_dns_mode", "off")
                1 -> put(a, "global", "private_dns_mode", "opportunistic")
                else -> PDialog.edit(a, "Värdnamn för privat DNS-leverantör",
                    (try { Settings.Global.getString(a.contentResolver, "private_dns_specifier") } catch (_: Exception) { null }) ?: "dns.google") { host ->
                    put(a, "global", "private_dns_specifier", host.trim()) { put(a, "global", "private_dns_mode", "hostname") }
                }
            }
        }
    }

    // ---- Anslutna enheter ------------------------------------------------------------------------

    private fun connected(a: PixelSettingsActivity): Spec {
        val items = ArrayList<Item>()
        val bt = a.getSystemService(BluetoothManager::class.java)?.adapter
        val canBt = a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canBt) a.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 7)
        val bonded = try { if (canBt) bt?.bondedDevices?.toList() ?: emptyList() else emptyList() } catch (_: SecurityException) { emptyList() }
        val connected = bonded.filter { d -> try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (_: Exception) { false } }
        if (connected.isNotEmpty()) {
            items += Category("Medieenheter")
            connected.forEach { d ->
                items += Row(try { d.alias ?: d.name } catch (_: SecurityException) { "Enhet" } ?: "Enhet", { "Aktiv" }, sym(a, Sym.HEADPHONES)) {
                    a.openSystem(i(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
        }
        items += Category(null)
        items += Row("Parkoppla en ny enhet", { "Bluetooth aktiveras för parkoppling" }, sym(a, Sym.ADD)) {
            a.openSystem(i(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        val saved = bonded.filter { it !in connected }
        if (saved.isNotEmpty()) {
            items += Category("Sparade enheter")
            saved.take(3).forEach { d ->
                items += Row(try { d.alias ?: d.name } catch (_: SecurityException) { "Enhet" } ?: "Enhet", null, sym(a, Sym.BLUETOOTH)) {
                    a.openSystem(i(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
            items += Row("Se alla", null, null) { a.openSystem(i(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        items += Category(null)
        items += Row("Anslutningsval", { "Bluetooth, NFC, Android Auto" }, sym(a, Sym.SETTINGS)) { open(a, "connprefs") }
        if (bt?.isEnabled != true) items += Footer("Aktivera Bluetooth för att ansluta till andra enheter.")
        return Spec("connected", "Anslutna enheter", items)
    }

    private fun connectionPrefs(a: PixelSettingsActivity) = Spec("connprefs", "Anslutningsval", listOf(
        Category(null),
        Row("Bluetooth", { "Anslut till enheter i närheten" }, sym(a, Sym.BLUETOOTH), toggle = Toggle(
            get = { a.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true },
            set = { on -> a.shell("cmd bluetooth_manager ${if (on) "enable" else "disable"}") })),
        Row("NFC", { "Använd NFC" }, sym(a, Sym.NFC), toggle = Toggle(
            get = { android.nfc.NfcAdapter.getDefaultAdapter(a)?.isEnabled == true },
            set = { on -> a.shell("svc nfc ${if (on) "enable" else "disable"}") })),
        Row("Casta", null, sym(a, Sym.CAST)) { a.openSystem(i(Settings.ACTION_CAST_SETTINGS)) },
        Row("Android Auto", null, sym(a, Sym.DIRECTIONS_CAR)) {
            a.openSystem(a.packageManager.getLaunchIntentForPackage("com.google.android.projection.gearhead") ?: i(Settings.ACTION_BLUETOOTH_SETTINGS))
        },
        Row("Utskrift", { "Tjänster för utskrift" }, sym(a, Sym.PRINT)) { a.openSystem(i(Settings.ACTION_PRINT_SETTINGS)) },
        Row("Snabbdelning", null, sym(a, Sym.SHARE)) {
            a.openSystem(i("com.google.android.gms.nearby.SHARING_SETTINGS"), i(Settings.ACTION_BLUETOOTH_SETTINGS))
        },
    ))

    // ---- Ljud och vibration ----------------------------------------------------------------------

    private fun sound(a: PixelSettingsActivity): Spec {
        val am = a.getSystemService(AudioManager::class.java)
        fun vol(title: String, stream: Int, ic: Int) = Slider(title, a.getDrawable(ic), am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream),
            { am.getStreamVolume(stream) }) { v -> try { am.setStreamVolume(stream, v, 0) } catch (_: SecurityException) { } }
        return Spec("sound", "Ljud och vibration", listOf(
            Category(null),
            Row("Spela upp media på", { "Den här telefonen" }, sym(a, Sym.CAST)) {
                a.openSystem(i("com.android.settings.panel.action.MEDIA_OUTPUT"), i(Settings.Panel.ACTION_VOLUME))
            },
            vol("Medievolym", AudioManager.STREAM_MUSIC, R.drawable.aosp_ic_volume_media),
            vol("Samtalsvolym", AudioManager.STREAM_VOICE_CALL, R.drawable.aosp_ic_speaker_on),
            vol("Ringvolym", AudioManager.STREAM_RING, R.drawable.aosp_ic_ring_volume),
            vol("Aviseringsvolym", AudioManager.STREAM_NOTIFICATION, R.drawable.aosp_ic_volume_ringer_vibrate),
            vol("Alarmvolym", AudioManager.STREAM_ALARM, R.drawable.aosp_ic_alarm),
            Category(null),
            Row("Stör ej", { if (NotifStore.dndOn()) "På" else "Av" }, sym(a, Sym.DO_NOT_DISTURB_ON)) {
                a.openSystem(i("android.settings.ZEN_MODE_SETTINGS"))
            },
            Row("Vibration och haptik", { "Ringsignal, aviseringar, tryck" }, sym(a, Sym.VIBRATION)) {
                a.openSystem(i("android.settings.VIBRATION_SETTINGS"), i(Settings.ACTION_SOUND_SETTINGS))
            },
            Row("Live Caption", { "Textar media automatiskt" }, sym(a, Sym.CLOSED_CAPTION)) {
                a.openSystem(i(Settings.ACTION_CAPTIONING_SETTINGS))
            },
            Category("Ljudmönster"),
            ringRow(a, "Ringsignal", RingtoneManager.TYPE_RINGTONE),
            ringRow(a, "Standardljud för aviseringar", RingtoneManager.TYPE_NOTIFICATION),
            ringRow(a, "Standardljud för alarm", RingtoneManager.TYPE_ALARM),
            Category("Systemljud och vibrationer"),
            Row("Knappsatsljud", null, toggle = toggle(a, "system", Settings.System.DTMF_TONE_WHEN_DIALING, 1)),
            Row("Ljud vid skärmlåsning", null, toggle = toggle(a, "system", "lockscreen_sounds_enabled", 1)),
            Row("Laddningsljud och -vibration", null, toggle = toggle(a, "system", "charging_sounds_enabled", 1)),
            Row("Ljud vid tryck och klick", null, toggle = toggle(a, "system", Settings.System.SOUND_EFFECTS_ENABLED, 1)),
        ))
    }

    private fun ringRow(a: PixelSettingsActivity, title: String, type: Int) = Row(title, {
        try { RingtoneManager.getActualDefaultRingtoneUri(a, type)?.let { RingtoneManager.getRingtone(a, it)?.getTitle(a) } ?: "Inget" }
        catch (_: Exception) { "" }
    }) {
        if (!Settings.System.canWrite(a)) {
            a.openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}"))); return@Row
        }
        a.pickRingtone(type)
    }

    // ---- Skärm och tryck -------------------------------------------------------------------------

    private fun display(a: PixelSettingsActivity) = Spec("display", "Skärm och tryck", listOf(
        Category("Ljusstyrka"),
        Slider("Ljusstyrka", null, 0, 1000, {
            val v = sysInt(a, Settings.System.SCREEN_BRIGHTNESS, 128)
            (BrightnessSlider.linearToGamma(((v - 1) / 254f).coerceIn(0f, 1f)) * 1000).toInt()
        }) { g ->
            val lin = BrightnessSlider.gammaToLinear(g / 1000f)
            put(a, "system", Settings.System.SCREEN_BRIGHTNESS, (1 + lin * 254).toInt().coerceIn(1, 255).toString())
        },
        Row("Anpassad ljusstyrka", { "Anpassar ljusstyrkan efter omgivningen" }, toggle = Toggle(
            get = { sysInt(a, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 },
            set = { on -> put(a, "system", Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) "1" else "0") })),
        Category("Låsskärm"),
        Row("Låsskärm", { "Vad som visas på låsskärmen" }) {
            a.openSystem(i("android.settings.LOCK_SCREEN_SETTINGS"), i(Settings.ACTION_SECURITY_SETTINGS))
        },
        Row("Visa alltid klocka och info", { "Batterianvändningen ökar" }, toggle = toggle(a, "system", "aod_mode")),
        Row("Skärmens tidsgräns", { timeoutLabel(sysInt(a, Settings.System.SCREEN_OFF_TIMEOUT, 30000)) }) { timeout(a) },
        Category("Utseende"),
        Row("Mörkt tema", { if (a.sc.night) "På" else "Av" }, toggle = Toggle(
            get = { a.getSystemService(android.app.UiModeManager::class.java).nightMode == android.app.UiModeManager.MODE_NIGHT_YES },
            set = { on -> a.shell("cmd uimode night ${if (on) "yes" else "no"}") })),
        Row("Skärmstorlek och text", { "Teckenstorlek, fetstil, visningsstorlek" }) {
            a.openSystem(i("android.settings.TEXT_READING_SETTINGS"), i(Settings.ACTION_DISPLAY_SETTINGS))
        },
        Category("Färg"),
        Row("Nattljus", { if (sysInt(a, "blue_light_filter") == 1) "På" else "Av" }, toggle = toggle(a, "system", "blue_light_filter")),
        Row("Färger", { "Anpassade" }) { a.openSystem(i(Settings.ACTION_DISPLAY_SETTINGS)) },
        Category("Andra skärminställningar"),
        Row("Rotera skärmen automatiskt", null, toggle = toggle(a, "system", Settings.System.ACCELEROMETER_ROTATION)),
        Row("Smooth Display", { "Höjer automatiskt uppdateringsfrekvensen till 120 Hz för visst innehåll. Ökar batteriförbrukningen." },
            toggle = Toggle(get = { secInt(a, "refresh_rate_mode", 1) != 0 }, set = { on -> put(a, "secure", "refresh_rate_mode", if (on) "1" else "0") })),
        Row("Skärmupplösning", null) { a.openSystem(i(Settings.ACTION_DISPLAY_SETTINGS)) },
        Row("Skärmsläckare", null) { a.openSystem(i(Settings.ACTION_DREAM_SETTINGS)) },
    ))

    private val timeouts = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
    private fun timeoutLabel(ms: Int) = when {
        ms < 60_000 -> "Efter ${ms / 1000} sekunders inaktivitet"
        ms == 60_000 -> "Efter 1 minuts inaktivitet"
        else -> "Efter ${ms / 60_000} minuters inaktivitet"
    }
    private fun timeout(a: PixelSettingsActivity) {
        val cur = timeouts.indexOf(sysInt(a, Settings.System.SCREEN_OFF_TIMEOUT, 30000))
        PDialog.choose(a, "Skärmens tidsgräns",
            listOf("15 sekunder", "30 sekunder", "1 minut", "2 minuter", "5 minuter", "10 minuter", "30 minuter"), cur) { sel ->
            put(a, "system", Settings.System.SCREEN_OFF_TIMEOUT, timeouts[sel].toString())
        }
    }

    // ---- Batteri ----------------------------------------------------------------------------------

    private fun batteryIntent(a: Context) = a.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun batteryLevel(a: Context) = batteryIntent(a)?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
    private fun charging(a: Context) = batteryIntent(a)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0 != 0
    private fun batterySummary(a: Context) = "${batteryLevel(a)} %" + if (charging(a)) " – Laddas" else ""

    private fun battery(a: PixelSettingsActivity) = Spec("battery", "Batteri", listOf(
        Custom { act -> batteryHeader(act) },
        Category(null),
        Row("Batteriförbrukning", { "Visa användning sedan senaste fulladdningen" }) {
            a.openSystem(i(Intent.ACTION_POWER_USAGE_SUMMARY))
        },
        Row("Batterisparläge", { if (a.getSystemService(PowerManager::class.java).isPowerSaveMode) "På" else "Av" }) { open(a, "saver") },
        Row("Adaptive Battery", { "Begränsar batteriförbrukningen för appar som används sällan" }) {
            a.openSystem(i("com.samsung.android.sm.ACTION_BATTERY"), i(Intent.ACTION_POWER_USAGE_SUMMARY))
        },
        Category(null),
        Row("Batteriprocent", { "Visa batteriprocent i statusfältet" }, toggle = toggle(a, "system", "display_battery_percentage")),
        Row("Batteriinformation", null) { open(a, "batteryinfo") },
        Footer("Den återstående batteritiden är ungefärlig och kan ändras beroende på användning"),
    ))

    private fun batteryHeader(a: PixelSettingsActivity): View {
        // UsageProgressBarPreference: big percentage, status line and a rounded progress bar
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dpi(20f), a.dpi(20f), a.dpi(20f), a.dpi(20f)) }
        val pct = TextView(a).apply { textSize = 36f; typeface = Fonts.display; setTextColor(a.sc.onSurface) }
        val state = TextView(a).apply { textSize = 14f; typeface = Fonts.regular; setTextColor(a.sc.onVariant) }
        val bar = object : View(a) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val h = height.toFloat(); val f = batteryLevel(a) / 100f
                p.color = a.sc.highest; c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, p)
                p.color = a.sc.primary; c.drawRoundRect(0f, 0f, width * f, h, h / 2, h / 2, p)
            }
        }
        box.addView(pct); box.addView(state)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dpi(16f)).apply { topMargin = a.dpi(16f) })
        val fill = {
            pct.text = "${batteryLevel(a)} %"
            state.text = if (charging(a)) "Laddas" else {
                val bm = a.getSystemService(BatteryManager::class.java)
                val left = if (Build.VERSION.SDK_INT >= 28) try { bm.computeChargeTimeRemaining() } catch (_: Exception) { -1L } else -1L
                if (left > 0) "Laddat om ${left / 60000} min" else "Används på batteri"
            }
            bar.invalidate()
        }
        fill(); a.onRefresh(fill)
        return box
    }

    private fun batterySaver(a: PixelSettingsActivity) = Spec("saver", "Batterisparläge", listOf(
        MainSwitch("Använd batterisparläge", { a.getSystemService(PowerManager::class.java).isPowerSaveMode }) { on ->
            a.shell("cmd power set-mode ${if (on) 1 else 0}")
        },
        Footer("Batterisparläget aktiverar mörkt tema och begränsar eller inaktiverar bakgrundsaktivitet, vissa visuella effekter och vissa funktioner som \"Hey Google\"."),
    ))

    private fun batteryInfo(a: PixelSettingsActivity): Spec {
        val b = batteryIntent(a)
        val cycles = if (Build.VERSION.SDK_INT >= 34) b?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1 else -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val volt = (b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val health = when (b?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Bra"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Överhettat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Förbrukat"; else -> "Okänt"
        }
        return Spec("batteryinfo", "Batteriinformation", listOf(
            Category(null),
            Row("Antal laddningscykler", { if (cycles >= 0) "$cycles" else "Inte tillgängligt" }),
            Row("Batteriets skick", { health }),
            Row("Temperatur", { "%.1f °C".format(temp) }),
            Row("Spänning", { "%.2f V".format(volt) }),
        ))
    }

    // ---- System -----------------------------------------------------------------------------------

    private fun system(a: PixelSettingsActivity) = Spec("system", "System", listOf(
        Category(null),
        Row("Språk", { java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() } }, sym(a, Sym.TRANSLATE)) {
            a.openSystem(i(Settings.ACTION_LOCALE_SETTINGS))
        },
        Row("Tangentbord", { "Skärmtangentbord, verktyg" }, sym(a, Sym.KEYBOARD)) { a.openSystem(i(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        Row("Rörelser", { "Snabba rörelser för att styra telefonen" }, sym(a, Sym.GESTURE)) {
            a.openSystem(i("android.settings.GESTURE_SETTINGS"), cls("com.android.settings", "com.android.settings.Settings\$MotionAndGestureSettingsActivity"))
        },
        Row("Datum och tid", { "GMT+01:00 Centraleuropeisk normaltid" }, sym(a, Sym.SCHEDULE)) { a.openSystem(i(Settings.ACTION_DATE_SETTINGS)) },
        Category(null),
        Row("Säkerhetskopiering", { "Säkerhetskopiera till Google Drive" }, sym(a, Sym.CLOUD_UPLOAD)) {
            a.openSystem(i("android.settings.BACKUP_AND_RESET_SETTINGS"), i(Settings.ACTION_PRIVACY_SETTINGS))
        },
        Row("Systemuppdatering", { "Uppdaterad till Android ${Build.VERSION.RELEASE}" }, sym(a, Sym.SYSTEM_UPDATE)) {
            a.openSystem(i("android.settings.SYSTEM_UPDATE_SETTINGS"))
        },
        Row("Återställningsalternativ", null, sym(a, Sym.RESTART_ALT)) {
            a.openSystem(i("android.settings.BACKUP_AND_RESET_SETTINGS"), i(Settings.ACTION_PRIVACY_SETTINGS))
        },
        Row("Utvecklaralternativ", null, sym(a, Sym.CODE)) { a.openSystem(i(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) },
    ))

    // ---- Plats ------------------------------------------------------------------------------------

    private fun locationOn(a: Context) = a.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
    private fun location(a: PixelSettingsActivity) = Spec("location", "Plats", listOf(
        MainSwitch("Använd plats", { locationOn(a) }) { on -> a.shell("cmd location set-location-enabled $on") },
        Category(null),
        Row("Appars platsbehörigheter", { "Appar med åtkomst till din plats" }) {
            a.openSystem(i("android.intent.action.MANAGE_PERMISSION_APPS").putExtra("android.intent.extra.PERMISSION_GROUP_NAME", "android.permission-group.LOCATION"),
                i(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        },
        Row("Platstjänster", { "Wifi- och Bluetooth-sökning, nödplatstjänst" }) { a.openSystem(i(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        Footer("Platsen kan använda källor som GPS, wifi, mobilnätverk och sensorer för att uppskatta var enheten finns."),
    ))

    // ---- Om telefonen -----------------------------------------------------------------------------

    private fun deviceName(a: Context) = (try { Settings.Global.getString(a.contentResolver, "device_name") } catch (_: Exception) { null }) ?: Build.MODEL

    private fun about(a: PixelSettingsActivity) = Spec("about", "Om telefonen", listOf(
        Custom { act ->
            LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(act.dpi(24f), act.dpi(28f), act.dpi(24f), act.dpi(28f))
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(TextView(act).apply { text = deviceName(act); textSize = 28f; typeface = Fonts.display; setTextColor(act.sc.onSurface) })
                addView(TextView(act).apply { text = Build.MODEL; textSize = 14f; setTextColor(act.sc.onVariant); setPadding(0, act.dpi(4f), 0, 0) })
            }
        },
        Category("Basinformation"),
        Row("Enhetens namn", { deviceName(a) }) {
            PDialog.edit(a, "Enhetens namn", deviceName(a)) { n -> if (n.isNotBlank()) put(a, "global", "device_name", n.trim()) }
        },
        Category("Juridisk information och föreskrifter"),
        Row("Juridisk information", null) { a.openSystem(i("android.settings.LICENSE"), i(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
        Category("Enhetsinformation"),
        Row("Status för SIM-kort", { telephonyName(a) }) { a.openSystem(i(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
        Row("Modell", { Build.MODEL }),
        Row("IMEI-nummer", { "Visa i One UI" }) { a.openSystem(i(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
        Row("Android-version", { Build.VERSION.RELEASE }) { a.openSystem(i(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
        Category("Enhetsidentifierare"),
        Row("IP-adress", { ipAddress(a) }),
        Row("Drifttid", { uptime() }),
        Row("Version", { Build.DISPLAY }),
    ))

    private fun ipAddress(a: Context): String = try {
        val cm = a.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address.hostAddress ?: "" }?.filter { it.isNotBlank() }
            ?.joinToString("\n")?.ifBlank { null } ?: "Inte tillgängligt"
    } catch (_: Exception) { "Inte tillgängligt" }

    private fun uptime(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    // ---- Lagring summary ---------------------------------------------------------------------------

    private fun storageSummary(): String = try {
        val st = StatFs(android.os.Environment.getDataDirectory().path)
        val total = st.totalBytes.toDouble(); val free = st.availableBytes.toDouble()
        "%d %% används – %d GB ledigt".format(((total - free) / total * 100).toInt(), (free / 1e9).toInt())
    } catch (_: Exception) { "" }

    // ---- search -------------------------------------------------------------------------------------

    fun openSearch(a: PixelSettingsActivity) {
        val index = ArrayList<Triple<String, String, () -> Unit>>()
        homeItems(a).filterIsInstance<Row>().forEach { r -> index += Triple(r.title, "", r.onClick ?: {}) }
        allIds.forEach { id ->
            spec(a, id)?.let { s ->
                s.items.forEach { it2 ->
                    val t = when (it2) { is Row -> it2.title; is Slider -> it2.title; is MainSwitch -> it2.title; else -> null }
                    if (t != null) index += Triple(t, s.title, { open(a, id) })
                }
            }
        }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val field = EditText(a).apply {
            hint = "Sök i inställningar"; textSize = 18f; setTextColor(a.sc.onSurface); setHintTextColor(a.sc.onVariant)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = a.dp(40f); setColor(a.sc.search) }
            setPadding(a.dpi(24f), a.dpi(20f), a.dpi(24f), a.dpi(20f)); isSingleLine = true
        }
        val page = PageView(a, "Sök", listOf(Custom { field }, Category(null), Custom { list }))
        fun render(q: String) {
            list.removeAllViews()
            if (q.isBlank()) return
            index.filter { it.first.contains(q, true) || it.second.contains(q, true) }.distinctBy { it.first + it.second }.take(30).forEach { (t, path, go) ->
                list.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(a.dpi(16f), a.dpi(14f), a.dpi(16f), a.dpi(14f))
                    addView(TextView(a).apply { text = t; textSize = 16f; setTextColor(a.sc.onSurface) })
                    if (path.isNotBlank()) addView(TextView(a).apply { text = path; textSize = 14f; setTextColor(a.sc.onVariant) })
                    setOnClickListener { go() }
                })
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })
        a.push(page)
        field.requestFocus()
        a.main.postDelayed({
            a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, 0)
        }, 300)
    }

    // ---- icons -------------------------------------------------------------------------------------

    private fun icon(a: PixelSettingsActivity, res: Int): Drawable = a.getDrawable(res)!!.mutate().apply { setTint(a.sc.onVariant) }
    private fun sym(a: PixelSettingsActivity, s: String): Drawable = SymDrawable(a, s, a.sc.onVariant)

    /** A Material Symbol glyph as a 24 dp drawable. */
    class SymDrawable(ctx: Context, private val s: String, private val color: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Fonts.symOutline; textAlign = Paint.Align.CENTER; this.color = color
            textSize = ctx.dp(24f)
        }
        private val size = ctx.dpi(24f)
        override fun draw(c: Canvas) {
            val b = bounds; val fm = p.fontMetrics
            c.drawText(s, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, p)
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Google entry icon: the four-colour G. */
    class GoogleG(ctx: Context) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3.4f) }
        private val size = ctx.dpi(24f)
        override fun draw(c: Canvas) {
            val b = bounds; val r = size / 2f - p.strokeWidth / 2f; val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val o = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            p.color = 0xFFEA4335.toInt(); c.drawArc(o, 225f, 100f, false, p)
            p.color = 0xFFFBBC05.toInt(); c.drawArc(o, 135f, 90f, false, p)
            p.color = 0xFF34A853.toInt(); c.drawArc(o, 45f, 90f, false, p)
            p.color = 0xFF4285F4.toInt(); c.drawArc(o, -10f, 55f, false, p)
            c.drawLine(cx, cy, cx + r + p.strokeWidth / 2, cy, p)
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
