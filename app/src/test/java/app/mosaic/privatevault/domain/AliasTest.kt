package app.mosaic.privatevault.domain

import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.TargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasTest {

    private val identity = TargetIdentity(handle = "camille_r", displayName = "Camille R")

    @Test
    fun `the default alias is Louis`() {
        assertEquals("Louis", Alias.displayName(MosaicSettings()))
    }

    @Test
    fun `a blank alias falls back to the default rather than showing nothing`() {
        assertEquals("Louis", Alias.displayName(MosaicSettings(alias = "   ")))
    }

    @Test
    fun `a chosen alias replaces the default`() {
        assertEquals("Marc", Alias.displayName(MosaicSettings(alias = "Marc")))
    }

    @Test
    fun `invisible formatting characters are stripped from an alias`() {
        // A zero-width space would otherwise let a "different" alias render
        // identically to another one.
        assertEquals("Louis", Alias.sanitize("Lou​is"))
    }

    @Test
    fun `an alias is capped in length`() {
        assertEquals(Alias.MAX_LENGTH, Alias.sanitize("x".repeat(100)).length)
    }

    @Test
    fun `the real handle is detected in a rendered string`() {
        assertTrue(Alias.leaksIdentity("Message de camille_r", identity))
    }

    @Test
    fun `the real display name is detected in a rendered string`() {
        assertTrue(Alias.leaksIdentity("Camille R vous a ecrit", identity))
    }

    @Test
    fun `the leak check is case insensitive and ignores the at sign`() {
        assertTrue(Alias.leaksIdentity("@CAMILLE_R", identity))
    }

    @Test
    fun `a rendering that only shows the alias does not leak`() {
        assertFalse(Alias.leaksIdentity("Louis - Local", identity))
    }

    @Test
    fun `the neutral notification title does not leak the identity`() {
        assertFalse(Alias.leaksIdentity("Nouvel element", identity))
    }

    @Test
    fun `the avatar seed depends only on the alias`() {
        assertEquals(Alias.avatarSeed("Louis"), Alias.avatarSeed("Louis"))
        assertNotEquals(Alias.avatarSeed("Louis"), Alias.avatarSeed("Marc"))
    }
}
