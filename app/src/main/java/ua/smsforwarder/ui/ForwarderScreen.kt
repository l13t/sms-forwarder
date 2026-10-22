@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.smsforwarder.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import ua.smsforwarder.ForwarderApp
import ua.smsforwarder.core.*
import ua.smsforwarder.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val palette = lightColorScheme(primary = Color(0xFF146C60), secondary = Color(0xFF52655F), background = Color(0xFFF5F8F5), surface = Color(0xFFF5F8F5))
private val eventLabels get() = linkedMapOf("sms.received" to "SMS", "call.completed" to tr(Message.CALLS), "notification.posted" to tr(Message.NOTIFICATIONS))
private fun time(at: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
private fun sourcePermissions(source: String): Array<String> = when(source) {
    "sms" -> arrayOf(Manifest.permission.RECEIVE_SMS)
    "calls" -> arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)
    else -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
}
private fun granted(app: ForwarderApp, source: String) = sourcePermissions(source).all { ContextCompat.checkSelfPermission(app,it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun ForwarderScreen(app: ForwarderApp) {
    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
    val systemLanguage = LocalConfiguration.current.locales[0]?.language ?: "en"
    val language = I18n.resolve(settings.language, systemLanguage)
    I18n.setPreference(settings.language, systemLanguage)
    // Recreate translated option lists and remembered dialog copy when the locale changes.
    key(language) { ForwarderContent(app) }
}

@Composable
private fun ForwarderContent(app: ForwarderApp) {
    MaterialTheme(colorScheme = palette) {
        val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
        val hooks by app.repo.dao.webhooks().collectAsStateWithLifecycle(initialValue = emptyList())
        val rules by app.repo.rules.collectAsStateWithLifecycle(initialValue = emptyList())
        val deliveries by app.repo.dao.deliveries().collectAsStateWithLifecycle(initialValue = emptyList())
        val pendingCount by app.repo.dao.pendingCount().collectAsStateWithLifecycle(initialValue = 0)
        val runtimeError by app.runtimeError.collectAsStateWithLifecycle()
        val monitorRunning by app.monitorRunning.collectAsStateWithLifecycle()
        var page by remember { mutableIntStateOf(0) }
        var hookEditor by remember { mutableStateOf<WebhookEntity?>(null) }
        var ruleEditor by remember { mutableStateOf<Rule?>(null) }
        var pickApps by remember { mutableStateOf(false) }
        var permissionTick by remember { mutableIntStateOf(0) }
        var pendingSource by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        fun action(block: suspend () -> Unit) { scope.launch {
            val error = withContext(Dispatchers.IO) { try { block(); null } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e } }
            if (error != null) snackbar.showSnackbar(error.message?.let(I18n::localizeSaved) ?: tr(Message.OPERATION_FAILED))
        } }
        fun setSource(source: String, value: Boolean) { action {
            if (source == "calls" && value) app.calls.baseline()
            app.settings.update { when(source) { "sms" -> it.copy(sms=value); "calls" -> it.copy(calls=value); else -> it.copy(notifications=value) } }
        } }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingSource?.let { source -> if (result.values.all { it } && granted(app, source)) setSource(source,true) }
            pendingSource = null; permissionTick++
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick++ }
        val notificationAccess = remember(permissionTick) { NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName) }
        fun requestSource(source: String, on: Boolean) {
            if (!on) setSource(source,false)
            else if (source == "notifications") {
                setSource(source,true)
                if (!notificationAccess) app.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (!granted(app,source)) { pendingSource = source; permissions.launch(sourcePermissions(source)) }
            else setSource(source,true)
        }
        Scaffold(
            topBar = { TopAppBar(title = { Column { Text("SMS Forwarder"); Text(tr(Message.PHONE_YOUR_WEBHOOK), style=MaterialTheme.typography.labelMedium) } }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { NavigationBar { listOf(tr(Message.STATUS), tr(Message.WEBHOOKS), tr(Message.RULES), tr(Message.HISTORY)).forEachIndexed { index,label ->
                NavigationBarItem(selected=page==index, onClick={page=index}, icon={Text(listOf("◉","↗","≡","◷")[index])}, label={Text(label)})
            } } }
        ) { padding ->
            when(page) {
                0 -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(settings.enabled) tr(Message.FORWARDING_ENABLED) else tr(Message.FORWARDING_PAUSED), style=MaterialTheme.typography.titleLarge)
                        Text(tr(Message.WEBHOOKS_RULES_QUEUED , hooks.count { it.enabled }, rules.count { it.enabled }, pendingCount))
                        Toggle(tr(Message.RUN_IN_BACKGROUND), settings.enabled) { on -> action {
                            if(on && !settings.enabled && settings.calls) app.calls.baseline()
                            app.settings.update { it.copy(enabled=on) }
                            if(on) {
                                val error = app.startMonitor()
                                if(error != null) { app.settings.update { it.copy(enabled=false) }; throw IllegalStateException(error) }
                                app.requestDelivery()
                            } else app.stopMonitor()
                        } }
                        Text(tr(Message.A_PERSISTENT_NOTIFICATION_IS_SHOWN_WHILE_FORWARDING),style=MaterialTheme.typography.bodySmall)
                    } }
                    Text(tr(Message.LANGUAGE), style=MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        linkedMapOf("system" to tr(Message.SYSTEM_LANGUAGE), "en" to "English", "uk" to "Українська").forEach { (code,label) ->
                            FilterChip(selected=settings.language==code, onClick={action { app.settings.update { it.copy(language=code) } }}, label={Text(label)})
                        }
                    }
                    Text(tr(Message.EVENT_SOURCES), style=MaterialTheme.typography.titleMedium)
                    Toggle(tr(Message.INCOMING_SMS),settings.sms) { requestSource("sms",it) }
                    Toggle(tr(Message.COMPLETED_CALLS),settings.calls) { requestSource("calls",it) }
                    Toggle(tr(Message.APP_NOTIFICATIONS),settings.notifications) { requestSource("notifications",it) }
                    if(settings.sms && !granted(app,"sms")) Text(tr(Message.SMS_PERMISSION_IS_MISSING_ENABLE_THE_SOURCE),color=MaterialTheme.colorScheme.error)
                    if(settings.calls && !granted(app,"calls")) Text(tr(Message.PHONE_CALL_LOG_PERMISSIONS_ARE_MISSING),color=MaterialTheme.colorScheme.error)
                    if(settings.notifications) {
                        OutlinedButton(onClick={pickApps=true}) { Text(tr(Message.CHOOSE_APPS , settings.packages.size)) }
                        if(!notificationAccess) TextButton(onClick={app.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}) { Text(tr(Message.GRANT_NOTIFICATION_ACCESS)) }
                        Text(tr(Message.ANDROID_15_MAY_REDACT_OTPS_AND_OTHER),style=MaterialTheme.typography.bodySmall)
                    }
                    if(Build.VERSION.SDK_INT >= 33 && !granted(app,"status")) OutlinedButton(onClick={permissions.launch(sourcePermissions("status"))}) { Text(tr(Message.ALLOW_SERVICE_NOTIFICATION)) }
                    var deviceName by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
                    OutlinedTextField(deviceName,{deviceName=it},label={Text(tr(Message.DEVICE_NAME))},modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={action { app.settings.update { it.copy(deviceName=deviceName.trim().ifEmpty { Build.MODEL }) } }}) { Text(tr(Message.SAVE_NAME)) }
                    Text(tr(Message.DIAGNOSTICS),style=MaterialTheme.typography.titleMedium)
                    if(settings.enabled && !monitorRunning) {
                        Text(tr(Message.BACKGROUND_SERVICE_IS_NOT_RUNNING),color=MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick={action { app.startMonitor()?.let { throw IllegalStateException(it) } }}) { Text(tr(Message.START_SERVICE)) }
                    }
                    val power = requireNotNull(app.getSystemService(PowerManager::class.java))
                    Text(if(power.isIgnoringBatteryOptimizations(app.packageName)) tr(Message.BATTERY_OPTIMIZATION_IS_DISABLED) else tr(Message.FOR_PROMPT_DELIVERY_ALLOW_UNRESTRICTED_BATTERY_USE))
                    OutlinedButton(onClick={app.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}) { Text(tr(Message.BATTERY_SETTINGS)) }
                    OutlinedButton(onClick={app.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}) { Text(tr(Message.APP_PERMISSIONS)) }
                    Text(tr(Message.IF_NOTIFICATION_ACCESS_IS_BLOCKED_AFTER_INSTALLING),style=MaterialTheme.typography.bodySmall)
                    runtimeError?.let { Text(I18n.localizeSaved(it),color=MaterialTheme.colorScheme.error) }
                }
                1 -> LazyColumn(Modifier.padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Button(onClick={hookEditor=WebhookEntity(UUID.randomUUID().toString(),"","","",true)}) { Text(tr(Message.ADD_WEBHOOK)) } }
                    if(hooks.isEmpty()) item { EmptyState(tr(Message.ADD_YOUR_N8N_WEBHOOK_URL), tr(Message.THEN_CREATE_A_RULE_AND_ENABLE_THE)) }
                    items(hooks,key={it.id}) { hook -> Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(hook.name,style=MaterialTheme.typography.titleMedium)
                        Text(runCatching { Uri.parse(hook.url).host }.getOrNull().orEmpty(),style=MaterialTheme.typography.bodySmall)
                        Text(if(hook.enabled) tr(Message.ENABLED) else tr(Message.PAUSED))
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={hookEditor=hook}) { Text(tr(Message.EDIT)) }
                            TextButton(onClick={action { app.repo.testWebhook(hook); app.requestDelivery() }}) { Text(tr(Message.TEST)) }
                            TextButton(onClick={action { app.repo.dao.saveWebhook(hook.copy(enabled=!hook.enabled)); app.requestDelivery() }}) { Text(if(hook.enabled) tr(Message.PAUSE) else tr(Message.ENABLE)) }
                        }
                    } } }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                2 -> LazyColumn(Modifier.padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Button(onClick={ruleEditor=Rule()}) { Text(tr(Message.ADD_RULE)) } }
                    if(rules.isEmpty()) item { EmptyState(tr(Message.RULES_DETERMINE_WHAT_TO_FORWARD), tr(Message.CHOOSE_EVENTS_CONDITIONS_AND_DESTINATIONS_CREATE_SEPARATE)) }
                    items(rules,key={it.id}) { rule -> Card { Column(Modifier.padding(16.dp)) {
                        Toggle(rule.name,rule.enabled) { on -> action { app.repo.saveRule(rule.copy(enabled=on)) } }
                        Text(rule.types.mapNotNull { eventLabels[it] }.joinToString(" · "))
                        Text("Webhook: ${rule.webhookIds.map { id -> hooks.find { it.id==id }?.name ?: tr(Message.DELETED) }.joinToString()}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={ruleEditor=rule}) { Text(tr(Message.EDIT)) }
                    } } }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                3 -> LazyColumn(Modifier.padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    item { FlowRow { TextButton(onClick={action { app.requestDelivery() }}) { Text(tr(Message.SEND_QUEUE)) }; TextButton(onClick={action { app.repo.dao.clearFinished() }}) { Text(tr(Message.CLEAR_COMPLETED)) } } }
                    if(deliveries.isEmpty()) item { EmptyState(tr(Message.NO_DELIVERIES_YET), tr(Message.TAP_TEST_NEXT_TO_A_WEBHOOK_OR)) }
                    items(deliveries,key={it.id}) { d -> DeliveryCard(d, hooks.find { it.id==d.webhookId }?.name ?: tr(Message.DELETED_WEBHOOK)) { action { app.repo.dao.retry(d.id,System.currentTimeMillis()); app.requestDelivery() } } }
                    item { Text(tr(Message.THE_LATEST_300_DELIVERIES_COMPLETED_RECORDS_ARE),style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        hookEditor?.let { hook -> WebhookEditor(app,hook,onDismiss={hookEditor=null},onSave={name,url,headers ->
            action { app.repo.saveWebhook(hook.id,name,url,headers,hook.enabled); withContext(Dispatchers.Main) { hookEditor=null } }
        },onDelete={action { app.repo.deleteWebhook(hook.id); withContext(Dispatchers.Main) { hookEditor=null } }}) }
        ruleEditor?.let { rule -> RuleEditor(rule,hooks,onDismiss={ruleEditor=null},onSave={edited -> action { app.repo.saveRule(edited); withContext(Dispatchers.Main) { ruleEditor=null } }},onDelete={action { app.repo.dao.deleteRule(rule.id); withContext(Dispatchers.Main) { ruleEditor=null } }}) }
        if(pickApps) AppPicker(app,settings.packages,onDismiss={pickApps=false},onSave={packages -> action { app.settings.update { it.copy(packages=packages) }; withContext(Dispatchers.Main) { pickApps=false } }})
    }
}

@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f)); Switch(value,onChange)
    }
}
@Composable private fun EmptyState(title: String,body: String) { Column(Modifier.padding(vertical=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(title,style=MaterialTheme.typography.titleLarge); Text(body) } }
@Composable private fun DeliveryCard(d: DeliveryEntity, name: String, retry: ()->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val event = remember(d.payload) { runCatching { wireJson.decodeFromString<Envelope>(d.payload) }.getOrNull() }
    Card { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("$name · ${eventLabels[event?.type] ?: tr(Message.TEST)}",style=MaterialTheme.typography.titleMedium)
        Text(tr(Message.ATTEMPTS , time(d.createdAt), d.attempts),style=MaterialTheme.typography.bodySmall)
        Text(when(d.status) { "sent" -> tr(Message.DELIVERED); "failed" -> tr(Message.ERROR); "sending" -> tr(Message.SENDING); else -> tr(Message.QUEUED) })
        d.lastError?.let { Text(I18n.localizeSaved(it),color=MaterialTheme.colorScheme.error) }
        if(d.status=="pending" && d.nextAt>System.currentTimeMillis()) Text(tr(Message.NEXT_ATTEMPT , time(d.nextAt)),style=MaterialTheme.typography.bodySmall)
        FlowRow {
            TextButton(onClick={expanded=!expanded}) { Text(if(expanded) tr(Message.HIDE_DATA) else tr(Message.SHOW_JSON)) }
            if(d.status!="sending") TextButton(onClick=retry) { Text(tr(Message.RETRY)) }
        }
        if(expanded) androidx.compose.foundation.text.selection.SelectionContainer { Text(d.payload,style=MaterialTheme.typography.bodySmall) }
    } }
}

