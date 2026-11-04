package com.droiddeck.launcher.runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.droiddeck.launcher.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChrootConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.chroot_root_title)
            .setMessage(R.string.chroot_root_message)
            .setPositiveButton(R.string.chroot_root_allow) { _, _ -> answer(true) }
            .setNegativeButton(R.string.chroot_root_deny) { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .setOnDismissListener { finish() }
            .show()
    }

    private fun answer(allowed: Boolean) {
        synchronized(LOCK) {
            granted = allowed
            pending?.countDown()
        }
    }

    companion object {
        private val LOCK = Any()
        private var pending: CountDownLatch? = null
        private var granted = false

        fun ask(context: Context): Boolean {
            val latch = CountDownLatch(1)
            synchronized(LOCK) {
                if (pending != null) return false
                pending = latch
                granted = false
            }
            try {
                context.startActivity(Intent(context, ChrootConfirmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                latch.await(30, TimeUnit.SECONDS)
                return synchronized(LOCK) { granted }
            } finally {
                synchronized(LOCK) { pending = null }
            }
        }
    }
}
