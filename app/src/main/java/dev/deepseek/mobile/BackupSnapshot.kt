package dev.deepseek.mobile

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/** Preserve referenced archives, while avoiding exponential backup-within-backup growth. */
internal data class BackupSnapshot(
    val texts: Map<String, ByteArray>,
    val omittedBackups: Set<String>,
) {
    companion object {
        fun capture(context: Context, root: File): BackupSnapshot {
            val references = buildList {
                val conversation = File(root, "conversations-v3.json")
                if (conversation.isFile) add(conversation)
                listOf("memory.md", "persona_memory.md", "projects", "sessions").forEach { name ->
                    val folder = File(root, name)
                    if (folder.exists())
                        addAll(
                            folder
                                .walkTopDown()
                                .filter {
                                    it.isFile &&
                                        it.extension == "md" &&
                                        it.canonicalFile == it.absoluteFile
                                }
                                .toList(),
                        )
                }
            }
            val frozen = references.associate { it.absolutePath to it.readBytes() }
            val text = frozen.values.map { String(it, Charsets.UTF_8) }
            val omitted =
                AttachmentStore(context)
                    .all()
                    .filter { file ->
                        file.mime == "application/zip" &&
                            file.name.matches(Regex("DSHA-backup-\\d{4}-\\d{2}-\\d{2}\\.zip")) &&
                            file.sourceTask.isBlank() &&
                            file.sourcePath.isBlank() &&
                            text.none { it.contains(file.id) }
                    }
                    .filter { file ->
                        runCatching {
                                ZipFile(AttachmentStore(context).file(file.id)).use { zip ->
                                    zip.getEntry("conversations-v3.json") != null &&
                                        zip.getEntry("experience.json") != null
                                }
                            }
                            .getOrDefault(false)
                    }
                    .map { it.id }
                    .toSet()
            return BackupSnapshot(frozen, omitted)
        }
    }

    fun includes(file: File, root: File): Boolean {
        val parts = file.relativeTo(root).invariantSeparatorsPath.split('/')
        return !(parts.size >= 3 && parts[0] == "attachments" && parts[1] in omittedBackups)
    }
}
