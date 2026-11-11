package se.rob.pixelpanel

import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Receives every notification on the phone and hands them to [NotifStore]. Samsung's own shade keeps working. */
class NotifService : NotificationListenerService() {
    private val h = Handler(Looper.getMainLooper())
    private val refresh = Runnable { push() }

    override fun onListenerConnected() {
        NotifStore.service = this
        push()
        NotifStore.notifyDnd()
    }

    override fun onListenerDisconnected() {
        NotifStore.service = null
        NotifStore.set(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = schedule()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = schedule()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = schedule()
    override fun onInterruptionFilterChanged(interruptionFilter: Int) = NotifStore.notifyDnd()

    private fun schedule() {
        h.removeCallbacks(refresh)
        h.postDelayed(refresh, 40)
    }

    private fun push() {
        val list = try { activeNotifications?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val rm = try { currentRanking } catch (_: Exception) { null }
        val r = Ranking()
        NotifStore.set(list.map { sbn ->
            val silent = rm != null && rm.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT
            NotifStore.Entry(sbn, silent, if (rm != null && rm.getRanking(sbn.key, r)) r.rank else 0)
        })
    }
}

object NotifStore {
    class Entry(val sbn: StatusBarNotification, val silent: Boolean, val rank: Int) {
        val key: String get() = sbn.key
        val n get() = sbn.notification
    }

    @Volatile var service: NotifService? = null
    var entries: List<Entry> = emptyList()
        private set
    private val listeners = LinkedHashSet<() -> Unit>()
    private val dndListeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun addDndListener(l: () -> Unit) { dndListeners += l }

    fun set(list: List<Entry>) {
        entries = list
        listeners.toList().forEach { it() }
    }

    fun notifyDnd() = dndListeners.toList().forEach { it() }

    fun cancel(keys: List<String>) {
        val s = service ?: return
        try { s.cancelNotifications(keys.toTypedArray()) } catch (_: Exception) { }
    }

    fun dndOn(): Boolean {
        val s = service ?: return false
        val f = try { s.currentInterruptionFilter } catch (_: Exception) { return false }
        return f != NotificationListenerService.INTERRUPTION_FILTER_ALL &&
            f != NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN
    }

    fun setDnd(on: Boolean): Boolean {
        val s = service ?: return false
        return try {
            s.requestInterruptionFilter(
                if (on) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
                else NotificationListenerService.INTERRUPTION_FILTER_ALL
            ); true
        } catch (_: Exception) { false }
    }
}
