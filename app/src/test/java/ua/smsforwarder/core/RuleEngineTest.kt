package ua.smsforwarder.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RuleEngineTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val sms = Event(type="sms.received", data=EventData(sender="Bank",text="Code: 482913",simId="2"),sourceKey="fixture")
    private fun rule(group: ConditionGroup=ConditionGroup()) = Rule(name="Bank",webhookIds=setOf("hook"),conditions=group)
    @Test fun emptyAndMatchesAllButEmptyOrMatchesNone() {
        assertTrue(RuleEngine.matches(rule(),sms))
        assertFalse(RuleEngine.matches(rule(ConditionGroup(any=true)),sms))
    }
    @Test fun disabledOrWrongEventTypeDoesNotMatch() {
        assertFalse(RuleEngine.matches(rule().copy(enabled=false),sms))
        assertFalse(RuleEngine.matches(rule().copy(types=setOf("call.completed")),sms))
    }
    @Test fun nestedAndOrFiltersCombineSenderSimAndRegex() {
        val group=ConditionGroup(conditions=listOf(Condition(Field.SIM,Operator.EQUALS,"2")),groups=listOf(
            ConditionGroup(any=true,conditions=listOf(Condition(Field.SENDER,Operator.EQUALS,"bank"),Condition(Field.TEXT,Operator.REGEX,"never")))
        ))
        assertTrue(RuleEngine.matches(rule(group),sms))
        assertFalse(RuleEngine.matches(rule(group),sms.copy(data=sms.data.copy(simId="1"))))
        assertTrue(RuleEngine.matches(rule(ConditionGroup(conditions=listOf(Condition(Field.TEXT,Operator.REGEX,"Code: [0-9]{6}")))),sms))
    }
    @Test fun textSearchIncludesNotificationTitle() {
        val notification=sms.copy(type="notification.posted",data=EventData(title="DELIVERY",text="Parcel ready",packageName="org.example"))
        val r=rule(ConditionGroup(conditions=listOf(Condition(Field.TEXT,Operator.CONTAINS,"delivery")))).copy(types=setOf("notification.posted"))
        assertTrue(RuleEngine.matches(r,notification))
    }
    @Test fun missingFieldsDoNotMatch() {
        assertFalse(RuleEngine.matches(rule(ConditionGroup(conditions=listOf(Condition(Field.PACKAGE,Operator.CONTAINS,"bank")))),sms))
    }
    @Test fun callNumberAndCallTypeAreFilterable() {
        val event=sms.copy(type="call.completed",data=EventData(number="+49123",callType="missed"))
        val r=rule(ConditionGroup(conditions=listOf(Condition(Field.SENDER,Operator.EQUALS,"+49123"),Condition(Field.CALL_TYPE,Operator.EQUALS,"missed")))).copy(types=setOf("call.completed"))
        assertTrue(RuleEngine.matches(r,event))
    }
    @Test fun invalidRegexAndMissingDestinationsAreRejected() {
        assertNotNull(RuleEngine.validate(rule(ConditionGroup(conditions=listOf(Condition(operator=Operator.REGEX,value="["))))))
        assertNotNull(RuleEngine.validate(rule().copy(webhookIds=emptySet())))
        assertNotNull(RuleEngine.validate(rule().copy(schedule=Schedule(startMinute=-1))))
    }
    @Test fun excessiveNestingIsRejected() {
        var g=ConditionGroup()
        repeat(10) { g=ConditionGroup(groups=listOf(g)) }
        assertNotNull(RuleEngine.validate(rule(g)))
    }
    @Test fun daytimeScheduleUsesInclusiveStartExclusiveEnd() {
        val s=Schedule(days=setOf(1),startMinute=9*60,endMinute=17*60)
        fun at(value:String)=RuleEngine.inSchedule(s,Instant.parse(value).toEpochMilli(),zone)
        assertFalse(at("2026-10-19T06:59:00Z"))
        assertTrue(at("2026-10-19T07:00:00Z"))
        assertFalse(at("2026-10-19T15:00:00Z"))
        assertFalse(at("2026-10-20T07:00:00Z"))
    }
    @Test fun overnightScheduleBelongsToStartingDay() {
        val s=Schedule(days=setOf(1),startMinute=22*60,endMinute=6*60)
        assertTrue(RuleEngine.inSchedule(s,Instant.parse("2026-10-19T21:00:00Z").toEpochMilli(),zone))
        assertTrue(RuleEngine.inSchedule(s,Instant.parse("2026-10-20T03:00:00Z").toEpochMilli(),zone))
        assertFalse(RuleEngine.inSchedule(s,Instant.parse("2026-10-19T03:00:00Z").toEpochMilli(),zone))
        assertFalse(RuleEngine.inSchedule(s,Instant.parse("2026-10-20T04:00:00Z").toEpochMilli(),zone))
    }
    @Test fun fullDayAndDstTransitionUseLocalTime() {
        val s=Schedule(days=setOf(7),startMinute=0,endMinute=0)
        assertTrue(RuleEngine.inSchedule(s,Instant.parse("2026-10-25T01:30:00Z").toEpochMilli(),zone))
        assertFalse(RuleEngine.inSchedule(s,Instant.parse("2026-10-26T12:00:00Z").toEpochMilli(),zone))
    }
}
