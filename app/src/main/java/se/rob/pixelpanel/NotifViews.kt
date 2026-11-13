package se.rob.pixelpanel

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.InputType
import android.text.format.DateFormat
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/** What the notification views need from the shade. */
interface NotifHost {
    fun launch(pi: PendingIntent?, fill: Intent? = null, closeShade: Boolean = true)
    fun openSettings(intent: Intent)
    fun dismiss(keys: List<String>)
    fun focusInput(edit: EditText)
    fun isExpanded(key: String): Boolean
    fun setExpanded(key: String, v: Boolean)
}

// ---- Data helpers ------------------------------------------------------------------------

object AppInfo {
    private val labels = HashMap<String, CharSequence>()
    private val icons = HashMap<String, Drawable?>()

    fun label(ctx: Context, pkg: String): CharSequence = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        } catch (_: PackageManager.NameNotFoundException) { pkg }
    }

    fun icon(ctx: Context, pkg: String): Drawable? = icons.getOrPut(pkg) {
        try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    }
}

private fun Bundle.cs(key: String): CharSequence? = getCharSequence(key)

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Bundle.parcel(key: String): T? = try { get(key) as? T } catch (_: Exception) { null }

fun Notification.isSummary() = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
fun Notification.isMedia() = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
fun Notification.isMessaging() =
    extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$MessagingStyle"

fun Notification.title(): CharSequence? =
    extras.cs(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.cs(Notification.EXTRA_TITLE_BIG) ?: extras.cs(Notification.EXTRA_TITLE)

/** Messages as "Sender: text" lines, oldest first. */
fun Notification.messages(): List<CharSequence> {
    val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
    return arr.mapNotNull { p ->
        val b = p as? Bundle ?: return@mapNotNull null
        val text = b.cs("text") ?: return@mapNotNull null
        val sender = b.cs("sender") ?: (b.parcel<android.app.Person>("sender_person"))?.name
        if (sender != null) "$sender: $text" else text
    }
}

fun Notification.shortText(): CharSequence? =
    if (isMessaging()) messages().lastOrNull() ?: extras.cs(Notification.EXTRA_TEXT)
    else extras.cs(Notification.EXTRA_TEXT) ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.firstOrNull()

fun Notification.longText(): CharSequence? = when {
    isMessaging() -> messages().takeLast(6).joinToString("\n").ifEmpty { null }
    extras.cs(Notification.EXTRA_BIG_TEXT) != null -> extras.cs(Notification.EXTRA_BIG_TEXT)
    extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null ->
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.joinToString("\n")
    else -> extras.cs(Notification.EXTRA_TEXT)
}

fun Notification.picture(ctx: Context): Drawable? {
    extras.parcel<Bitmap>(Notification.EXTRA_PICTURE)?.let { return android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
    extras.parcel<Icon>(Notification.EXTRA_PICTURE_ICON)?.let { return try { it.loadDrawable(ctx) } catch (_: Exception) { null } }
    return null
}

fun relativeTime(ctx: Context, `when`: Long): String {
    val d = System.currentTimeMillis() - `when`
    val min = d / 60000
    return when {
        min < 1 -> "nu"
        min < 60 -> "$min min"
        min < 24 * 60 -> "${min / 60} h"
        else -> DateFormat.format("d MMM", Date(`when`)).toString()
    }
}

// ---- Shared pieces -----------------------------------------------------------------------

/** Round app icon; for conversations the sender avatar with a small app badge. */
class AppAvatar(ctx: Context, pal: Palette, main: Drawable?, badge: Drawable?, sizeDp: Float) : FrameLayout(ctx) {
    init {
        val size = ctx.dpi(sizeDp)
        val iv = ImageView(ctx).apply {
            setImageDrawable(main)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.surfaceContainer) }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        addView(iv, LayoutParams(size, size))
        if (badge != null) {
            val b = ctx.dpi(sizeDp * 0.42f)
            addView(ImageView(ctx).apply {
                setImageDrawable(badge)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.surfaceContainerHigh) }
                setPadding(ctx.dpi(1.5f), ctx.dpi(1.5f), ctx.dpi(1.5f), ctx.dpi(1.5f))
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }, LayoutParams(b, b, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = -ctx.dpi(2f); bottomMargin = -ctx.dpi(2f)
            })
        }
        clipChildren = false
    }
}

