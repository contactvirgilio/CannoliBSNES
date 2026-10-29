package dev.cannoli.scorza.saves

import dev.cannoli.scorza.config.CannoliPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SavesTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Pair<CannoliPaths, SavesTransfer> {
        val p = CannoliPaths(tmp.newFolder("root"))
        return p to SavesTransfer(p)
    }

    private val emerald = "Pokemon Emerald (USA)"
    private fun targets() = mapOf(
        SavesTransfer.nameKey(emerald) to listOf(SavesTransfer.Target("GBA", emerald)),
        SavesTransfer.nameKey("Tetris") to listOf(SavesTransfer.Target("GB", "Tetris"), SavesTransfer.Target("NES", "Tetris")),
    )

    @Test fun `export zips saves and states`() {
        val (p, t) = setup()
        p.savesFor("GBA").mkdirs(); File(p.savesFor("GBA"), "$emerald.srm").writeText("old")
        p.saveStateDir("GBA", emerald).mkdirs(); File(p.saveStateDir("GBA", emerald), "$emerald.state1").writeText("st")
        val r = t.export()
        assertEquals(2, r.fileCount)
        val names = ZipFile(r.zip!!).use { z -> z.entries().toList().map { it.name }.sorted() }
        assertEquals(listOf("Save States/GBA/$emerald/$emerald.state1", "Saves/GBA/$emerald.srm"), names)
    }

    @Test fun `import matches by name, keeps bak, reports leftovers`() {
        val (p, t) = setup()
        p.savesFor("GBA").mkdirs(); File(p.savesFor("GBA"), "$emerald.srm").writeText("old")
        p.importDir.mkdirs()
        File(p.importDir, "pokemon emerald (usa).SAV").writeText("new")
        File(p.importDir, "Tetris.sav").writeText("x")
        File(p.importDir, "Unknown.srm").writeText("y")
        File(p.importDir, "notes.txt").writeText("z")
        val r = t.importFolder(targets())
        assertEquals(1, r.savesImported)
        assertEquals(listOf("Tetris.sav"), r.ambiguous)
        assertEquals(listOf("Unknown.srm"), r.unmatched)
        val srm = File(p.savesFor("GBA"), "$emerald.srm")
        assertEquals("new", srm.readText())
        assertEquals("old", File(srm.path + ".bak").readText())
        assertTrue(File(p.importDir, "Imported/pokemon emerald (usa).SAV").exists())
        assertTrue(File(p.importDir, "notes.txt").exists())
    }

    @Test fun `zip restore round trips and ignores escaping entries`() {
        val (p, t) = setup()
        p.savesFor("GBA").mkdirs()
        val srm = File(p.savesFor("GBA"), "$emerald.srm"); srm.writeText("old")
        val zip = t.export().zip!!
        srm.writeText("newer")
        p.importDir.mkdirs(); zip.copyTo(File(p.importDir, zip.name))
        assertEquals(1, t.importFolder(targets()).backupsRestored)
        assertEquals("old", srm.readText())
        assertEquals("newer", File(srm.path + ".bak").readText())

        val evil = File(p.importDir, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Saves/../../escape.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertEquals(listOf("evil.zip"), t.importFolder(targets()).failed)
        assertFalse(File(p.root, "escape.txt").exists())
    }

    @Test fun `drop-in sav imports once and only when newer`() {
        val (p, t) = setup()
        val dir = p.savesFor("SNES"); dir.mkdirs()
        File(dir, "Zelda.SAV").writeText("drop")
        assertTrue(t.importDropInSav("SNES", "Zelda"))
        assertEquals("drop", File(dir, "Zelda.srm").readText())
        assertTrue(File(dir, "Zelda.SAV.imported").exists())
        assertFalse(t.importDropInSav("SNES", "Zelda"))

        File(dir, "Mario.srm").writeText("current")
        File(dir, "Mario.sav").apply { writeText("older"); setLastModified(1000) }
        assertFalse(t.importDropInSav("SNES", "Mario"))
        assertEquals("current", File(dir, "Mario.srm").readText())
    }
}
