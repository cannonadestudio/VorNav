package pl.pilot.vornav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import pl.pilot.vornav.ui.*
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NavGreen, secondary = Amber, background = Color(0xFF0B1A2B),
                    surface = Color(0xFF0B1A2B), onPrimary = Color.Black
                )
            ) {
                App(vm, hasPermission())
            }
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Mapa", Icons.Filled.Map),
    NAV("NAV", Icons.Filled.Explore),
    ROUTE("Trasa", Icons.Filled.Timeline),
    TRAIN("Trening", Icons.Filled.School),
    SEARCH("Szukaj", Icons.Filled.Search),
    FIX("Korekta", Icons.Filled.GpsFixed),
    DB("Baza", Icons.Filled.Storage),
}

@Composable
private fun App(vm: NavViewModel, permissionGranted: Boolean) {
    var tab by rememberSaveable { mutableStateOf(Tab.NAV) }
    val snack = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true) vm.startGps()
    }
    LaunchedEffect(Unit) {
        if (permissionGranted) vm.startGps()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(toast) {
        toast?.let { snack.showSnackbar(it); vm.toast.value = null }
    }

    val ilsRes by vm.ilsResult.collectAsState()
    ilsRes?.let { r ->
        IlsResultDialog(r, onRetry = { vm.repeatIlsPractice(); tab = Tab.MAP }, onClose = { vm.ilsResult.value = null })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF07111C)) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.MAP -> MapScreen(vm)
                Tab.NAV -> NavScreen(vm)
                Tab.ROUTE -> RouteScreen(vm)
                Tab.TRAIN -> TrainerScreen(vm)
                Tab.SEARCH -> SearchScreen(vm)
                Tab.FIX -> FixScreen(vm)
                Tab.DB -> DbScreen(vm)
            }
        }
    }
}
