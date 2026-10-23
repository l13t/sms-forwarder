@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package ua.smsforwarder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.smsforwarder.core.*
import ua.smsforwarder.R
import ua.smsforwarder.data.WebhookEntity
import java.time.LocalDateTime
import java.time.ZoneId

private val fields get()=mapOf(Field.SENDER to tr(Message.NUMBER_SENDER),Field.SIM to tr(Message.SIM_ACCOUNT_ID),Field.PACKAGE to tr(Message.PACKAGE_NAME),Field.TEXT to tr(Message.TITLE_AND_TEXT),Field.CALL_TYPE to tr(Message.CALL_TYPE))
private val operators get()=mapOf(Operator.EQUALS to tr(Message.EQUALS),Operator.CONTAINS to tr(Message.CONTAINS),Operator.REGEX to tr(Message.REGULAR_EXPRESSION))
private val eventTypes get()=mapOf("sms.received" to "SMS","call.completed" to tr(Message.CALLS),"notification.posted" to tr(Message.NOTIFICATIONS))

@Composable
fun RuleEditor(initial: Rule,hooks: List<WebhookEntity>,onDismiss: ()->Unit,onSave: (Rule)->Unit,onDelete: ()->Unit) {
    var rule by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Scaffold(topBar={TopAppBar(title={Text(tr(Message.RULE))},navigationIcon={IconButton(onClick=onDismiss){AppIcon(R.drawable.ic_back,description=tr(Message.BACK))}},actions={TextButton(onClick={
            error=RuleEngine.validate(rule) ?: if(rule.webhookIds.any { id -> hooks.none { it.id==id } }) tr(Message.CHOOSE_EXISTING_WEBHOOKS) else null
            if(error==null) onSave(rule)
        }){Text(tr(Message.SAVE))}})}) { padding ->
            Column(Modifier.padding(padding).padding(20.dp).imePadding().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(rule.name,{rule=rule.copy(name=it)},label={Text(tr(Message.RULE_NAME))},modifier=Modifier.fillMaxWidth())
                Text(tr(Message.SOURCES),style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { eventTypes.forEach { (id,label) -> FilterChip(selected=id in rule.types,onClick={rule=rule.copy(types=if(id in rule.types) rule.types-id else rule.types+id)},label={Text(label)}) } }
                Text(tr(Message.DESTINATION_WEBHOOKS),style=MaterialTheme.typography.titleMedium)
                if(hooks.isEmpty()) Text(tr(Message.ADD_AT_LEAST_ONE_WEBHOOK_FIRST))
                hooks.forEach { hook -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(hook.id in rule.webhookIds,{on->rule=rule.copy(webhookIds=if(on) rule.webhookIds+hook.id else rule.webhookIds-hook.id)})
                    Text(hook.name+if(hook.enabled) "" else tr(Message.PAUSED_2))
                } }
                rule.webhookIds.filter { id -> hooks.none { it.id==id } }.forEach { missing ->
                    TextButton(onClick={rule=rule.copy(webhookIds=rule.webhookIds-missing)}) { Text(tr(Message.REMOVE_DELETED_WEBHOOK , missing.take(8))) }
                }
                SectionTitle(tr(Message.CONDITIONS))
                Text(tr(Message.AN_EMPTY_AND_GROUP_MATCHES_ALL_EVENTS),style=MaterialTheme.typography.bodySmall)
                GroupEditor(rule.conditions,0,{rule=rule.copy(conditions=it)})
                Text(tr(Message.CALL_TYPES_MISSED_INCOMING_OUTGOING_REJECTED_SIM),style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(rule.schedule!=null,{on->rule=rule.copy(schedule=if(on) Schedule() else null)})
                    Text(tr(Message.FILTER_BY_SCHEDULE))
                }
                rule.schedule?.let { s ->
                    FlowRow { listOf(tr(Message.MON),tr(Message.TUE),tr(Message.WED),tr(Message.THU),tr(Message.FRI),tr(Message.SAT),tr(Message.SUN)).forEachIndexed { i,label ->
                        FilterChip(selected=i+1 in s.days,onClick={rule=rule.copy(schedule=s.copy(days=if(i+1 in s.days) s.days-(i+1) else s.days+(i+1)))},label={Text(label)},modifier=Modifier.padding(end=4.dp))
                    } }
                    TimeEditor(tr(Message.FROM),s.startMinute){rule=rule.copy(schedule=s.copy(startMinute=it))}
                    TimeEditor(tr(Message.TO),s.endMinute){rule=rule.copy(schedule=s.copy(endMinute=it))}
                    Text(tr(Message.USES_THE_PHONE_TIME_ZONE_EQUAL_START),style=MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                RuleTester(rule)
                TextButton(onClick={confirmDelete=true}) { Text(tr(Message.DELETE_RULE),color=MaterialTheme.colorScheme.error) }
            }
        } }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text(tr(Message.DELETE_RULE_2))},text={Text(tr(Message.ALREADY_QUEUED_DELIVERIES_WILL_REMAIN_IN_THE))},confirmButton={TextButton(onClick=onDelete){Text(tr(Message.DELETE))}},dismissButton={TextButton(onClick={confirmDelete=false}){Text(tr(Message.BACK))}})
}

