package ua.smsforwarder

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ua.smsforwarder.core.*
import ua.smsforwarder.data.*

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: ForwarderDatabase
    private lateinit var repo: Repository
    private lateinit var settings: SettingsStore
    private var originalLanguage = "system"
    private val event=Event(type="sms.received",data=EventData(sender="Bank",text="Test",simId="2"),sourceKey="fixture-sms")
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,ForwarderDatabase::class.java).build()
        settings=SettingsStore(context)
        originalLanguage=settings.current().language
        settings.update { it.copy(enabled=true,sms=true,calls=false,notifications=false) }
        repo=Repository(db,settings,Secrets())
        repo.saveWebhook("a","A","https://example.com/a",mapOf("X-Test" to "secret"),true)
        repo.saveWebhook("b","B","https://example.com/b",emptyMap(),true)
    }
    @After fun cleanup() = runBlocking { settings.update { it.copy(enabled=false,sms=false,calls=false,notifications=false,language=originalLanguage) }; db.close() }
    @Test fun overlappingRulesAndRepeatedSourceProduceOneDeliveryPerDestination() = runBlocking {
        repo.saveRule(Rule(id="one",name="One",webhookIds=setOf("a","b")))
        repo.saveRule(Rule(id="two",name="Two",webhookIds=setOf("a")))
        repo.accept(event)
        repo.accept(event.copy(id="new-uuid-same-source"))
        val deliveries=repo.dao.deliveries().first()
        assertEquals(2,deliveries.size)
        assertEquals(2,deliveries.map { it.id }.toSet().size)
        assertEquals(setOf(event.id),deliveries.map { it.eventId }.toSet())
        val envelope=wireJson.decodeFromString<Envelope>(deliveries.first().payload)
        assertEquals(event.id,envelope.eventId)
        assertEquals(deliveries.first().id,envelope.deliveryId)
        assertEquals(event.data,envelope.data)
    }
    @Test fun pausedCollectionAndDisabledSourcesDoNotCreateDeliveries() = runBlocking {
        repo.saveRule(Rule(name="All",webhookIds=setOf("a")))
        settings.update { it.copy(enabled=false) }; repo.accept(event)
        settings.update { it.copy(enabled=true,sms=false) }; repo.accept(event.copy(sourceKey="next"))
        assertTrue(repo.dao.deliveries().first().isEmpty())
    }
    @Test fun endpointCredentialsAreEncryptedAndQueuedRequestIsSnapshot() = runBlocking {
        repo.saveRule(Rule(name="All",webhookIds=setOf("a")))
        repo.accept(event)
        val delivery=repo.dao.deliveries().first().single()
        assertFalse(delivery.encryptedHeaders.contains("secret"))
        assertEquals(mapOf("X-Test" to "secret"),wireJson.decodeFromString<Map<String,String>>(repo.secrets.decrypt(delivery.encryptedHeaders)))
        repo.saveWebhook("a","A","https://example.com/changed",emptyMap(),true)
        assertEquals("https://example.com/a",repo.dao.delivery(delivery.id)!!.url)
    }
    @Test fun leasePreventsDoubleClaimAndAllowsRecovery() = runBlocking {
        repo.saveRule(Rule(name="All",webhookIds=setOf("a")))
        repo.accept(event)
        val next=repo.dao.next(System.currentTimeMillis())!!
        assertEquals(1,repo.dao.claim(next.id,100,1000))
        assertEquals(0,repo.dao.claim(next.id,101,1001))
        assertNull(repo.dao.next(999))
        assertEquals(next.id,repo.dao.next(1001)!!.id)
    }
    @Test fun languagePreferencePersistsAcrossStoreInstances() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(language in listOf("en","uk","system")) {
            settings.update { it.copy(language=language) }
            assertEquals(language,SettingsStore(context).current().language)
        }
    }
    @Test fun pendingQueueSurvivesActualDatabaseReopen() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="queue-test-${System.nanoTime()}.db"
        var disk=Room.databaseBuilder(context,ForwarderDatabase::class.java,name).build()
        try {
            var repository=Repository(disk,settings,Secrets())
            repository.saveWebhook("disk","Disk","https://example.com/hook",emptyMap(),true)
            repository.saveRule(Rule(name="Disk",webhookIds=setOf("disk")))
            repository.accept(event)
            val before=repository.dao.deliveries().first().single()
            disk.close()
            disk=Room.databaseBuilder(context,ForwarderDatabase::class.java,name).build()
            repository=Repository(disk,settings,Secrets())
            assertEquals(before,repository.dao.deliveries().first().single())
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
