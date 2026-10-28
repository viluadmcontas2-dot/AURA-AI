package ai.aura.app.ui.screens

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.Gate
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.screens.capabilities.CapGroup
import ai.aura.app.ui.screens.capabilities.CapabilitiesModel
import ai.aura.app.ui.screens.capabilities.CapabilitiesSource
import ai.aura.app.ui.screens.capabilities.Mode
import ai.aura.app.ui.screens.capabilities.Skill
import ai.aura.app.ui.screens.capabilities.applyModes
import ai.aura.app.ui.screens.capabilities.byGroup
import ai.aura.app.ui.screens.capabilities.inUse
import ai.aura.app.ui.screens.capabilities.modesOf
import ai.aura.app.ui.screens.memory.MemoryActions
import ai.aura.app.ui.screens.memory.MemoryFilter
import ai.aura.app.ui.screens.memory.MemoryItem
import ai.aura.app.ui.screens.memory.MemoryKind
import ai.aura.app.ui.screens.memory.MemoryModel
import ai.aura.app.ui.screens.memory.origin
import ai.aura.app.ui.screens.memory.visible
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** T11: Memória, Capacidades e Detalhe. Lógica na JVM; as telas só desenham o que estes modelos entregam. */
class MemoryCapabilitiesTest {
    @get:Rule val tmp = TemporaryFolder()

    // 12 out 2024, 12:00 UTC: o dia certo em qualquer fuso razoável.
    private val t0 = 1_728_734_400.0
    private val day = 86_400.0

    private fun item(id: String, kind: MemoryKind, source: String = "user", at: Double = t0) =
        MemoryItem(id, kind, "texto $id", source, at)

    private fun <T> Reading<T>.loaded(): Reading.Loaded<T> = this as Reading.Loaded<T>

    private class FakeMemory(var items: List<MemoryItem>) : MemoryActions {
        var down = false
        var hang = false
        private suspend fun gate() {
            if (hang) awaitCancellation()
            if (down) throw IOException("sem motor")
        }
        override suspend fun list(): List<MemoryItem> { gate(); return items }
        override suspend fun forget(id: String) { gate(); items = items.filterNot { it.id == id } }
        override suspend fun forgetAll(): Int { gate(); return items.size.also { items = emptyList() } }
    }

    private class FakePolicy(private val live: PolicyDoc) : CapabilitiesSource {
        var answer: PolicyResult? = null
        val saved = mutableListOf<PolicyDoc>()
        override suspend fun skills() = listOf(Skill("vibe-coding", "vibe-coding", "d", "bundled"))
        override suspend fun policy(): PolicyResult = PolicyResult.Ok(live)
        override suspend fun savePolicy(doc: PolicyDoc): PolicyResult {
            saved += doc
            return answer ?: PolicyResult.Ok(doc)
        }
    }

    @Test fun `filtros ordem e frase de origem da memoria`() {
        val pref = item("p", MemoryKind.Preference)
        val proj = item("j", MemoryKind.Project, "repository", t0 + day)
        val dev = item("d", MemoryKind.Device, "device", t0 + 2 * day)
        val rule = item("r", MemoryKind.Rule, "user", t0 + 3 * day)
        val all = listOf(pref, proj, dev, rule)

        assertEquals(listOf("r", "d", "j", "p"), all.visible(MemoryFilter.All).map { it.id })
        assertEquals(listOf("p"), all.visible(MemoryFilter.Preferences).map { it.id })
        assertEquals(listOf("j"), all.visible(MemoryFilter.Projects).map { it.id })
        assertEquals(listOf("d"), all.visible(MemoryFilter.Devices).map { it.id })
        assertTrue(emptyList<MemoryItem>().visible(MemoryFilter.All).isEmpty())

        assertEquals("você disse em 12 out", pref.origin())
        assertEquals("você definiu", rule.origin())
        assertEquals("aprendido no repositório", proj.origin())
        assertEquals("aprendido ao conectar", dev.origin())
        assertEquals("outro-lugar", item("x", MemoryKind.Project, "outro-lugar").origin())
    }

    @Test fun `sem motor a memoria mostra a ultima leitura guardada e diz que e antiga`() = runTest {
        val items = listOf(item("p", MemoryKind.Preference))
        val store = LocalStore(tmp.newFolder())
        val fake = FakeMemory(items)
        val first = MemoryModel(fake, store)
        first.load()
        assertEquals(Reading.Loaded(items, stale = false), first.state.value)

        fake.down = true
        val reopened = MemoryModel(fake, store)
        reopened.load()
        assertEquals(Reading.Loaded(items, stale = true), reopened.state.value)
    }

    @Test fun `sem motor e sem leitura guardada a memoria mostra erro e nunca fica carregando`() = runTest {
        val broken = listOf<(FakeMemory) -> Unit>({ it.down = true }, { it.hang = true })
        for (breakIt in broken) {
            val model = MemoryModel(FakeMemory(emptyList()).also(breakIt), LocalStore(tmp.newFolder()))
            model.load()
            assertTrue(model.state.value is Reading.Failed)
        }
    }

