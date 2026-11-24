package io.github.ciandunlevy.catchup.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import io.github.ciandunlevy.catchup.R
import io.github.ciandunlevy.catchup.data.Store
import io.github.ciandunlevy.catchup.model.Level
import io.github.ciandunlevy.catchup.model.Logic
import io.github.ciandunlevy.catchup.notify.ActionReceiver
import io.github.ciandunlevy.catchup.ui.MainActivity

/** Home-screen widget: the 4 most urgent people. Tap a name to log a catch-up without opening the app. */
class CatchUpWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    companion object {
        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)
        private val DOTS = intArrayOf(R.id.row0_dot, R.id.row1_dot, R.id.row2_dot, R.id.row3_dot)
        private val NAMES = intArrayOf(R.id.row0_name, R.id.row1_name, R.id.row2_name, R.id.row3_name)
        private val STATUSES = intArrayOf(R.id.row0_status, R.id.row1_status, R.id.row2_status, R.id.row3_status)

        private const val OK = 0xFF5FCF7E.toInt()
        private const val SOON = 0xFFF2B84B.toInt()
        private const val OVER = 0xFFFF7070.toInt()

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, CatchUpWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            val views = build(context)
            runCatching { manager.updateAppWidget(ids, views) }
        }

        private fun build(context: Context): RemoteViews {
            val now = System.currentTimeMillis()
            val people = Logic.sorted(Store.people.value, now)
            val v = RemoteViews(context.packageName, R.layout.widget_catchup)

            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.header, open)
            v.setOnClickPendingIntent(R.id.empty, open)

            val statuses = people.map { Logic.status(it, now) }
            val over = statuses.count { it.level == Level.OVER }
            val soon = statuses.count { it.level == Level.SOON }
            v.setTextViewText(R.id.header, when {
                people.isEmpty() -> "Catch-Up"
                over > 0 && soon > 0 -> "Catch-Up · $over overdue · $soon soon"
                over > 0 -> "Catch-Up · $over overdue"
                soon > 0 -> "Catch-Up · $soon due soon"
                else -> "Catch-Up · all caught up"
            })
            v.setViewVisibility(R.id.empty, if (people.isEmpty()) View.VISIBLE else View.GONE)

            for (i in ROWS.indices) {
                val p = people.getOrNull(i)
                if (p == null) { v.setViewVisibility(ROWS[i], View.GONE); continue }
                val s = statuses[i]
                val color = when (s.level) { Level.OK -> OK; Level.SOON -> SOON; Level.OVER -> OVER }
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                v.setTextViewText(NAMES[i], p.name)
                v.setTextColor(DOTS[i], color)
                v.setTextColor(STATUSES[i], color)
                v.setTextViewText(STATUSES[i], when {
                    s.left < 0 -> "${-s.left}d overdue"
                    s.left == 0 -> "due today"
                    else -> "in ${s.left}d"
                })
                val tap = Intent(context, ActionReceiver::class.java).apply {
                    action = ActionReceiver.ACTION_TAP
                    data = Uri.parse("catchup://tap/${p.id}")
                    putExtra(ActionReceiver.EXTRA_PERSON, p.id)
                }
                v.setOnClickPendingIntent(ROWS[i], PendingIntent.getBroadcast(context, 100 + i, tap,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            return v
        }
    }
}
