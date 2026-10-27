// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private const val SOURCE_URL = "https://github.com/loxK/pebble-phone-sms-android"
private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html"
private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

enum class Screen(val parent: Screen?, val title: Int) {
    SETUP(null, R.string.app_name),
    SETTINGS(SETUP, R.string.settings),
    ABOUT(SETTINGS, R.string.about),
    CHANGELOG(ABOUT, R.string.changelog),
}

/** A list the setup screen asks to pick from. */
class Choice(val title: String, val options: List<String>, val onPick: (Int) -> Unit)

class SetupActions(
    val link: () -> Unit,
    val open: () -> Unit,
    val grant: () -> Unit,
    val pair: () -> Unit,
)

/** The launcher icon, drawn from its own layers: an adaptive icon is no image Compose can paint. */
@Composable
private fun AppIcon() {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        // The foreground keeps its drawing inside the middle 66 of its 108 units.
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(setup: Setup, actions: SetupActions, choice: Choice?, onChoiceClosed: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.SETUP) }
    val parent = screen.parent
    BackHandler(enabled = parent != null) { screen = parent ?: Screen.SETUP }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    if (screen == Screen.SETUP) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon()
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(screen.title))
                        }
                    } else {
                        Text(stringResource(screen.title))
                    }
                },
                navigationIcon = {
                    if (parent != null) {
                        IconButton(onClick = { screen = parent }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (screen == Screen.SETUP) {
                        IconButton(onClick = { screen = Screen.SETTINGS }) {
                            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (screen) {
            Screen.SETUP -> SetupScreen(setup, actions, padding)
            Screen.SETTINGS -> SettingsScreen(padding, onAbout = { screen = Screen.ABOUT })
            Screen.ABOUT -> AboutScreen(padding, onChangelog = { screen = Screen.CHANGELOG })
            Screen.CHANGELOG -> ChangelogScreen(padding)
        }
    }

    if (choice != null) {
        ChoiceDialog(
            choice.title,
            choice.options,
            selected = -1,
            onPick = {
                onChoiceClosed()
                choice.onPick(it)
            },
            onDismiss = onChoiceClosed,
        )
    }
}

@Composable
private fun SetupScreen(setup: Setup, actions: SetupActions, padding: PaddingValues) {
    Column(
        modifier = Modifier
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(setup.summary, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Step(setup.pebbleDone, stringResource(R.string.pebble_title), setup.pebbleStatus) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.link, enabled = setup.canLink) {
                    Text(stringResource(if (setup.linked) R.string.pebble_change else R.string.pebble_link))
                }
                Button(onClick = actions.open, enabled = setup.canOpen) {
                    Text(stringResource(R.string.pebble_test))
                }
            }
        }

        Step(setup.permissionsDone, stringResource(R.string.permissions_title), setup.permissionsStatus) {
            if (!setup.permissionsDone) {
                Button(onClick = actions.grant) { Text(stringResource(R.string.permissions_grant)) }
            }
        }

        Step(
            setup.callsDone,
            stringResource(R.string.calls_title),
            setup.callsStatus,
            stringResource(R.string.calls_explain),
        ) {
            if (!setup.callsDone) {
                Button(onClick = actions.pair) { Text(stringResource(R.string.calls_pair)) }
            }
        }

        RepliesCard()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Step(
    done: Boolean,
    title: String,
    status: String,
    explanation: String? = null,
    actions: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                painterResource(if (done) R.drawable.ic_status_ok else R.drawable.ic_status_todo),
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (explanation != null) {
                    Text(
                        explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                actions()
            }
        }
    }
}

@Composable
private fun RepliesCard() {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(Replies.load(context).joinToString("\n")) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.replies_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.replies_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                Replies.save(context, text)
                text = Replies.load(context).joinToString("\n")
                Toast.makeText(context, R.string.replies_saved, Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.replies_save))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Chevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
}

/** Options to pick one from; the dialog closes on the pick. [selected] is -1 for none. */
@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selected, role = Role.RadioButton) { onPick(index) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = index == selected, onClick = null)
                        Text(option, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** A setting showing its current option, changed from a [ChoiceDialog]. */
@Composable
private fun ChoiceSetting(
    title: String,
    options: List<String>,
    selected: Int,
    note: String? = null,
    onPick: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(options.getOrElse(selected) { "" })
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = Modifier.clickable { picking = true },
    )
    if (picking) {
        ChoiceDialog(
            title,
            options,
            selected,
            onPick = {
                onPick(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

private val TextSize.label: Int
    get() = when (this) {
        TextSize.SYSTEM -> R.string.text_size_system
        TextSize.SMALL -> R.string.text_size_small
        TextSize.MEDIUM -> R.string.text_size_medium
        TextSize.LARGE -> R.string.text_size_large
        TextSize.EXTRA_LARGE -> R.string.text_size_extra_large
    }

/** A text size of the watch, stored under [key]. [systemLabel] names the default. */
@Composable
private fun SizeSetting(title: Int, key: String, systemLabel: Int) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(Settings.textSize(context, key)) }
    val labels = TextSize.entries.map { stringResource(if (it == TextSize.SYSTEM) systemLabel else it.label) }
    ChoiceSetting(stringResource(title), labels, size.ordinal) {
        size = TextSize.entries[it]
        Settings.setTextSize(context, key, size)
    }
}

/** Material You colors, or those of the app icon on phones whose maker left it out. */
@Composable
private fun WallpaperColorsSetting() {
    val context = LocalContext.current
    var on by ColorChoice.wallpaper(context)
    ListItem(
        headlineContent = { Text(stringResource(R.string.wallpaper_colors_title)) },
        supportingContent = { Text(stringResource(R.string.wallpaper_colors_explain)) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = on, role = Role.Switch) {
            on = it
            Settings.setWallpaperColors(context, it)
        },
    )
}

/** The event shown under the app name in the watch launcher. */
@Composable
private fun GlanceSetting() {
    val context = LocalContext.current
    var event by remember { mutableStateOf(Settings.glance(context)) }
    val labels = GlanceEvent.entries.map {
        stringResource(
            when (it) {
                GlanceEvent.LATEST -> R.string.glance_latest
                GlanceEvent.MISSED_CALL -> R.string.glance_missed_call
                GlanceEvent.SMS -> R.string.glance_sms
                GlanceEvent.NONE -> R.string.glance_none
            },
        )
    }
    ChoiceSetting(stringResource(R.string.glance_title), labels, event.ordinal) {
        event = GlanceEvent.entries[it]
        Settings.setGlance(context, event)
    }
}

/**
 * The SIM [key] goes out from: the phone's choice, the conversation's one for texts when
 * [conversation] is set, or one SIM. [asks] tells that the phone has no default SIM.
 */
@Composable
private fun SimSetting(title: Int, key: String, sims: List<Sims.Sim>, conversation: Boolean, asks: Boolean) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(Settings.sim(context, key)) }
    val values = buildList {
        add(Settings.SIM_PHONE)
        if (conversation) add(Settings.SIM_THREAD)
        sims.forEach { add(it.id.toString()) }
    }
    val labels = buildList {
        add(stringResource(R.string.sim_phone))
        if (conversation) add(stringResource(R.string.sim_thread))
        sims.forEach { add(it.label) }
    }
    // A SIM taken out since it was picked leaves the phone to choose.
    val selected = values.indexOf(value).takeIf { it >= 0 } ?: 0
    val note = if (asks && values[selected] in setOf(Settings.SIM_PHONE, Settings.SIM_THREAD)) {
        stringResource(R.string.sim_asks, sims.first().label)
    } else {
        null
    }
    ChoiceSetting(stringResource(title), labels, selected, note) {
        value = values[it]
        Settings.setSim(context, key, value)
    }
}

@Composable
private fun SettingsScreen(padding: PaddingValues, onAbout: () -> Unit) {
    val context = LocalContext.current
    val sims = remember { Sims.active(context) }
    Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
        SectionHeader(stringResource(R.string.settings_watch))
        SizeSetting(R.string.menu_size_title, Settings.MENU_SIZE, R.string.text_size_system)
        SizeSetting(R.string.message_size_title, Settings.MESSAGE_SIZE, R.string.message_size_default)
        Text(
            stringResource(R.string.text_size_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        GlanceSetting()
        Text(
            stringResource(R.string.glance_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (sims.size >= 2) {
            SectionHeader(stringResource(R.string.settings_sim))
            SimSetting(R.string.sim_sms, Settings.SMS_SIM, sims, conversation = true, asks = Sims.smsAsks(context))
            SimSetting(R.string.sim_calls, Settings.CALL_SIM, sims, conversation = false, asks = Sims.callAsks(context))
            Text(
                stringResource(R.string.sim_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        SectionHeader(stringResource(R.string.settings_app))
        WallpaperColorsSetting()
        ListItem(
            headlineContent = { Text(stringResource(R.string.about)) },
            supportingContent = { Text(stringResource(R.string.about_summary)) },
            trailingContent = { Chevron() },
            modifier = Modifier.clickable(onClick = onAbout),
        )
    }
}

@Composable
private fun AboutScreen(padding: PaddingValues, onChangelog: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_version)) },
            supportingContent = { Text(version) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.changelog)) },
            trailingContent = { Chevron() },
            modifier = Modifier.clickable(onClick = onChangelog),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_license)) },
            supportingContent = { Text(stringResource(R.string.about_license_value)) },
            modifier = Modifier.clickable { uri.openUri(LICENSE_URL) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.about_source)) },
            supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
            modifier = Modifier.clickable { uri.openUri(SOURCE_URL) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.about_credits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { uri.openUri(APACHE_URL) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.about_signature),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Renders the few Markdown forms CHANGELOG.md uses: headings, bullets and paragraphs. */
@Composable
private fun ChangelogScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val lines = remember {
        context.assets.open("CHANGELOG.md").bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("# ") }
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        items(lines) { line ->
            when {
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                line.startsWith("### ") -> Text(
                    line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                line.startsWith("- ") -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("•", modifier = Modifier.padding(end = 8.dp))
                    Text(line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}