    @Test fun `esquecer uma so some depois do motor confirmar`() = runTest {
        val a = item("a", MemoryKind.Preference)
        val b = item("b", MemoryKind.Project)
        val fake = FakeMemory(listOf(a, b))
        val store = LocalStore(tmp.newFolder())
        val model = MemoryModel(fake, store)
        model.load()

        fake.down = true
        model.forget("a")
        assertEquals(listOf(a, b), model.state.value.loaded().value)
        assertNotNull(model.notice.value)

        fake.down = false
        model.forget("a")
        assertEquals(listOf(b), fake.items)
        assertEquals(listOf(b), model.state.value.loaded().value)
        assertNull(model.notice.value)

        fake.down = true
        val reopened = MemoryModel(fake, store)
        reopened.load()
        assertEquals(Reading.Loaded(listOf(b), stale = true), reopened.state.value)
    }

    @Test fun `esquecer tudo so apaga a copia local depois do motor confirmar`() = runTest {
        val fake = FakeMemory(listOf(item("a", MemoryKind.Preference), item("b", MemoryKind.Rule)))
        val store = LocalStore(tmp.newFolder())
        val model = MemoryModel(fake, store)
        model.load()

        fake.down = true
        model.forgetAll()
        assertEquals(2, model.state.value.loaded().value.size)
        assertNotNull(model.notice.value)

        fake.down = false
        model.forgetAll()
        assertTrue(fake.items.isEmpty())
        assertEquals(Reading.Loaded(emptyList<MemoryItem>(), stale = false), model.state.value)

        fake.down = true
        val reopened = MemoryModel(fake, store)
        reopened.load()
        assertEquals(Reading.Loaded(emptyList<MemoryItem>(), stale = true), reopened.state.value)
    }

    @Test fun `capacidades tem 4 grupos e o em uso vem so dos jobs rodando`() {
        val skills = listOf(
            Skill("vibe-coding", "vibe-coding", "d", "bundled"),
            Skill("android-diagnosis", "android-diagnosis", "d", "bundled"),
            Skill("minha-skill", "minha-skill", "d", "user"),
        )
        val groups = skills.byGroup()
        assertEquals(
            listOf(CapGroup.Engineering, CapGroup.Clarity, CapGroup.Android, CapGroup.Organization),
            groups.keys.toList(),
        )
        assertEquals(listOf(1, 0, 1, 1), groups.values.map { it.size })

        fun job(id: String, kind: String, status: JobStatus) = Job(id, "r$id", "t", kind, status, JobRisk.Read, 0)
        val jobs = listOf(
            job("1", "adb", JobStatus.Running), job("2", "adb", JobStatus.Running),
            job("3", "files", JobStatus.Completed), job("4", "claude_cli", JobStatus.Running),
            job("5", "shell", JobStatus.AwaitingApproval),
        )
        assertEquals(mapOf("android-diagnosis" to listOf("1", "2")), inUse(jobs))
    }

    @Test fun `modo vira politica e so o que mudou e gravado`() {
        val doc = PolicyDoc(version = 3)
        val next = applyModes(doc, mapOf("kind.shell" to Mode.Ask, "risk.write" to Mode.Ask, "kind.root" to Mode.Off))

        assertEquals(Gate.Ask, next.byKind["shell"])
        assertEquals(Gate.Ask, next.byRisk["write"])
        assertFalse(next.root.enabled)
        assertEquals(Gate.Auto, next.byKind["adb"])
        val modes = modesOf(next)
        assertEquals(Mode.Off, modes["kind.root"])
        assertEquals(Mode.Ask, modes["kind.shell"])
        assertEquals(Mode.Always, modes["kind.adb"])

        val back = applyModes(next, mapOf("kind.root" to Mode.Always))
        assertTrue(back.root.enabled)
        assertEquals(Gate.Auto, back.byKind["root"])
    }

    @Test fun `salvar permissoes grava sobre a politica viva e erro nao muda nada na tela`() = runTest {
        val source = FakePolicy(PolicyDoc(version = 3))
        val model = CapabilitiesModel(source, LocalStore(tmp.newFolder()))
        model.load()

        assertNull(model.save(mapOf("kind.shell" to Mode.Ask)))
        assertEquals(Gate.Ask, source.saved.single().byKind["shell"])
        assertEquals(Mode.Ask, model.state.value.loaded().value.modes["kind.shell"])

        for (answer in listOf(PolicyResult.Rejected("invalid_request"), PolicyResult.Unavailable())) {
            source.answer = answer
            assertNotNull(model.save(mapOf("kind.adb" to Mode.Ask)))
            assertEquals(Mode.Always, model.state.value.loaded().value.modes["kind.adb"])
        }
    }
}