@Composable private fun WebhookEditor(app: ForwarderApp, hook: WebhookEntity, onDismiss: ()->Unit, onSave: (String,String,Map<String,String>)->Unit, onDelete: ()->Unit) {
    var name by remember { mutableStateOf(hook.name) }
    var url by remember { mutableStateOf(hook.url) }
    var headers by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(hook.encryptedHeaders.isEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(hook.id) {
        if(hook.encryptedHeaders.isNotEmpty()) {
            val result = withContext(Dispatchers.IO) { runCatching { app.repo.secrets.decrypt(hook.encryptedHeaders) } }
            result.onSuccess { headers=it }.onFailure { error=tr(Message.COULD_NOT_READ_HEADERS_ENTER_THEM_AGAIN) }
        } else headers="{}"
        loaded=true
    }
    FullEditor(tr(Message.WEBHOOKS),onDismiss,onSave={
        runCatching {
            require(name.isNotBlank()) { tr(Message.ENTER_A_NAME) }
            val parsed = wireJson.decodeFromString<Map<String,String>>(headers)
            ua.smsforwarder.delivery.WebhookClient.validate(url.trim(),parsed)
            onSave(name,url.trim(),parsed)
        }.onFailure { error=it.message ?: tr(Message.INVALID_JSON_HEADERS) }
    },saveEnabled=loaded) {
        OutlinedTextField(name,{name=it},label={Text(tr(Message.NAME))},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{url=it},label={Text(tr(Message.WEBHOOK_URL))},placeholder={Text("https://n8n.example/webhook/…")},modifier=Modifier.fillMaxWidth())
        if(url.trim().startsWith("http://",true)) Text(tr(Message.HTTP_SENDS_MESSAGES_AND_TOKENS_WITHOUT_ENCRYPTION),color=MaterialTheme.colorScheme.error)
        OutlinedTextField(headers,{headers=it},label={Text(tr(Message.HEADERS_JSON))},placeholder={Text("{\"Authorization\": \"Bearer …\"}")},minLines=4,modifier=Modifier.fillMaxWidth())
        Text(tr(Message.POST_JSON_ADD_THE_REQUIRED_HEADER_FOR),style=MaterialTheme.typography.bodySmall)
        Text(tr(Message.URL_AND_HEADER_CHANGES_APPLY_TO_NEW),style=MaterialTheme.typography.bodySmall)
        error?.let { Text(I18n.localizeSaved(it),color=MaterialTheme.colorScheme.error) }
        TextButton(onClick={confirmDelete=true}) { Text(tr(Message.DELETE_WEBHOOK),color=MaterialTheme.colorScheme.error) }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text(tr(Message.DELETE_WEBHOOK_2))},text={Text(tr(Message.ITS_UNFINISHED_DELIVERIES_WILL_BE_CANCELED_UPDATE))},confirmButton={TextButton(onClick=onDelete){Text(tr(Message.DELETE))}},dismissButton={TextButton(onClick={confirmDelete=false}){Text(tr(Message.BACK))}})
}

