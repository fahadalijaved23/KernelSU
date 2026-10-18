package me.weishu.kernelsu.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.util.execKsud

private const val TAG = "SuRequest"

/**
 * Popup shown by the su-request broker daemon (`ksud sudaemon`) when an app that
 * has no app profile yet calls `su`. The daemon launches this activity with the
 * request id, uid and pid; we grant or deny, then relay the decision back to the
 * daemon via `ksud su-reply`, which unblocks the waiting `su` caller.
 */
class SuRequestActivity : Activity() {

    private var reqId: Long = -1
    private var uid: Int = -1
    private var decided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        reqId = intent.getLongExtra("ksu_reqid", -1)
        uid = intent.getIntExtra("ksu_uid", -1)
        val pid = intent.getIntExtra("ksu_pid", -1)

        if (reqId < 0 || uid < 0) {
            Log.w(TAG, "invalid su request intent: reqid=$reqId uid=$uid")
            finish()
            return
        }

        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull()
        val label = pkg?.let {
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it, 0)
                ).toString()
            }.getOrNull()
        } ?: pkg ?: getString(R.string.su_request_unknown_app)

        val appKey = pkg ?: uid.toString()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.su_request_title))
            .setMessage(getString(R.string.su_request_message, label, uid, pid))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.su_request_grant)) { _, _ ->
                decide(true, appKey)
            }
            .setNegativeButton(getString(R.string.su_request_deny)) { _, _ ->
                decide(false, appKey)
            }
            .setOnDismissListener {
                // If dismissed without a choice, fail closed.
                if (!decided) decide(false, appKey)
            }
            .show()
    }

    private fun decide(allow: Boolean, appKey: String) {
        if (decided) return
        decided = true

        CoroutineScope(Dispatchers.IO).launch {
            if (allow) {
                // Persist the grant so future su calls take the normal path and
                // don't prompt again. A plain deny is one-time (no profile set).
                val granted = runCatching {
                    Natives.setAppProfile(
                        Natives.Profile(
                            name = appKey,
                            currentUid = uid,
                            allowSu = true,
                        )
                    )
                }.getOrDefault(false)
                if (!granted) Log.e(TAG, "failed to set app profile for uid=$uid")
            }

            val decision = if (allow) "allow" else "deny"
            val ok = runCatching { execKsud("su-reply --id $reqId --$decision") }
                .getOrDefault(false)
            if (!ok) Log.e(TAG, "failed to deliver su decision ($decision) for #$reqId")

            runOnUiThread { finish() }
        }
    }
}
