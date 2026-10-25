package com.hackquant.steward

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val APPROVE = "com.hackquant.steward.APPROVE"
        const val DISMISS = "com.hackquant.steward.DISMISS"
        const val REMIND = "com.hackquant.steward.REMIND"
        const val ANSWER = "com.hackquant.steward.ANSWER"
        const val FOLLOWUP = "com.hackquant.steward.FOLLOWUP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val id = intent.getLongExtra("activity", -1)
        fun background(work: () -> Unit) {
            val pending = goAsync()
            Thread { try { work() } finally { pending.finish() } }.start()
        }
        when (intent.action) {
            APPROVE -> background { Tools.approve(ctx, id, null) }
            DISMISS -> Tools.dismiss(ctx, id, null)
            REMIND -> Tools.notify(ctx, intent.getIntExtra("nid", 0), "Reminder from Alfred", intent.getStringExtra("text") ?: return)
            // A quick-answer button carries its answer; the Reply box carries typed text.
            ANSWER -> {
                val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Owner.ANSWER_KEY)?.toString()
                val answer = typed ?: intent.getStringExtra("answer") ?: return
                background {
                    val result = Owner.answer(ctx, id, answer)
                    Tools.notify(ctx, id.hashCode() + 11, "Alfred", result)
                }
            }
            FOLLOWUP -> background { Owner.followUp(ctx, id) }
        }
    }
}
