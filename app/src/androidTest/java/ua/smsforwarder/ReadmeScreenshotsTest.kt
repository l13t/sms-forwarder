package ua.smsforwarder

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ua.smsforwarder.core.*
import ua.smsforwarder.data.*
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Explicitly opt-in: this test replaces the disposable emulator's local app data. */
class ReadmeScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureDocumentation() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("captureScreenshots")=="true")
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Screenshots require a disposable Android emulator" }
        val app=compose.activity.application as ForwarderApp
        WorkManager.getInstance(app).cancelAllWork().result.get()
        val theme=args.getString("screenshotTheme") ?: "light"
        check(theme in setOf("light","dark"))
        runBlocking {
            app.settings.update { it.copy(enabled=false,sms=false,calls=false,notifications=false,deviceName="Pixel · Work phone",language="en") }
            app.stopMonitor()
            app.db.clearAllTables()
            app.repo.saveWebhook("demo-main","Team automation","https://automation.example.invalid/webhook/phone",emptyMap(),true)
            app.repo.saveWebhook("demo-backup","Backup inbox","https://backup.example.invalid/events",emptyMap(),true)
            listOf(
                ua.smsforwarder.core.Rule(id="demo-sms",name="Work messages",types=setOf("sms.received"),webhookIds=setOf("demo-main"),conditions=ConditionGroup(conditions=listOf(Condition(Field.SENDER,Operator.CONTAINS,"Acme")))),
                ua.smsforwarder.core.Rule(id="demo-calls",name="Missed calls",types=setOf("call.completed"),webhookIds=setOf("demo-main","demo-backup"),conditions=ConditionGroup(conditions=listOf(Condition(Field.CALL_TYPE,Operator.EQUALS,"missed")))),
                ua.smsforwarder.core.Rule(id="demo-apps",name="Delivery updates",types=setOf("notification.posted"),webhookIds=setOf("demo-main"))
            ).forEach { app.repo.saveRule(it) }
            val at=Instant.parse("2026-10-22T09:41:00Z").toEpochMilli()
            val examples=listOf(
                "sent" to EventData(sender="Acme",text="Your delivery is on its way."),
                "pending" to EventData(number="+1 202-555-0148",callType="missed",durationSeconds=0),
                "failed" to EventData(appName="Parcel",title="Delivery update",text="Your package is ready for collection.")
            )
            examples.forEachIndexed { index,(status,data) ->
                val id="demo-$index"
                val type=listOf("sms.received","call.completed","notification.posted")[index]
                val payload=wireJson.encodeToString(Envelope(eventId=id,deliveryId=id,deviceId="demo-phone",deviceName="Work phone",occurredAt=Instant.ofEpochMilli(at-index*60_000).toString(),type=type,data=data))
                app.repo.dao.insertDelivery(DeliveryEntity(id,id,"demo-main",payload,"https://automation.example.invalid/webhook/phone",app.repo.secrets.encrypt("{}"),at-index*60_000,status=status,attempts=if(status=="pending")0 else 1,lastError=if(status=="failed")"HTTP 503" else null,completedAt=if(status=="pending")null else at))
            }
        }
        for(language in listOf("en","uk")) {
            runBlocking { app.settings.update {it.copy(language=language)} }
            compose.waitForIdle()
            navigate(0)
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag("stat_rules").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].any {it.text=="3"} }.getOrDefault(false)
            }
            save("$language-$theme/status.png")
            for((index,name) in listOf(1 to "webhooks",2 to "rules",3 to "history",4 to "settings",5 to "about")) {
                navigate(index)
                if(index==1) compose.onNodeWithTag("webhook_demo-main").assertExists()
                save("$language-$theme/$name.png")
            }
            navigate(2)
            compose.onNodeWithTag("rules_list").performScrollToNode(hasTestTag("edit_rule_demo-sms"))
            compose.onNodeWithTag("edit_rule_demo-sms").performClick()
            compose.waitForIdle()
            save("$language-$theme/rule-editor.png")
            compose.onNodeWithContentDescription(tr(Message.BACK)).performClick()
            compose.waitForIdle()
        }
    }
    private fun navigate(index:Int) {
        compose.onNodeWithTag("nav_menu").performClick()
        compose.onNodeWithTag("nav_$index").performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun save(path:String) {
        require(path.matches(Regex("(en|uk)-(light|dark)/[a-z-]+\\.png")))
        compose.waitForIdle()
        assertAppInForeground(path)
        val image=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val png=try {
            // Reject a system dialog that appeared between the focus check and capture.
            assertAppInForeground(path)
            ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG,100,it)) { "Could not encode screenshot: $path" }
                it.toByteArray()
            }
        } finally { image.recycle() }
        // Stream to shell-owned storage; avoid app storage access and shell operators.
        // These files survive instrumentation APK cleanup between theme runs.
        val directory="/data/local/tmp/sms-forwarder-screenshots/${path.substringBeforeLast('/')}"
        val file="/data/local/tmp/sms-forwarder-screenshots/$path"
        val created=shell("mkdir -p $directory")
        check(created.second.isBlank()) { "Could not create screenshot directory: ${created.second}" }
        val written=shell("dd of=$file",png)
        val size=shell("stat -c %s $file")
        check(size.first.trim().toLongOrNull()==png.size.toLong() && size.second.isBlank()) {
            "Could not export screenshot $path (${png.size} bytes): dd=${written.second}; stat=$size"
        }
    }

    private fun assertAppInForeground(path:String) {
        val activity=compose.activity
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val activePackage=automation.rootInActiveWindow?.packageName?.toString()
        compose.runOnUiThread {
            check(activity.hasWindowFocus() && activePackage==activity.packageName) {
                "Refusing screenshot $path: app focused=${activity.hasWindowFocus()}, active package=$activePackage. A system dialog or another app may cover the screen."
            }
        }
    }

    /** Only used for bounded-output commands; none interpret shell syntax. */
    private fun shell(command:String,input:ByteArray=byteArrayOf()):Pair<String,String> {
        check(Build.VERSION.SDK_INT>=34) { "Screenshot export requires an API 34+ emulator" }
        val streams=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRwe(command)
        ParcelFileDescriptor.AutoCloseInputStream(streams[0]).use { stdout ->
            ParcelFileDescriptor.AutoCloseInputStream(streams[2]).use { stderr ->
                val write=runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(streams[1]).use { it.write(input) }
                }
                val result=stdout.bufferedReader().readText() to stderr.bufferedReader().readText()
                check(write.isSuccess) { "Screenshot command failed: $command; stderr=${result.second}; ${write.exceptionOrNull()}" }
                return result
            }
        }
    }
}