@Composable private fun <T> Choice(label: String,value: T,options: Map<T,String>,onChange:(T)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={open=true}) { Text("$label${options[value]}") }; DropdownMenu(open,{open=false}) { options.forEach { (id,text) -> DropdownMenuItem(text={Text(text)},onClick={onChange(id);open=false}) } } }
}
@Composable private fun GroupEditor(group: ConditionGroup,depth: Int,onChange: (ConditionGroup)->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Choice("",group.any,mapOf(false to tr(Message.AND_ALL_CONDITIONS),true to tr(Message.OR_ANY_CONDITION))){onChange(group.copy(any=it))}
        group.conditions.forEachIndexed { index,c ->
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Choice("",c.field,fields){f->onChange(group.copy(conditions=group.conditions.toMutableList().apply { this[index]=c.copy(field=f) }))}
                Choice("",c.operator,operators){o->onChange(group.copy(conditions=group.conditions.toMutableList().apply { this[index]=c.copy(operator=o) }))}
                OutlinedTextField(c.value,{v->onChange(group.copy(conditions=group.conditions.toMutableList().apply { this[index]=c.copy(value=v) }))},label={Text(tr(Message.VALUE))},modifier=Modifier.fillMaxWidth())
                TextButton(onClick={onChange(group.copy(conditions=group.conditions.filterIndexed { i,_ -> i!=index }))}){Text(tr(Message.REMOVE_CONDITION))}
            }
        }
        group.groups.forEachIndexed { index,g ->
            GroupEditor(g,depth+1){updated->onChange(group.copy(groups=group.groups.toMutableList().apply { this[index]=updated }))}
            TextButton(onClick={onChange(group.copy(groups=group.groups.filterIndexed { i,_ -> i!=index }))}){Text(tr(Message.REMOVE_GROUP))}
        }
        FlowRow {
            TextButton(onClick={onChange(group.copy(conditions=group.conditions+Condition()))}){Text(tr(Message.CONDITION))}
            if(depth<8) TextButton(onClick={onChange(group.copy(groups=group.groups+ConditionGroup()))}){Text(tr(Message.GROUP))}
        }
    } }
}
@Composable private fun TimeEditor(label: String,minute: Int,onChange:(Int)->Unit) {
    var value by remember { mutableStateOf("%02d:%02d".format(minute/60,minute%60)) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value,{ v->
        value=v
        val parts=v.split(':'); val h=parts.getOrNull(0)?.toIntOrNull(); val m=parts.getOrNull(1)?.toIntOrNull()
        invalid=parts.size!=2 || h==null || m==null || h !in 0..23 || m !in 0..59
        onChange(if(invalid) -1 else h!!*60+m!!)
    },label={Text("$label (HH:mm)")},isError=invalid,supportingText={if(invalid) Text(tr(Message.TIME_NOT_SAVED_ENTER_HH_MM))})
}
@Composable private fun RuleTester(rule: Rule) {
    var type by remember { mutableStateOf("sms.received") }
    var sender by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var sim by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var callType by remember { mutableStateOf("missed") }
    var at by remember { mutableStateOf(LocalDateTime.now().withSecond(0).withNano(0).toString()) }
    var result by remember { mutableStateOf<String?>(null) }
    Text(tr(Message.TEST_RULE_WITHOUT_SENDING),style=MaterialTheme.typography.titleMedium)
    Choice(tr(Message.EVENT),type,eventTypes){type=it}
    OutlinedTextField(sender,{sender=it},label={Text(tr(Message.SENDER_NUMBER))})
    OutlinedTextField(text,{text=it},label={Text(tr(Message.TEXT))})
    OutlinedTextField(sim,{sim=it},label={Text(tr(Message.SIM_ACCOUNT_ID))})
    if(type=="notification.posted") OutlinedTextField(pkg,{pkg=it},label={Text(tr(Message.PACKAGE_NAME))})
    if(type=="call.completed") Choice("",callType,mapOf("missed" to tr(Message.MISSED),"incoming" to tr(Message.ANSWERED_INCOMING),"outgoing" to tr(Message.OUTGOING),"rejected" to tr(Message.REJECTED))){callType=it}
    OutlinedTextField(at,{at=it},label={Text(tr(Message.LOCAL_TIME_YYYY_MM_DDTHH_MM))})
    OutlinedButton(onClick={ result=runCatching {
        val millis=LocalDateTime.parse(at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event=Event(type=type,occurredAt=millis,data=EventData(sender=sender,number=sender,text=text,simId=sim,packageName=pkg,callType=callType),sourceKey="preview")
        RuleEngine.validate(rule)?.let { return@runCatching it }
        if(RuleEngine.matches(rule,event)) tr(Message.MATCHES_THE_RULE) else tr(Message.DOES_NOT_MATCH_THE_RULE)
    }.getOrDefault(tr(Message.INVALID_TIME)) }){Text(tr(Message.CHECK))}
    result?.let { Text(it) }
}