/** notification_2025_expand_button: a 20 dp-high pill (min 28 dp wide) with an optional count and a chevron. */
class ExpandPill(ctx: Context, private val pal: Palette, count: Int, expanded: Boolean, onClick: () -> Unit) : LinearLayout(ctx) {
    private val chevron = SymbolView(ctx, if (expanded) Sym.EXPAND_LESS else Sym.EXPAND_MORE, pal.onSurface, 16f)
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        minimumHeight = ctx.dpi(20f); minimumWidth = ctx.dpi(28f)
        setPadding(ctx.dpi(6f), ctx.dpi(2f), ctx.dpi(6f), ctx.dpi(2f))
        background = GradientDrawable().apply { cornerRadius = ctx.dp(20f); setColor(pal.surfaceContainer) }
        if (count > 1) addView(ctx.label("$count", 12f, pal.onSurface).apply { setPadding(ctx.dpi(2f), 0, ctx.dpi(1f), 0) })
        addView(chevron, LayoutParams(ctx.dpi(16f), ctx.dpi(16f)))
        setOnClickListener { onClick() }
    }
    fun setExpanded(e: Boolean) { chevron.symbol = if (e) Sym.EXPAND_LESS else Sym.EXPAND_MORE; chevron.invalidate() }
}

/** Title first, then "App · time" in the small info style, on one line (notification_2025_top_line). */
private fun topLine(ctx: Context, pal: Palette, title: CharSequence?, info: String): TextView {
    val sb = android.text.SpannableStringBuilder()
    if (!title.isNullOrBlank()) {
        sb.append(title)
        sb.setSpan(android.text.style.TypefaceSpan(Fonts.medium), 0, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(pal.onSurface), 0, sb.length, 0)
        sb.append("  ")
    }
    val st = sb.length
    sb.append(info)
    sb.setSpan(android.text.style.AbsoluteSizeSpan(12, true), st, sb.length, 0)
    sb.setSpan(android.text.style.ForegroundColorSpan(pal.onSurfaceVariant), st, sb.length, 0)
    return ctx.label(sb, 14f, pal.onSurface)
}

private fun actionPill(ctx: Context, pal: Palette, text: CharSequence, onClick: () -> Unit) =
    ctx.label(text, 14f, pal.onSurface, medium = true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(16f), ctx.dpi(9f), ctx.dpi(16f), ctx.dpi(9f))
        background = GradientDrawable().apply { setColor(pal.surfaceContainer); cornerRadius = ctx.dp(100f) }
        setOnClickListener { onClick() }
    }

/** Thin determinate progress bar for download/upload notifications. */
private class ThinProgress(ctx: Context, private val pal: Palette, private val frac: Float, private val indeterminate: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.color = pal.surfaceContainer; r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = pal.primary
        val f = if (indeterminate) ((SystemClock.uptimeMillis() % 1500) / 1500f) else frac
        if (indeterminate) { val x = f * width; r.set(x - width * 0.2f, 0f, x, h) } else r.set(0f, 0f, width * f, h)
        c.drawRoundRect(r, h / 2, h / 2, p)
        if (indeterminate) postInvalidateOnAnimation()
    }
}

// ---- Single notification -------------------------------------------------------------

