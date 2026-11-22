package io.github.lingyicute.starrypass

import io.github.lingyicute.starrypass.autofill.AssistStructureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlAutofillPolicyTest {
    private fun fillable(tag: String, vararg attributes: Pair<String?, String?>): Boolean =
        AssistStructureParser.isFillableHtmlInput(tag, attributes.asSequence())

    @Test
    fun `disabled false is still a present boolean attribute`() {
        assertFalse(fillable("input", "type" to "password", "disabled" to "false"))
        assertFalse(fillable("input", "DISABLED" to " FALSE "))
    }

    @Test
    fun `readonly false blocks both inputs and textareas`() {
        assertFalse(fillable("input", "readonly" to "false"))
        assertFalse(fillable("textarea", "READONLY" to "False"))
    }

    @Test
    fun `blocking boolean attributes do not depend on their values`() {
        for (name in listOf("disabled", "readonly", " DISABLED ", " READONLY ")) {
            for (value in listOf(null, "", "true", "0", name)) {
                assertFalse("$name=$value", fillable("input", name to value))
            }
        }
    }

    @Test
    fun `text controls remain eligible when blocking attributes are absent`() {
        assertTrue(fillable("input"))
        assertTrue(fillable(" INPUT ", "TYPE" to " PASSWORD "))
        assertTrue(fillable("textarea"))
        for (type in listOf(null, "", "password", "text", "tel", "number", "email", "search", "url")) {
            assertTrue("type=$type", fillable("input", "type" to type))
        }
    }

    @Test
    fun `non-text controls never receive passwords`() {
        for (type in listOf("hidden", "submit", "button", "checkbox", "radio", "file", "range", "reset")) {
            assertFalse("type=$type", fillable("input", "type" to type))
        }
        for (tag in listOf("select", "button", "div", "")) {
            assertFalse("tag=$tag", fillable(tag))
        }
    }

    @Test
    fun `unrelated attributes are not mistaken for native HTML booleans`() {
        assertTrue(fillable("input", "aria-disabled" to "false", "data-readonly" to "false", null to null))
    }

    @Test
    fun `the first type attribute retains precedence`() {
        assertFalse(fillable("input", "type" to "hidden", "type" to "password"))
        assertTrue(fillable("input", "type" to "password", "type" to "hidden"))
        assertTrue(fillable("input", "type" to null, "type" to "hidden"))
    }

    @Test
    fun `an allowed type does not skip a later blocking attribute`() {
        assertFalse(fillable("input", "type" to "password", "disabled" to ""))
        assertFalse(fillable("textarea", "type" to "text", "readonly" to null))
    }

    @Test
    fun `credit card autocomplete tokens exclude masked CVV fields from password detection`() {
        val card = AssistStructureParser::hasCreditCardHtmlAutocomplete
        assertTrue(card(sequenceOf("type" to "password", "autocomplete" to "cc-csc")))
        assertTrue(card(sequenceOf("AUTOCOMPLETE" to " section-checkout CC-NUMBER ")))
        assertTrue(card(sequenceOf("autocomplete" to "creditcardNumber")))
        assertFalse(card(sequenceOf("autocomplete" to "current-password")))
        assertFalse(card(sequenceOf("autocomplete" to "section-cc-csc current-password")))
        assertFalse(card(sequenceOf("aria-autocomplete" to "cc-csc")))
    }

    @Test
    fun `html password classification respects first type and exact autocomplete tokens`() {
        val classify = AssistStructureParser::classifyHtmlPasswordInput
        assertEquals(false, classify(sequenceOf("type" to "password", "autocomplete" to "one-time-code")))
        assertEquals(false, classify(sequenceOf("type" to "text", "autocomplete" to "section-password username")))
        assertNull(classify(sequenceOf("type" to "text", "autocomplete" to "section-password")))
        assertEquals(true, classify(sequenceOf("type" to "text", "autocomplete" to "section-login current-password")))
        assertEquals(false, classify(sequenceOf("type" to "email", "type" to "password")))
        assertNull(classify(sequenceOf("type" to "text", "type" to "password")))
        assertEquals(true, classify(sequenceOf("type" to "password", "type" to "text")))
    }
}