@Composable private fun FullEditor(title: String, onDismiss: ()->Unit, onSave: ()->Unit, saveEnabled: Boolean=true, content: @Composable ColumnScope.()->Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Scaffold(topBar={TopAppBar(title={Text(title)},navigationIcon={TextButton(onClick=onDismiss){Text(tr(Message.BACK))}},actions={TextButton(onClick=onSave,enabled=saveEnabled){Text(tr(Message.SAVE))}})}) { padding ->
                Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
    }
}

@Composable private fun AppPicker(app: ForwarderApp, selected: Set<String>, onDismiss: ()->Unit, onSave: (Set<String>)->Unit) {
    var packages by remember { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    LaunchedEffect(Unit) { apps=withContext(Dispatchers.IO) {
        val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed=app.packageManager.queryIntentActivities(launcher,0).map { it.activityInfo.packageName to it.loadLabel(app.packageManager).toString() }
        (installed+selected.map { it to it }).distinctBy { it.first }.filter { it.first!=app.packageName }.sortedBy { it.second.lowercase() }
    } }
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr(Message.APPS_TO_FORWARD),style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(search,{search=it},label={Text(tr(Message.SEARCH))},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(manual,{manual=it},label={Text(tr(Message.SYSTEM_APP_PACKAGE_NAME))},modifier=Modifier.fillMaxWidth())
            TextButton(onClick={ val p=manual.trim(); if(p.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) && p!=app.packageName) { packages=packages+p; apps=(apps+(p to p)).distinctBy { it.first }; manual="" } }) { Text(tr(Message.ADD_BY_PACKAGE_NAME)) }
            LazyColumn(Modifier.weight(1f)) { items(apps.filter { it.first.contains(search,true)||it.second.contains(search,true) },key={it.first}) { (id,label) ->
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(id in packages,{on->packages=if(on) packages+id else packages-id})
                    Column { Text(label); Text(id,style=MaterialTheme.typography.bodySmall) }
                }
            } }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { TextButton(onClick=onDismiss){Text(tr(Message.BACK))}; Button(onClick={onSave(packages)}){Text(tr(Message.SELECT , packages.size))} }
        } }
    }
}
