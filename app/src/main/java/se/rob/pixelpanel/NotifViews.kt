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
private fun <T : Parcelable> Bundle.parcel(key: String): T? = try { get(key) as? T } catch (_: Exception) { null }

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
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.card) }
                setPadding(ctx.dpi(1.5f), ctx.dpi(1.5f), ctx.dpi(1.5f), ctx.dpi(1.5f))
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }, LayoutParams(b, b, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = -ctx.dpi(2f); bottomMargin = -ctx.dpi(2f)
            })
        }
        clipChildren = false
    }
}

private fun chevron(ctx: Context, pal: Palette, expanded: Boolean, onClick: () -> Unit) =
    SymbolView(ctx, if (expanded) Sym.EXPAND_LESS else Sym.EXPAND_MORE, pal.onSurface, 20f).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.surfaceContainer) }
        setOnClickListener { onClick() }
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
    private val chevronView: SymbolView
    private var replyRow: LinearLayout? = null

    init {
        orientation = VERTICAL
        background = bg
        setPadding(ctx.dpi(16f), ctx.dpi(14f), ctx.dpi(12f), ctx.dpi(14f))

        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
        val pkg = e.sbn.packageName
        val appIcon = AppInfo.icon(ctx, pkg)
        val large = try { n.getLargeIcon()?.loadDrawable(ctx) } catch (_: Exception) { null }
        val avatar = if (n.isMessaging() && large != null) AppAvatar(ctx, pal, large, appIcon, 40f)
        else AppAvatar(ctx, pal, appIcon, null, 40f)
        row.addView(avatar, LayoutParams(ctx.dpi(40f), ctx.dpi(40f)).apply { marginEnd = ctx.dpi(14f) })

        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        val sub = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        val head = buildString {
            append(AppInfo.label(ctx, pkg))
            if (!sub.isNullOrBlank()) append(" · ").append(sub)
            if (n.`when` > 0 && n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)) append(" · ").append(relativeTime(ctx, n.`when`))
        }
        col.addView(ctx.label(head, 12f, pal.onSurfaceVariant))
        val title = n.title()
        if (!title.isNullOrBlank()) col.addView(ctx.label(title, 15f, pal.onSurface, medium = true).apply {
            setPadding(0, ctx.dpi(3f), 0, 0)
        })
        textView = ctx.label(n.shortText(), 14f, pal.onSurfaceVariant).apply {
            setPadding(0, ctx.dpi(2f), 0, 0)
            setLineSpacing(ctx.dp(2f), 1f)
        }
        if (!n.shortText().isNullOrBlank() || !n.longText().isNullOrBlank()) col.addView(textView)
        row.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (large != null && !n.isMessaging()) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(large); scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = ctx.dp(10f); setColor(pal.surfaceContainer) }
                clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            }, LayoutParams(ctx.dpi(44f), ctx.dpi(44f)).apply { marginStart = ctx.dpi(10f) })
        }

        chevronView = chevron(ctx, pal, expanded) { toggle() }
        row.addView(chevronView, LayoutParams(ctx.dpi(28f), ctx.dpi(28f)).apply { marginStart = ctx.dpi(8f) })
        addView(row)

        buildExpandArea()
        addView(expandArea, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dpi(54f)
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
        chevronView.symbol = if (expanded) Sym.EXPAND_LESS else Sym.EXPAND_MORE
        chevronView.invalidate()
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
    private val chevronView: SymbolView

    init {
        orientation = VERTICAL
        background = bg
        setPadding(ctx.dpi(16f), ctx.dpi(14f), ctx.dpi(12f), ctx.dpi(12f))
        val pkg = children.first().sbn.packageName
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(AppAvatar(ctx, pal, AppInfo.icon(ctx, pkg), null, 40f), LayoutParams(ctx.dpi(40f), ctx.dpi(40f)).apply { marginEnd = ctx.dpi(14f) })
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        val latest = children.maxOf { it.n.`when` }
        col.addView(ctx.label("${AppInfo.label(ctx, pkg)} · ${relativeTime(ctx, latest)}", 12f, pal.onSurfaceVariant))
        val sumTitle = summary?.n?.title() ?: summary?.n?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
        col.addView(ctx.label(sumTitle ?: "${children.size} aviseringar", 15f, pal.onSurface, medium = true).apply {
            setPadding(0, ctx.dpi(3f), 0, 0)
        })
        row.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val count = ctx.label("${children.size}", 12f, pal.onSurface, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(8f), ctx.dpi(3f), ctx.dpi(8f), ctx.dpi(3f))
            background = GradientDrawable().apply { setColor(pal.surfaceContainer); cornerRadius = ctx.dp(100f) }
        }
        row.addView(count)
        chevronView = chevron(ctx, pal, expanded) { toggle() }
        row.addView(chevronView, LayoutParams(ctx.dpi(28f), ctx.dpi(28f)).apply { marginStart = ctx.dpi(8f) })
        addView(row)
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dpi(54f); topMargin = ctx.dpi(6f)
        })
        fillList()
        setOnClickListener { host.launch(summary?.n?.contentIntent ?: children.first().n.contentIntent) }
    }

    private fun fillList() {
        list.removeAllViews()
        val shown = if (expanded) children else children.take(2)
        shown.forEach { c ->
            val r = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, context.dpi(6f), 0, context.dpi(6f))
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
        chevronView.symbol = if (expanded) Sym.EXPAND_LESS else Sym.EXPAND_MORE
        chevronView.invalidate()
        fillList()
    }
}