class NotifCardView(
    ctx: Context, private val pal: Palette, private val e: NotifStore.Entry,
    private val host: NotifHost, extraKeys: List<String> = emptyList(),
) : LinearLayout(ctx), StackCard {

    override val bg = GradientDrawable().apply { setColor(pal.card) }
    override val dismissKeys = listOf(e.key) + extraKeys
    override val dismissable = e.sbn.isClearable

    private val n = e.n
    private var expanded = host.isExpanded(e.key)
    private val textView: TextView
    private val expandArea = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val pill: ExpandPill
    private var replyRow: LinearLayout? = null

    init {
        // notification_2025_template_collapsed_base: 72 dp min height, 40 dp icon at 16 dp,
        // content starts at 72 dp, 12 dp vertical margins, expand pill top-right with 16 dp padding.
        orientation = VERTICAL
        background = bg
        minimumHeight = ctx.dpi(72f)
        setPadding(0, 0, 0, ctx.dpi(4f))

        val row = FrameLayout(ctx)
        val pkg = e.sbn.packageName
        val appIcon = AppInfo.icon(ctx, pkg)
        val large = try { n.getLargeIcon()?.loadDrawable(ctx) } catch (_: Exception) { null }
        val avatar = if (n.isMessaging() && large != null) AppAvatar(ctx, pal, large, appIcon, 40f)
        else AppAvatar(ctx, pal, appIcon, null, 40f)
        row.addView(avatar, FrameLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f)).apply {
            leftMargin = ctx.dpi(16f); topMargin = ctx.dpi(16f)
        })

        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        val sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val info = buildString {
            append(AppInfo.label(ctx, pkg))
            if (!sub.isNullOrBlank()) append(" · ").append(sub)
            if (n.`when` > 0 && n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)) append(" · ").append(relativeTime(ctx, n.`when`))
        }
        col.addView(topLine(ctx, pal, n.title(), info).apply { setPadding(0, ctx.dpi(4f), 0, 0) })
        textView = ctx.label(n.shortText(), 14f, pal.onSurfaceVariant).apply {
            setPadding(0, ctx.dpi(2f), 0, 0)
            setLineSpacing(ctx.dp(2f), 1f)
        }
        if (!n.shortText().isNullOrBlank() || !n.longText().isNullOrBlank()) col.addView(textView)
        val endRoom = ctx.dpi(16f + 28f + 8f) + (if (large != null && !n.isMessaging()) ctx.dpi(48f + 8f) else 0)
        row.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dpi(72f); rightMargin = endRoom; topMargin = ctx.dpi(12f); bottomMargin = ctx.dpi(12f)
        })

        if (large != null && !n.isMessaging()) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(large); scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = ctx.dp(8f); setColor(pal.surfaceContainer) }
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }, FrameLayout.LayoutParams(ctx.dpi(48f), ctx.dpi(48f), Gravity.END or Gravity.TOP).apply {
                topMargin = ctx.dpi(12f); rightMargin = ctx.dpi(16f + 28f + 8f)
            })
        }

        pill = ExpandPill(ctx, pal, 0, expanded) { toggle() }
        row.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = ctx.dpi(16f); rightMargin = ctx.dpi(16f)
        })
        addView(row)

        buildExpandArea()
        addView(expandArea, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dpi(72f); marginEnd = ctx.dpi(16f); bottomMargin = ctx.dpi(8f)
        })
        applyExpanded()

        setOnClickListener {
            host.launch(n.contentIntent)
            if (n.flags and Notification.FLAG_AUTO_CANCEL != 0 && e.sbn.isClearable) host.dismiss(dismissKeys)
        }
    }

    private fun buildExpandArea() {
        val ctx = context
        // Progress
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indet = n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (max > 0 || indet) {
            val prog = n.extras.getInt(Notification.EXTRA_PROGRESS, 0)
            expandArea.addView(ThinProgress(ctx, pal, if (max > 0) prog.toFloat() / max else 0f, indet),
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(4f)).apply { topMargin = ctx.dpi(10f) })
        }
        // Big picture
        n.picture(ctx)?.let { pic ->
            expandArea.addView(ImageView(ctx).apply {
                setImageDrawable(pic); scaleType = ImageView.ScaleType.CENTER_CROP; adjustViewBounds = true
                maxHeight = ctx.dpi(220f)
                background = GradientDrawable().apply { cornerRadius = ctx.dp(16f); setColor(pal.surfaceContainer) }
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(10f) })
        }
        // Actions
        val actions = n.actions?.filter { it?.title != null } ?: emptyList()
        if (actions.isNotEmpty()) {
            val ar = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            actions.take(3).forEachIndexed { i, a ->
                val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                ar.addView(actionPill(ctx, pal, a.title) {
                    if (ri != null) showReply(a, ri) else host.launch(a.actionIntent, null, closeShade = true)
                }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) marginStart = ctx.dpi(8f)
                })
            }
            expandArea.addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false; addView(ar)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(12f) })
        }
    }

    private fun showReply(a: Notification.Action, ri: RemoteInput) {
        val ctx = context
        replyRow?.let { expandArea.removeView(it) }
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edit = EditText(ctx).apply {
            hint = ri.label ?: "Svara"
            typeface = Fonts.regular; textSize = 15f
            setTextColor(pal.onSurface); setHintTextColor(pal.onSurfaceVariant)
            background = GradientDrawable().apply { setColor(pal.surfaceContainer); cornerRadius = ctx.dp(100f) }
            setPadding(ctx.dpi(16f), ctx.dpi(10f), ctx.dpi(16f), ctx.dpi(10f))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val send = SymbolView(ctx, Sym.SEND, pal.onPrimary, 20f).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.primary) }
        }
        fun doSend() {
            val text = edit.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            val intent = Intent()
            RemoteInput.addResultsToIntent(a.remoteInputs, intent, Bundle().apply { putCharSequence(ri.resultKey, text) })
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            host.launch(a.actionIntent, intent, closeShade = false)
            edit.setText(""); edit.hint = "Skickat ✓"; edit.clearFocus()
        }
        send.setOnClickListener { doSend() }
        edit.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        row.addView(edit, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LayoutParams(ctx.dpi(44f), ctx.dpi(44f)).apply { marginStart = ctx.dpi(8f) })
        replyRow = row
        expandArea.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dpi(10f) })
        if (!expanded) toggle()
        host.focusInput(edit)
    }

    private fun toggle() {
        (parent?.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(250).setInterpolator(EMPHASIZED)) }
        expanded = !expanded
        host.setExpanded(e.key, expanded)
        applyExpanded()
    }

    private fun applyExpanded() {
        pill.setExpanded(expanded)
        if (expanded) { textView.text = n.longText() ?: n.shortText(); textView.maxLines = 14 }
        else { textView.text = n.shortText(); textView.maxLines = 1 }
        expandArea.visibility = if (expanded && expandArea.childCount > 0) View.VISIBLE else View.GONE
    }
}

