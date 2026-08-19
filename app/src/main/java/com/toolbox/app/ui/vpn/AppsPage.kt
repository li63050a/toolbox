package com.toolbox.app.ui.vpn

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.VpnConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VpnUI"

private data class AppInfo(val pkg: String, val label: String)

@Composable
fun AppsPage(context: Context, scope: CoroutineScope, snackbar: SnackbarHostState) {
    val config by VpnConfigStore.config.collectAsState()
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        apps = withContext(Dispatchers.IO) {
            runCatching { loadLauncherApps(context) }
                .onFailure { Log.e(TAG, "Failed to read installed apps list", it) }
                .getOrDefault(emptyList())
        }
        loading = false
    }

    fun toggle(app: AppInfo) {
        val adding = app.pkg !in config.blockedApps
        mutateConfig(context, scope, snackbar, if (adding) context.getString(R.string.applist_exclude, app.label) else context.getString(R.string.applist_restore, app.label)) { c ->
            val set = c.blockedApps.toMutableSet()
            if (adding) set.add(app.pkg) else set.remove(app.pkg)
            c.copy(blockedApps = set)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.applist_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            apps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.applist_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(apps, key = { i, a -> a.pkg.ifBlank { "app_$i" } }) { _, app ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { toggle(app) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = app.pkg in config.blockedApps, onCheckedChange = { toggle(app) })
                        Column(Modifier.weight(1f)) {
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                app.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadLauncherApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri ->
            runCatching {
                val info = pm.getApplicationInfo(ri.activityInfo.packageName, 0)
                val label = pm.getApplicationLabel(info)?.toString() ?: ri.activityInfo.packageName
                AppInfo(ri.activityInfo.packageName, label)
            }.getOrNull()
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}