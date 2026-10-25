package com.hackquant.steward

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import com.hackquant.steward.ui.HomeScreen
import com.hackquant.steward.ui.Onboarding
import com.hackquant.steward.ui.PeopleScreen
import com.hackquant.steward.ui.Perms
import com.hackquant.steward.ui.RulesScreen
import com.hackquant.steward.ui.SettingsScreen
import com.hackquant.steward.ui.StewardTheme

class MainActivity : ComponentActivity() {
    private var perms by mutableStateOf<Perms?>(null)
    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        Llm.load(this)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        refresh()

        setContent {
            StewardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val config by Store.config.collectAsState()
                    val p = perms ?: return@Surface
                    if (!config.onboarded) Box(Modifier.safeDrawingPadding()) { Onboarding(p) } else App(p)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // Settings may have changed; prepare the new prompt so the next message is fast.
        Agent.warm()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        perms = Perms(
            notificationAccess = packageName in NotificationManagerCompat.getEnabledListenerPackages(this),
            calendar = granted(Manifest.permission.WRITE_CALENDAR),
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            openNotificationAccess = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            requestCalendar = { request.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            requestLocation = {
                request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            requestBackgroundLocation = { request.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) },
        )
    }
}

@Composable
private fun App(perms: Perms) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsReset by rememberSaveable { mutableIntStateOf(0) }
    val acts by Store.activity.collectAsState()
    val pending = acts.sumOf { a -> a.entries.count { it.status == Status.PENDING } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            NavigationBarItem(tab == 0, { tab = 0 }, icon = {
                BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) { Icon(Icons.Filled.Home, null) }
            }, label = { Text("Home") })
            NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Filled.Person, null) }, label = { Text("People") })
            NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Rules") })
            NavigationBarItem(tab == 3, { if (tab == 3) settingsReset++ else tab = 3 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(perms) { tab = 3 }
                1 -> PeopleScreen()
                2 -> RulesScreen()
                else -> SettingsScreen(perms, settingsReset)
            }
        }
    }
}
