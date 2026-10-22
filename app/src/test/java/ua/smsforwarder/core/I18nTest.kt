package ua.smsforwarder.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class I18nTest {
    @After fun reset() { I18n.setPreference("system") }
    @Test fun explicitPreferenceOverridesSystemLanguage() {
        assertEquals("en", I18n.resolve("en", "uk"))
        assertEquals("uk", I18n.resolve("uk", "en"))
    }
    @Test fun unsupportedSystemLanguagesFallBackToEnglish() {
        assertEquals("uk", I18n.resolve("system", "uk"))
        assertEquals("en", I18n.resolve("system", "de"))
        assertEquals("en", I18n.resolve("unknown", "fr"))
    }
    @Test fun switchingLanguageChangesLabelsAndValidation() {
        I18n.setPreference("en")
        assertEquals("Status", tr(Message.STATUS))
        assertEquals("Enter a rule name", RuleEngine.validate(Rule()))
        I18n.setPreference("uk")
        assertEquals("Стан", tr(Message.STATUS))
        assertEquals("Вкажіть назву правила", RuleEngine.validate(Rule()))
    }
    @Test fun allTranslationsHaveMatchingFormatArguments() {
        // Match literal positional placeholders such as %1$s.
        val pattern=Regex("%[0-9]+" + Regex.escape("$" + "s"))
        for(message in Message.values()) {
            assertTrue(message.english.isNotBlank())
            assertTrue(message.ukrainian.isNotBlank())
            assertEquals(message.name,pattern.findAll(message.english).map { it.value }.toList(),pattern.findAll(message.ukrainian).map { it.value }.toList())
            val count=pattern.findAll(message.english).count()
            val args=Array<Any?>(count) { "value" }
            I18n.setPreference("en"); assertFalse(I18n.text(message,*args).contains(Regex("%[0-9]+")))
            I18n.setPreference("uk"); assertFalse(I18n.text(message,*args).contains(Regex("%[0-9]+")))
        }
    }
    @Test fun historicalDiagnosticsFollowCurrentLanguage() {
        val message=Message.NETWORK_OR_TLS_ERROR
        I18n.setPreference("en")
        assertEquals(message.english,I18n.localizeSaved(message.ukrainian))
        I18n.setPreference("uk")
        assertEquals(message.ukrainian,I18n.localizeSaved(message.english))
        assertEquals("HTTP 503",I18n.localizeSaved("HTTP 503"))
    }
    @Test fun formattingPreservesValuesInBothLanguages() {
        for(language in listOf("en","uk")) {
            I18n.setPreference(language)
            val label=tr(Message.WEBHOOKS_RULES_QUEUED,2,3,4)
            assertTrue(label.contains("2")); assertTrue(label.contains("3")); assertTrue(label.contains("4"))
        }
    }
}
