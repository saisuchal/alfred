package com.hackquant.steward

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Test hook for running scenarios over adb. Protected by the DUMP permission, which only the shell
 * (adb) holds, so other apps can't inject messages.
 *
 *   adb shell am broadcast -n com.hackquant.steward/.TestReceiver -a com.hackquant.steward.TEST \
 *       --es from "Praveen" --es text "are you free at 8?" [--es chat "Gang"]
 *   adb shell am broadcast -n com.hackquant.steward/.TestReceiver -a com.hackquant.steward.TEST_ANSWER \
 *       --es from "Praveen" --es answer "yes"
 *   adb shell am broadcast -n com.hackquant.steward/.TestReceiver -a com.hackquant.steward.TEST_COMMAND --es text "what's pending"
 *   adb shell am broadcast -n com.hackquant.steward/.TestReceiver -a com.hackquant.steward.TEST_FOLLOWUP --es from "Praveen"
 *
 * Replies Alfred sends to a test chat land in files/test_sent.log instead of a real app.
 */
class TestReceiver : BroadcastReceiver() {
    companion object {
        const val TEST = "com.hackquant.steward.TEST"
        const val ANSWER = "com.hackquant.steward.TEST_ANSWER"
        const val COMMAND = "com.hackquant.steward.TEST_COMMAND"
        const val FOLLOWUP = "com.hackquant.steward.TEST_FOLLOWUP"
        const val SINK = "com.hackquant.steward.TEST_SINK"
        private const val KEY = "test_reply"
    }

    private fun log(ctx: Context, line: String) = TestSink.log(ctx, line)

    private fun latest(from: String) = Store.activity.value.firstOrNull { it.msg.sender.equals(from, true) }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val from = intent.getStringExtra("from") ?: "Tester"
        when (intent.action) {
            TEST -> {
                val text = intent.getStringExtra("text") ?: return
                val chat = intent.getStringExtra("chat")
                // A fake inline-reply action that delivers whatever Alfred sends back to SINK below.
                val sink = PendingIntent.getBroadcast(ctx, from.hashCode(),
                    Intent(ctx, TestSink::class.java).setAction(SINK).putExtra("from", from),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val action = Notification.Action.Builder(null, "Reply", sink)
                    .addRemoteInput(RemoteInput.Builder(KEY).build()).build()
                val sender = if (chat != null) "$from in $chat" else from
                // Optional: put the test sender in a circle first (family, friends, work, others).
                intent.getStringExtra("circle")?.let { circle -> Store.update { it.copy(contacts = it.contacts + (sender to circle)) } }
                Agent.submit(ctx, Incoming(sender, "Test chat", "test", text,
                    isGroup = chat != null, chat = chat, replyId = Replies.register(action)))
            }
            ANSWER -> {
                val a = latest(from) ?: return
                val answer = intent.getStringExtra("answer") ?: return
                val pending = goAsync()
                Thread { try { log(ctx, "${System.currentTimeMillis()}\tOWNER\t" + Owner.answer(ctx, a.id, answer)) } finally { pending.finish() } }.start()
            }
            COMMAND -> {
                val text = intent.getStringExtra("text") ?: return
                val pending = goAsync()
                Thread { try { log(ctx, "${System.currentTimeMillis()}\tASK\t" + Owner.command(ctx, text)) } finally { pending.finish() } }.start()
            }
            FOLLOWUP -> {
                val a = latest(from) ?: return
                val pending = goAsync()
                Thread { try { Owner.followUp(ctx, a.id) } finally { pending.finish() } }.start()
            }
        }
    }
}

/** Receives what Alfred "sends" to a test chat. Internal only: Alfred itself is the sender. */
class TestSink : BroadcastReceiver() {
    companion object {
        fun log(ctx: Context, line: String) = File(ctx.filesDir, "test_sent.log").appendText(line.replace("\n", " ⏎ ") + "\n")
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val sent = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("test_reply")?.toString() ?: return
        log(ctx, "${System.currentTimeMillis()}\t${intent.getStringExtra("from")}\t$sent")
    }
}
