package dev.cannoli.scorza.saves

import dev.cannoli.scorza.config.CannoliPaths
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Save import/export, all plain file work under the Cannoli root:
 *
 * - [export] zips `Saves/` and `Save States/` into `Backup/`.
 * - [importFolder] processes everything dropped into `Import/`: backup zips are restored, and
 *   `.sav` / `.srm` files are matched to a game by ROM name and become its in-game save.
 * - [importDropInSav] runs at launch: a `<rom>.sav` placed next to the game's `.srm` is used
 *   when it is newer, then renamed to `.sav.imported`.
 *
 * Any in-game save that gets replaced is kept as `<rom>.srm.bak`, so an import can be undone.
 */
class SavesTransfer(private val paths: CannoliPaths) {

    /** A game a save file can belong to: platform tag plus the ROM name without extension. */
    data class Target(val tag: String, val romBaseName: String)

    data class ExportResult(val zip: File?, val fileCount: Int)

    data class ImportResult(
        val savesImported: Int = 0,
        val backupsRestored: Int = 0,
        val unmatched: List<String> = emptyList(),
        val ambiguous: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    )

    /** Files in the Import folder this can act on. */
    fun pendingImports(): List<File> =
        paths.importDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMPORTABLE }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Zips every save and state into Backup/, reporting (files done, total files) as it goes. */
    fun export(now: Date = Date(), onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ExportResult {
        val roots = listOf(paths.savesDir, paths.saveStatesDir).filter { it.isDirectory }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.map { root to it }.toList() }
        if (files.isEmpty()) return ExportResult(null, 0)
        onProgress(0, files.size)
        paths.backupDir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(now)
        val zip = File(paths.backupDir, "Cannoli Saves $stamp.zip")
        val tmp = File(paths.backupDir, ".${zip.name}.tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for ((index, pair) in files.withIndex()) {
                val (root, file) = pair
                val entry = ZipEntry(root.name + "/" + file.relativeTo(root).invariantSeparatorsPath)
                entry.time = file.lastModified()
                out.putNextEntry(entry)
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
                onProgress(index + 1, files.size)
            }
        }
        if (!tmp.renameTo(zip)) {
            tmp.delete()
            return ExportResult(null, 0)
        }
        return ExportResult(zip, files.size)
    }

    /**
     * Imports everything in the Import folder. [targetsByName] maps a lowercased, NFC-normalized
     * ROM name to every game with that name. Handled files are moved to `Import/Imported/`;
     * files that match no game, or more than one, stay where they are.
     */
    fun importFolder(targetsByName: Map<String, List<Target>>): ImportResult {
        var saves = 0
        var backups = 0
        val unmatched = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (file in pendingImports()) {
            try {
                if (file.extension.equals("zip", ignoreCase = true)) {
                    if (restoreZip(file) > 0) {
                        backups++
                        markDone(file)
                    } else {
                        failed += file.name
                    }
                    continue
                }
                val matches = targetsByName[nameKey(file.nameWithoutExtension)].orEmpty()
                when (matches.size) {
                    0 -> unmatched += file.name
                    1 -> {
                        installSave(file, matches.single())
                        saves++
                        markDone(file)
                    }
                    else -> ambiguous += file.name
                }
            } catch (_: Exception) {
                failed += file.name
            }
        }
        return ImportResult(saves, backups, unmatched, ambiguous, failed)
    }

    /**
     * Restores the `Saves/` and `Save States/` entries of a backup zip. Other entries, and any
     * path that would land outside those folders, are ignored. Returns how many files it wrote.
     */
    fun restoreZip(zipFile: File): Int {
        var written = 0
        val roots = mapOf(
            paths.savesDir.name to paths.savesDir,
            paths.saveStatesDir.name to paths.saveStatesDir,
        )
        ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                val top = name.substringBefore('/')
                val root = roots[top] ?: continue
                val rel = name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val dest = File(root, rel)
                if (!dest.canonicalPath.startsWith(root.canonicalPath + File.separator)) continue
                dest.parentFile?.mkdirs()
                if (root == paths.savesDir) backupExisting(dest)
                val tmp = File(dest.parentFile, ".${dest.name}.tmp")
                zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    if (!tmp.renameTo(dest)) { tmp.delete(); continue }
                }
                if (entry.time > 0) dest.setLastModified(entry.time)
                written++
            }
        }
        return written
    }

    /**
     * Launch-time pickup of `<rom>.sav` (any case) in the game's save folder. It becomes the
     * `.srm` when there is none yet or the `.sav` is newer, then is renamed so it imports once.
     */
    fun importDropInSav(tag: String, romBaseName: String): Boolean {
        val dir = paths.savesFor(tag)
        val sav = dir.listFiles()?.firstOrNull {
            it.isFile && it.extension.equals("sav", ignoreCase = true) &&
                nameKey(it.nameWithoutExtension) == nameKey(romBaseName)
        } ?: return false
        val srm = paths.sramFile(tag, romBaseName)
        if (srm.exists() && srm.lastModified() >= sav.lastModified()) return false
        installSave(sav, Target(tag, romBaseName))
        sav.renameTo(File(dir, sav.name + ".imported"))
        return true
    }

    private fun installSave(source: File, target: Target) {
        val srm = paths.sramFile(target.tag, target.romBaseName)
        srm.parentFile?.mkdirs()
        backupExisting(srm)
        val tmp = File(srm.parentFile, ".${srm.name}.tmp")
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(srm)) {
            srm.delete()
            if (!tmp.renameTo(srm)) { tmp.delete(); error("could not write ${srm.name}") }
        }
        srm.setLastModified(maxOf(source.lastModified(), System.currentTimeMillis()))
    }

    private fun backupExisting(file: File) {
        if (file.isFile && file.extension.equals("srm", ignoreCase = true)) {
            file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true)
        }
    }

    private fun markDone(file: File) {
        val doneDir = File(paths.importDir, DONE_DIR)
        doneDir.mkdirs()
        var dest = File(doneDir, file.name)
        var n = 2
        while (dest.exists()) dest = File(doneDir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        if (!file.renameTo(dest)) {
            file.copyTo(dest)
            file.delete()
        }
    }

    companion object {
        const val DONE_DIR = "Imported"
        private val IMPORTABLE = setOf("zip", "sav", "srm")

        fun nameKey(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
