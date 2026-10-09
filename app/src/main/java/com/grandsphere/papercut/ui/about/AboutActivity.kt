package com.grandsphere.papercut.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grandsphere.papercut.BuildConfig
import com.grandsphere.papercut.R
import com.grandsphere.papercut.ui.settings.SettingsViewModel
import com.grandsphere.papercut.ui.chrome.PapercutScreenBar
import com.grandsphere.papercut.ui.chrome.applyPapercutImmersive
import com.grandsphere.papercut.ui.chrome.hidePapercutSystemBars
import com.grandsphere.papercut.ui.theme.PapercutTheme

class AboutActivity : ComponentActivity() {
    private val vm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.applyPapercutImmersive(0xFF000000.toInt())
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            window.applyPapercutImmersive(settings.bgColor)
            PapercutTheme(
                fontScale = settings.fontScale,
                appFontColor = settings.appFontColor,
                backgroundColor = settings.bgColor,
                actionColor = settings.actionColor,
            ) {
                AboutScreen(onBack = { finish() })
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hidePapercutSystemBars()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        PapercutScreenBar(title = "About", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_fg),
                contentDescription = "Papercut",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("Papercut", fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Grand Sphere Studios",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Version: ${BuildConfig.VERSION_NAME}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Text("License:", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                AboutLink(
                    title = "GNU General Public License 3",
                    subtitle = "(GPLv3)",
                    url = "https://github.com/GrandSphere/Papercut/blob/master/LICENSE",
                )
                Spacer(Modifier.height(16.dp))
                Text("Github:", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                AboutLink(
                    title = "Github Repository",
                    url = "https://github.com/GrandSphere/Papercut",
                )
                AboutLink(
                    title = "Latest Release",
                    url = "https://github.com/GrandSphere/Papercut/releases/latest",
                )
                Spacer(Modifier.height(16.dp))
                Text("Donate:", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                AboutLink(title = "Ko-fi", url = "https://ko-fi.com/grandspherestudios")
                AboutLink(title = "Liberapay", url = "https://liberapay.com/GrandSphere")
            }
        }
    }
}

@Composable
private fun AboutLink(title: String, url: String, subtitle: String? = null) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = "Open link",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}