// ---- Group of notifications from one app -----------------------------------------------

class GroupCardView(
    ctx: Context, private val pal: Palette, private val summary: NotifStore.Entry?,
    private val children: List<NotifStore.Entry>, private val host: NotifHost,
) : LinearLayout(ctx), StackCard {

    override val bg = GradientDrawable().apply { setColor(pal.card) }
    override val dismissKeys = children.map { it.key } + listOfNotNull(summary?.key)
    override val dismissable = children.all { it.sbn.isClearable }

    private val groupKey = children.first().sbn.groupKey
    private var expanded = host.isExpanded(groupKey)
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val pill: ExpandPill

    init {
        orientation = VERTICAL
        background = bg
        minimumHeight = ctx.dpi(72f)
        val pkg = children.first().sbn.packageName
        val row = FrameLayout(ctx)
        row.addView(AppAvatar(ctx, pal, AppInfo.icon(ctx, pkg), null, 40f), FrameLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f)).apply {
            leftMargin = ctx.dpi(16f); topMargin = ctx.dpi(16f)
        })
        val latest = children.maxOf { it.n.`when` }
        val sumTitle = summary?.n?.title() ?: summary?.n?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val head = LinearLayout(ctx).apply { orientation = VERTICAL }
        head.addView(topLine(ctx, pal, sumTitle ?: AppInfo.label(ctx, pkg),
            (if (sumTitle != null) "${AppInfo.label(ctx, pkg)} · " else "") + relativeTime(ctx, latest)).apply {
            setPadding(0, ctx.dpi(4f), 0, 0)
        })
        head.addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(head, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dpi(72f); rightMargin = ctx.dpi(16f + 40f + 8f); topMargin = ctx.dpi(12f); bottomMargin = ctx.dpi(12f)
        })
        pill = ExpandPill(ctx, pal, children.size, expanded) { toggle() }
        row.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = ctx.dpi(16f); rightMargin = ctx.dpi(16f)
        })
        addView(row)
        fillList()
        setOnClickListener { host.launch(summary?.n?.contentIntent ?: children.first().n.contentIntent) }
    }

    private fun fillList() {
        list.removeAllViews()
        val shown = if (expanded) children else children.take(2)
        shown.forEach { c ->
            val r = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, context.dpi(4f), 0, context.dpi(4f))
                setOnClickListener {
                    host.launch(c.n.contentIntent)
                    if (c.n.flags and Notification.FLAG_AUTO_CANCEL != 0 && c.sbn.isClearable) host.dismiss(listOf(c.key))
                }
            }
            c.n.title()?.let { r.addView(context.label(it, 14f, pal.onSurface, medium = true)) }
            c.n.shortText()?.let { r.addView(context.label(it, 14f, pal.onSurfaceVariant, lines = if (expanded) 3 else 1)) }
            list.addView(r)
        }
        if (!expanded && children.size > 2) {
            list.addView(context.label("+${children.size - 2} till", 13f, pal.primary, medium = true).apply {
                setPadding(0, context.dpi(4f), 0, 0)
            })
        }
    }

    private fun toggle() {
        (parent?.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(250).setInterpolator(EMPHASIZED)) }
        expanded = !expanded
        host.setExpanded(groupKey, expanded)
        pill.setExpanded(expanded)
        fillList()
    }
}

