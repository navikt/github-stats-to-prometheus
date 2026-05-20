package no.nav.github_stats

import kotlin.test.*

class RoleTest {

    private fun perms(
        admin: Boolean = false,
        maintain: Boolean = false,
        push: Boolean = false,
        triage: Boolean = false,
        pull: Boolean = true
    ) =
        Permissions(admin = admin, maintain = maintain, push = push, triage = triage, pull = pull)

    @Test
    fun `meetsMinimum is true for same role`() {
        Role.entries.forEach { assertTrue(it.meetsMinimum(it), "$it should meet itself") }
    }

    @Test
    fun `meetsMinimum respects hierarchy`() {
        assertTrue(Role.ADMIN.meetsMinimum(Role.MAINTAIN))
        assertTrue(Role.ADMIN.meetsMinimum(Role.PUSH))
        assertTrue(Role.MAINTAIN.meetsMinimum(Role.PUSH))
        assertTrue(Role.PUSH.meetsMinimum(Role.TRIAGE))
        assertTrue(Role.TRIAGE.meetsMinimum(Role.PULL))
    }

    @Test
    fun `meetsMinimum is false going downward`() {
        assertFalse(Role.PULL.meetsMinimum(Role.PUSH))
        assertFalse(Role.TRIAGE.meetsMinimum(Role.PUSH))
        assertFalse(Role.PUSH.meetsMinimum(Role.MAINTAIN))
        assertFalse(Role.MAINTAIN.meetsMinimum(Role.ADMIN))
    }

    @Test
    fun `roleFromPermissions returns ADMIN`() =
        assertEquals(Role.ADMIN, roleFromPermissions(perms(admin = true, maintain = true, push = true)))

    @Test
    fun `roleFromPermissions returns MAINTAIN`() =
        assertEquals(Role.MAINTAIN, roleFromPermissions(perms(maintain = true, push = true)))

    @Test
    fun `roleFromPermissions returns PUSH`() =
        assertEquals(Role.PUSH, roleFromPermissions(perms(push = true)))

    @Test
    fun `roleFromPermissions returns TRIAGE`() =
        assertEquals(Role.TRIAGE, roleFromPermissions(perms(triage = true)))

    @Test
    fun `roleFromPermissions returns PULL when only pull`() =
        assertEquals(Role.PULL, roleFromPermissions(perms()))

    @Test
    fun `Role fromString is case-insensitive`() {
        assertEquals(Role.MAINTAIN, Role.fromString("maintain"))
        assertEquals(Role.MAINTAIN, Role.fromString("MAINTAIN"))
        assertEquals(Role.MAINTAIN, Role.fromString("  Maintain  "))
    }

    @Test
    fun `Role fromString throws on unknown value`() {
        val ex = assertFailsWith<IllegalArgumentException> { Role.fromString("owner") }
        assertContains(ex.message!!, "owner")
    }
}
