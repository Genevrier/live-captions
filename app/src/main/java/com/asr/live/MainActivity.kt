package com.asr.live

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.asr.live.ui.AsrTheme
import com.asr.live.ui.CaptionScreen
import com.asr.live.ui.CaptionViewModel

class MainActivity : ComponentActivity() {

    private val vm: CaptionViewModel by viewModels()
    private var hasAudio by mutableStateOf(false)
    private var hasOverlay by mutableStateOf(false)

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasAudio = isAudioGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasAudio = isAudioGranted()
        hasOverlay = Settings.canDrawOverlays(this)

        setContent {
            AsrTheme {
                CaptionScreen(
                    vm = vm,
                    hasAudioPermission = hasAudio,
                    onRequestPermission = ::requestPermissions,
                    hasOverlayPermission = hasOverlay,
                    onRequestOverlayPermission = {
                        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                            .onFailure { com.asr.live.service.CaptionState.setError("Open Android Settings → Special app access → Display over other apps → LiveTranslate.") }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAudio = isAudioGranted()
        hasOverlay = Settings.canDrawOverlays(this)
        vm.refreshPresence()
    }

    private fun isAudioGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permissions.launch(perms.toTypedArray())
    }
}