// ---- Media player -------------------------------------------------------------------------

/**
 * Pixel-style media player: album art behind a scrim tinted with the art's own colours,
 * output chip top-right, big play button, and a wavy progress line while playing.
 */
class MediaCard(ctx: Context, private val pal: Palette, private val e: NotifStore.Entry, private val host: NotifHost) : FrameLayout(ctx) {
    private val controller: MediaController? = run {
        val token = e.n.extras.parcel<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        token?.let { try { MediaController(ctx, it) } catch (_: Exception) { null } }
    }
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val scrimView = View(ctx)
    private val title = ctx.label("", 18f, Color.WHITE, medium = true)
    private val artist = ctx.label("", 14f, Color.WHITE.withAlpha(215))
    private val chipText = ctx.label("", 12f, Color.WHITE, medium = true)
    private val play = SymbolView(ctx, Sym.PLAY_ARROW, Color.BLACK, 28f)
    private var accent = pal.primary
    private var lastArt: Bitmap? = null
    private var bottomRow: View? = null
    /** 0 = expanded (184 dp, with seek row), 1 = collapsed QQS player (128 dp, no seek row). */
    var compact = 0f
        private set

    private val seek = object : View(ctx) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val path = android.graphics.Path()
        var frac = 0f
        var playing = false
        var phase = 0f
        override fun onDraw(c: Canvas) {
            val y = height / 2f
            val x = width * frac
            val amp = if (playing) context.dp(3f) else 0f
            val wl = context.dp(22f)
            p.strokeWidth = context.dp(3f)
            // Played part: a gentle sine wave that drifts while playing (Pixel's squiggly progress)
            p.color = Color.WHITE
            path.reset(); path.moveTo(0f, y)
            var i = 0f
            while (i <= x) {
                path.lineTo(i, y + amp * kotlin.math.sin((i / wl + phase) * 2 * Math.PI).toFloat()); i += 2f
            }
            c.drawPath(path, p)
            p.color = Color.WHITE.withAlpha(90)
            c.drawLine(x + context.dp(6f), y, width.toFloat(), y, p)
            // Thumb: a short vertical bar
            p.color = Color.WHITE; p.strokeWidth = context.dp(4f)
            c.drawLine(x, y - context.dp(8f), x, y + context.dp(8f), p)
            if (playing) { phase -= 0.012f; postInvalidateOnAnimation() }
        }
    }
    private val ticker = object : Runnable {
        override fun run() { updateSeek(); if (isAttachedToWindow) postDelayed(this, 500) }
    }
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = bind()
        override fun onMetadataChanged(metadata: MediaMetadata?) = bind()
    }

    init {
        val radius = ctx.dp(28f)
        background = GradientDrawable().apply { cornerRadius = radius; setColor(pal.primaryContainer) }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
        }
        addView(art, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scrimView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(18f), ctx.dpi(16f), ctx.dpi(16f), ctx.dpi(10f))
        }
        // Top: app icon, output chip
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val appPkg = controller?.packageName ?: e.sbn.packageName
        top.addView(AppAvatar(ctx, pal, AppInfo.icon(ctx, appPkg), null, 24f), LinearLayout.LayoutParams(ctx.dpi(24f), ctx.dpi(24f)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(10f), ctx.dpi(5f), ctx.dpi(12f), ctx.dpi(5f))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(100f); setColor(Color.WHITE.withAlpha(56)) }
            setOnClickListener { host.openSettings(Intent(android.provider.Settings.Panel.ACTION_VOLUME)) }
        }
        val remote = e.n.extras.getCharSequence("android.mediaRemoteDevice")
        chip.addView(SymbolView(ctx, if (remote != null) Sym.CAST else Sym.VOLUME_UP, Color.WHITE, 16f),
            LinearLayout.LayoutParams(ctx.dpi(18f), ctx.dpi(18f)).apply { marginEnd = ctx.dpi(6f) })
        chipText.text = remote ?: "Den här telefonen"
        chipText.maxWidth = ctx.dpi(150f)
        chip.addView(chipText)
        top.addView(chip)
        col.addView(top)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))

        // Middle: title/artist, play button
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title); texts.addView(artist.apply { setPadding(0, ctx.dpi(3f), 0, 0) })
        mid.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        play.setOnClickListener {
            val st = controller?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause() else controller?.transportControls?.play()
        }
        mid.addView(play, LinearLayout.LayoutParams(ctx.dpi(60f), ctx.dpi(48f)).apply { marginStart = ctx.dpi(10f) })
        col.addView(mid)

        // Bottom: previous, progress, next
        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottomRow = bottom
        fun ctl(sym: String, act: () -> Unit) = SymbolView(ctx, sym, Color.WHITE, 24f).apply { setOnClickListener { act() } }
        bottom.addView(ctl(Sym.SKIP_PREVIOUS) { controller?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(ctx.dpi(36f), ctx.dpi(36f)))
        bottom.addView(seek, LinearLayout.LayoutParams(0, ctx.dpi(28f), 1f).apply { marginStart = ctx.dpi(6f); marginEnd = ctx.dpi(6f) })
        bottom.addView(ctl(Sym.SKIP_NEXT) { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(ctx.dpi(36f), ctx.dpi(36f)))
        col.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8f)
        })
        // Seeking only on a deliberate sideways drag: a tap or a vertical swipe (pulling the shade)
        // never moves playback, and a vertical drag is handed back to the shade.
        val slop = ctx.dp(12f)
        var downX = 0f; var downY = 0f; var seeking = false
        seek.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; seeking = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (!seeking && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f && compact < 0.05f) {
                        seeking = true; v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!seeking && kotlin.math.abs(dy) > slop) return@setOnTouchListener false
                    if (seeking) { seek.frac = (ev.x / v.width).coerceIn(0f, 1f); seek.invalidate() }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (seeking) {
                        val dur = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        if (dur > 0) controller?.transportControls?.seekTo((dur * (ev.x / v.width).coerceIn(0f, 1f)).toLong())
                    }
                    seeking = false; true
                }
                else -> { seeking = false; true }
            }
        }
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnClickListener { host.launch(e.n.contentIntent) }
        bind()
    }

    fun setCompact(f: Float) {
        compact = f
        bottomRow?.let { b ->
            b.alpha = (1f - f * 1.6f).coerceIn(0f, 1f)
            val h = (context.dp(36f) * (1f - f)).toInt()
            val lp = b.layoutParams as LinearLayout.LayoutParams
            val top = (context.dp(8f) * (1f - f)).toInt()
            if (lp.height != h || lp.topMargin != top) { lp.height = h; lp.topMargin = top; b.layoutParams = lp }
            b.visibility = if (f > 0.98f) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun applyArtColors(bmp: Bitmap?) {
        if (bmp != null && bmp === lastArt) return
        lastArt = bmp
        var base = pal.primaryContainer.let { if (pal.night) it else android.graphics.Color.rgb(40, 40, 52) }
        var acc = pal.primary
        if (bmp != null) {
            try {
                val sw = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
                val pl = androidx.palette.graphics.Palette.from(sw).maximumColorCount(16).generate()
                base = pl.getDarkMutedColor(pl.getDarkVibrantColor(base))
                acc = pl.getLightVibrantColor(pl.getVibrantColor(pl.getLightMutedColor(acc)))
            } catch (_: Exception) { }
        }
        accent = acc
        scrimView.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(base.withAlpha(if (bmp != null) 235 else 255), base.withAlpha(if (bmp != null) 140 else 255))
        )
        play.background = GradientDrawable().apply { cornerRadius = context.dp(18f); setColor(acc) }
        val lum = android.graphics.Color.luminance(acc)
        play.color = if (lum > 0.45f) Color.BLACK else Color.WHITE
        play.invalidate()
    }

    private fun bind() {
        val md = controller?.metadata
        val ex = e.n.extras
        title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        artist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: (try { e.n.getLargeIcon()?.loadDrawable(context) as? android.graphics.drawable.BitmapDrawable } catch (_: Exception) { null })?.bitmap
        art.setImageBitmap(bmp)
        applyArtColors(bmp)
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        play.symbol = if (playing) Sym.PAUSE else Sym.PLAY_ARROW
        play.invalidate()
        seek.playing = playing
        updateSeek()
    }

    private fun updateSeek() {
        val st = controller?.playbackState ?: return
        val dur = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur <= 0) { seek.visibility = View.INVISIBLE; return }
        seek.visibility = View.VISIBLE
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        seek.frac = (pos.toFloat() / dur).coerceIn(0f, 1f)
        seek.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        controller?.registerCallback(cb)
        post(ticker)
        bind()
    }

    override fun onDetachedFromWindow() {
        controller?.unregisterCallback(cb)
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}

