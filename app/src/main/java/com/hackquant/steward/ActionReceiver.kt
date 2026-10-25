package com.hackquant.steward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val APPROVE = "com.hackquant.steward.APPROVE"
        const val DISMISS = "com.hackquant.steward.DISMISS"
        const val REMIND = "com.hackquant.steward.REMIND"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        when (intent.action) {
            APPROVE -> {
                val id = intent.getLongExtra("activity", -1)
                val pending = goAsync()
                Thread { try { Tools.approve(ctx, id, null) } finally { pending.finish() } }.start()
            }
            DISMISS -> Tools.dismiss(ctx, intent.getLongExtra("activity", -1), null)
            REMIND -> Tools.notify(ctx, intent.getIntExtra("nid", 0), "Reminder from Alfred", intent.getStringExtra("text") ?: return)
        }
    }
}