// ---- Media player -------------------------------------------------------------------------

class MediaCard(ctx: Context, private val pal: Palette, private val e: NotifStore.Entry, private val host: NotifHost) : FrameLayout(ctx) {
    private val controller: MediaController? = run {
        @Suppress("DEPRECATION")
        val token = e.n.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        token?.let { try { MediaController(ctx, it) } catch (_: Exception) { null } }
    }
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = ctx.label("", 17f, Color.WHITE, medium = true)
    private val artist = ctx.label("", 14f, Color.WHITE.withAlpha(210))
    private val play = SymbolView(ctx, Sym.PLAY_ARROW, pal.onPrimary, 28f)
    private val seek = object : View(ctx) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = RectF()
        var frac = 0f
        override fun onDraw(c: Canvas) {
            val h = context.dp(4f); val y = height / 2f
            p.color = Color.WHITE.withAlpha(90); r.set(0f, y - h / 2, width.toFloat(), y + h / 2); c.drawRoundRect(r, h, h, p)
            p.color = Color.WHITE; r.set(0f, y - h / 2, width * frac, y + h / 2); c.drawRoundRect(r, h, h, p)
            c.drawCircle(width * frac, y, context.dp(6f), p)
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
        background = GradientDrawable().apply { cornerRadius = ctx.dp(28f); setColor(pal.surfaceContainerHigh) }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, ctx.dp(28f)) }
        }
        addView(art, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66000000, 0xCC000000.toInt()))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(20f), ctx.dpi(18f), ctx.dpi(20f), ctx.dpi(14f))
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(AppAvatar(ctx, pal, AppInfo.icon(ctx, e.sbn.packageName), null, 22f), LinearLayout.LayoutParams(ctx.dpi(22f), ctx.dpi(22f)).apply { marginEnd = ctx.dpi(8f) })
        top.addView(ctx.label(AppInfo.label(ctx, e.sbn.packageName), 12f, Color.WHITE.withAlpha(220)))
        col.addView(top)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))

        val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(title); texts.addView(artist.apply { setPadding(0, ctx.dpi(2f), 0, 0) })
        bottom.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun ctl(sym: String, sizeDp: Float, act: () -> Unit) = SymbolView(ctx, sym, Color.WHITE, sizeDp).apply { setOnClickListener { act() } }
        bottom.addView(ctl(Sym.SKIP_PREVIOUS, 26f) { controller?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f)))
        play.background = GradientDrawable().apply { cornerRadius = ctx.dp(18f); setColor(pal.primary) }
        play.setOnClickListener {
            val s = controller?.playbackState?.state
            if (s == PlaybackState.STATE_PLAYING) controller?.transportControls?.pause() else controller?.transportControls?.play()
        }
        bottom.addView(play, LinearLayout.LayoutParams(ctx.dpi(56f), ctx.dpi(48f)).apply { marginStart = ctx.dpi(4f); marginEnd = ctx.dpi(4f) })
        bottom.addView(ctl(Sym.SKIP_NEXT, 26f) { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(ctx.dpi(40f), ctx.dpi(40f)))
        col.addView(bottom)
        col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(20f)).apply { topMargin = ctx.dpi(10f) })
        seek.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val dur = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (dur > 0) controller?.transportControls?.seekTo((dur * (ev.x / v.width).coerceIn(0f, 1f)).toLong())
            }
            true
        }
        addView(col, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnClickListener { host.launch(e.n.contentIntent) }
        bind()
    }

    private fun bind() {
        val md = controller?.metadata
        val ex = e.n.extras
        title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ex.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        artist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (bmp != null) art.setImageBitmap(bmp)
        else art.setImageDrawable(try { e.n.getLargeIcon()?.loadDrawable(context) } catch (_: Exception) { null })
        val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        play.symbol = if (playing) Sym.PAUSE else Sym.PLAY_ARROW
        play.invalidate()
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
        media.sortedByDescending { it.sbn.postTime }.take(2).forEach {
            addView(MediaCard(ctx, pal, it, host), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(168f)).apply {
                bottomMargin = ctx.dpi(12f)
            })
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
                val v = if (i.members.size >= 2) GroupCardView(ctx, pal, i.summary, i.members, host)
                else NotifCardView(ctx, pal, i.members.first(), host, listOfNotNull(i.summary?.key))
                addCard(v)
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