// ---- The whole notification column ------------------------------------------------------

class NotifList(ctx: Context, private val pal: Palette, private val host: NotifHost) : LinearLayout(ctx) {
    var hasClearable = false
        private set

    init { orientation = VERTICAL; clipChildren = false }

    private class Item(val summary: NotifStore.Entry?, val members: List<NotifStore.Entry>) {
        val latest = members.maxOf { it.n.`when`.takeIf { w -> w > 0 } ?: it.sbn.postTime }
        val rank = members.minOf { it.rank }
        val silent = members.all { it.silent }
    }

    fun render(entries: List<NotifStore.Entry>, listenerOn: Boolean) {
        removeAllViews()
        val ctx = context
        if (!listenerOn) {
            addView(ctx.label("Ge Pixelpanel notisåtkomst i appen för att se dina aviseringar här.", 14f, pal.onSurfaceVariant, lines = 0).apply {
                gravity = Gravity.CENTER; setPadding(ctx.dpi(24f), ctx.dpi(24f), ctx.dpi(24f), ctx.dpi(24f))
            })
            hasClearable = false
            return
        }
        val media = entries.filter { it.n.isMedia() }
        // The media player is drawn above the tiles (like Pixel), never in the notification list.
        emptyList<NotifStore.Entry>().forEach {
            try {
                addView(MediaCard(ctx, pal, it, host), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(176f)).apply {
                    bottomMargin = ctx.dpi(12f)
                })
            } catch (t: Throwable) { CrashLog.write(ctx, "media", t) }
        }

