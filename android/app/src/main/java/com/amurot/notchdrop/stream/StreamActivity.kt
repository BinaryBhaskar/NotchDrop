package com.amurot.notchdrop.stream

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.amurot.notchdrop.service.Node

/** Invisible: asks for camera permission or screen-capture consent, starts StreamService, and closes. */
class StreamActivity : ComponentActivity() {
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) StreamService.startCamera(this) else toast("NotchDrop needs the camera to stream it")
        finish()
    }

    private val screen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) StreamService.startScreen(this, r.resultCode, data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Node.init(this)
        if (savedInstanceState != null) return // a request is already showing
        if (Node.store.peer == null) {
            toast("Pair with your Mac first")
            finish()
            return
        }
        when (intent.getStringExtra(EXTRA_KIND)) {
            StreamService.KIND_CAMERA ->
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    StreamService.startCamera(this)
                    finish()
                } else {
                    camera.launch(Manifest.permission.CAMERA)
                }
            StreamService.KIND_SCREEN -> screen.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            else -> finish()
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(ctx: Context, kind: String): Intent =
            Intent(ctx, StreamActivity::class.java).putExtra(EXTRA_KIND, kind)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
