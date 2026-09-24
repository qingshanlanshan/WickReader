package com.qshlab.shelf

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qshlab.shelf.catalog.CatalogsScreen
import com.qshlab.shelf.catalog.FeedScreen
import com.qshlab.shelf.reader.ReaderActivity
import com.qshlab.shelf.ui.GroupKind
import com.qshlab.shelf.ui.GroupScreen
import com.qshlab.shelf.ui.LibraryScreen
import com.qshlab.shelf.ui.LibraryViewModel

class MainActivity : ComponentActivity() {
    private var vm: LibraryViewModel? = null

    /** EPUB/TXT opened with or shared to the app. */
    private fun handleIncoming(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        intent?.action = null // handle once
        vm?.importAndOpen(uri) { id -> startActivity(ReaderActivity.intent(this, id)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShelfTheme {
                val nav = rememberNavController()
                val vm: LibraryViewModel = viewModel()
                androidx.compose.runtime.LaunchedEffect(vm) {
                    this@MainActivity.vm = vm
                    if (savedInstanceState == null) handleIncoming(intent)
                }
                val openBook = { book: com.qshlab.shelf.data.Book ->
                    vm.open(book) { id -> startActivity(ReaderActivity.intent(this, id)) }
                }
                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            vm = vm,
                            onOpenBook = openBook,
                            onOpenSeries = { nav.navigate("series/${Uri.encode(it)}") },
                            onOpenAuthor = { nav.navigate("author/${Uri.encode(it)}") },
                            onOpenCatalogs = { nav.navigate("catalogs") },
                        )
                    }
                    composable("catalogs") {
                        CatalogsScreen(onBack = { nav.popBackStack() }, onOpen = { c -> nav.navigate("feed?title=${Uri.encode(c.name)}&url=${Uri.encode(c.url)}") })
                    }
                    composable("feed?title={title}&url={url}") { entry ->
                        FeedScreen(
                            title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                            url = Uri.decode(entry.arguments?.getString("url").orEmpty()),
                            onBack = { nav.popBackStack() },
                            onOpenFeed = { t, u -> nav.navigate("feed?title=${Uri.encode(t)}&url=${Uri.encode(u)}") },
                        )
                    }
                    composable("series/{name}") { entry ->
                        GroupScreen(vm, GroupKind.SERIES, Uri.decode(entry.arguments?.getString("name").orEmpty()), { nav.popBackStack() }, openBook)
                    }
                    composable("author/{name}") { entry ->
                        GroupScreen(vm, GroupKind.AUTHOR, Uri.decode(entry.arguments?.getString("name").orEmpty()), { nav.popBackStack() }, openBook)
                    }
                }
            }
        }
    }
}

@Composable
fun ShelfTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8CC7B0))
        else -> lightColorScheme(primary = Color(0xFF2E5E4E), secondaryContainer = Color(0xFFD9EDE3))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