        val items = entries.filter { !it.n.isMedia() }
            .groupBy { it.sbn.groupKey }
            .mapNotNull { (_, list) ->
                val summary = list.firstOrNull { it.n.isSummary() }
                val children = list.filter { !it.n.isSummary() }
                when {
                    children.isNotEmpty() -> Item(summary, children.sortedByDescending { it.n.`when` })
                    summary != null && (summary.n.title() != null || summary.n.shortText() != null) -> Item(null, listOf(summary))
                    else -> null
                }
            }
            .sortedWith(compareBy<Item> { it.rank }.thenByDescending { it.latest })

        val alerting = items.filter { !it.silent }
        val silent = items.filter { it.silent }
        hasClearable = items.any { i -> i.members.any { it.sbn.isClearable } }

        fun stackOf(list: List<Item>): NotifStack = NotifStack(ctx).apply {
            onDismiss = { card -> host.dismiss(card.dismissKeys) }
            list.forEach { i ->
                try {
                    val v = if (i.members.size >= 2) GroupCardView(ctx, pal, i.summary, i.members, host)
                    else NotifCardView(ctx, pal, i.members.first(), host, listOfNotNull(i.summary?.key))
                    addCard(v)
                } catch (t: Throwable) {
                    CrashLog.write(ctx, "kort ${i.members.first().sbn.packageName}", t)
                }
            }
        }

        if (alerting.isNotEmpty()) addView(stackOf(alerting), matchWrap())
        if (silent.isNotEmpty()) {
            addView(ctx.label("Tyst", 14f, pal.onSurfaceVariant, medium = true).apply {
                setPadding(ctx.dpi(8f), ctx.dpi(if (alerting.isEmpty()) 4f else 18f), 0, ctx.dpi(10f))
            })
            addView(stackOf(silent), matchWrap())
        }
        if (items.isEmpty() && media.isEmpty()) {
            addView(ctx.label("Inga aviseringar", 15f, pal.onSurfaceVariant).apply {
                gravity = Gravity.CENTER; setPadding(0, ctx.dpi(28f), 0, ctx.dpi(28f))
            }, matchWrap())
        }
    }

    fun clearAll() {
        for (i in 0 until childCount) (getChildAt(i) as? NotifStack)?.clearAll()
    }

    private fun matchWrap() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
