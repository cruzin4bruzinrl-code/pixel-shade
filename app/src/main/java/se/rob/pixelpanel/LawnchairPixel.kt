package se.rob.pixelpanel

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Turns a Lawnchair backup (.lawnchairbackup: a zip with info.pb, launcher.db, the launcher3
 * shared-prefs XML and the DataStore file preferences.preferences_pb, see LawnchairBackup.kt)
 * into one with Pixel 10 Launcher3 settings. The home screen layout (launcher.db) and info.pb are
 * kept as they are; only settings are rewritten, so restoring it in Lawnchair keeps the user's apps.
 */
object LawnchairPixel {

    /** DataStore (PreferenceManager2) values, as Lawnchair saves them. */
    private val dataStore: List<Pair<String, Any>> = listOf(
        "icon_shape" to "circle",                 // Pixel default app icon shape
        "folder_shape" to "circle",               // Launcher3 folders follow the icon shape
        "hotseat_mode" to "lawnchair",            // dock with search bar below it
        "dock_search_bar_provider" to "google",   // Google search pill, like Pixel
        "themed_hotseat_qsb" to true,             // search pill tinted with the theme colours
        "color_style" to "tonal_spot",            // Pixel's default Material You style
        "enable_smartspace" to true,              // "Vid en blick" at the top
        "show_icon_labels_on_home_screen" to true,
        "show_icon_labels_in_drawer" to true,
    )

    /** Legacy SharedPreferences (PreferenceManager) values: Pixel 10's 4 x 5 grid, 4-icon dock. */
    private val xmlInts = listOf("pref_workspaceColumns" to 4, "pref_workspaceRows" to 5, "pref_hotseatColumns" to 4)

    fun convert(ctx: Context, input: Uri, output: Uri): String {
        val entries = LinkedHashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(input)?.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    entries[e.name] = z.readBytes()
                }
            }
        } ?: return "Kunde inte läsa filen"
        if (!entries.containsKey("info.pb")) return "Det här är inte en Lawnchair-säkerhetskopia"

        val dsName = "preferences.preferences_pb"
        entries[dsName] = rewriteDataStore(entries[dsName] ?: ByteArray(0))
        val xmlName = entries.keys.firstOrNull { it.endsWith(".xml") } ?: "com.android.launcher3.prefs.xml"
        entries[xmlName] = rewriteXml(entries[xmlName]?.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)

        ctx.contentResolver.openOutputStream(output, "wt")?.use { outs ->
            ZipOutputStream(outs.buffered()).use { z ->
                // info.pb first, as Lawnchair writes it
                (listOf("info.pb") + entries.keys.filter { it != "info.pb" }).forEach { name ->
                    z.putNextEntry(ZipEntry(name)); z.write(entries.getValue(name)); z.closeEntry()
                }
            }
        } ?: return "Kunde inte spara filen"
        return "Klart! Öppna Lawnchair-inställningar → Säkerhetskopiera och återställ → Återställ och välj \"Lawnchair Pixel 10\""
    }

    // ---- SharedPreferences XML -----------------------------------------------------------------

    private fun rewriteXml(src: String?): String {
        var s = src?.takeIf { it.contains("<map") }
            ?: "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        if (s.contains("<map />")) s = s.replace("<map />", "<map>\n</map>")
        for ((k, v) in xmlInts) {
            val re = Regex("<int name=\"${Regex.escape(k)}\" value=\"-?\\d+\" ?/>")
            val tag = "<int name=\"$k\" value=\"$v\" />"
            s = if (re.containsMatchIn(s)) s.replace(re, tag) else s.replace("</map>", "    $tag\n</map>")
        }
        return s
    }

    // ---- DataStore PreferenceMap protobuf --------------------------------------------------------
    // message PreferenceMap { map<string, Value> preferences = 1; }
    // message Value { oneof value { bool boolean = 1; float float = 2; int32 integer = 3; int64 long = 4;
    //                 string string = 5; StringSet string_set = 6; double double = 7; bytes bytes = 8; } }

    private fun rewriteDataStore(src: ByteArray): ByteArray {
        val keep = ArrayList<Pair<String, ByteArray>>()   // key -> raw entry bytes
        var i = 0
        while (i < src.size) {
            val (tag, n1) = varint(src, i); i = n1
            val field = (tag shr 3).toInt(); val wire = (tag and 7).toInt()
            if (wire != 2) { i = skip(src, i, wire); continue }
            val (len, n2) = varint(src, i); i = n2
            val entry = src.copyOfRange(i, i + len.toInt()); i += len.toInt()
            if (field == 1) keep += keyOf(entry) to entry
        }
        val overrides = dataStore.map { it.first }.toSet()
        val out = ByteArrayOutputStream()
        keep.filter { it.first !in overrides }.forEach { writeLen(out, 1, it.second) }
        dataStore.forEach { (k, v) -> writeLen(out, 1, entry(k, v)) }
        return out.toByteArray()
    }

    private fun keyOf(entry: ByteArray): String {
        var i = 0
        while (i < entry.size) {
            val (tag, n1) = varint(entry, i); i = n1
            val wire = (tag and 7).toInt()
            if ((tag shr 3).toInt() == 1 && wire == 2) {
                val (len, n2) = varint(entry, i)
                return String(entry, n2, len.toInt(), Charsets.UTF_8)
            }
            i = skip(entry, i, wire)
        }
        return ""
    }

    private fun entry(key: String, v: Any): ByteArray {
        val value = ByteArrayOutputStream()
        when (v) {
            is Boolean -> { writeVarint(value, (1L shl 3) or 0); writeVarint(value, if (v) 1 else 0) }
            is Int -> { writeVarint(value, (3L shl 3) or 0); writeVarint(value, v.toLong()) }
            is String -> writeLen(value, 5, v.toByteArray(Charsets.UTF_8))
        }
        val e = ByteArrayOutputStream()
        writeLen(e, 1, key.toByteArray(Charsets.UTF_8))
        writeLen(e, 2, value.toByteArray())
        return e.toByteArray()
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var shift = 0; var i = start
        while (true) {
            val x = b[i++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return r to i
            shift += 7
        }
    }

    private fun skip(b: ByteArray, i: Int, wire: Int): Int = when (wire) {
        0 -> varint(b, i).second
        1 -> i + 8
        2 -> varint(b, i).let { (len, n) -> n + len.toInt() }
        5 -> i + 4
        else -> b.size
    }

    private fun writeVarint(o: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (true) {
            if (v and 0x7fL.inv() == 0L) { o.write(v.toInt()); return }
            o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7
        }
    }

    private fun writeLen(o: ByteArrayOutputStream, field: Int, data: ByteArray) {
        writeVarint(o, (field.toLong() shl 3) or 2); writeVarint(o, data.size.toLong()); o.write(data)
    }
}
