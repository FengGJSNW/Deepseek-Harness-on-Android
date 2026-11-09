package dev.deepseek.mobile

import android.util.Xml
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

/** Local text extraction only: never runs macros, loads remote relationships, or evaluates HTML. */
internal object DocumentText {
    fun read(file: File, info: LocalAttachment, maxChars: Int = 100000): String {
        val extension = info.name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("docx", "pptx", "xlsx")) return office(file, extension, maxChars)
        val text =
            info.mime.startsWith("text/") ||
                extension in
                    setOf(
                        "md",
                        "json",
                        "jsonl",
                        "toml",
                        "yaml",
                        "yml",
                        "xml",
                        "kt",
                        "java",
                        "py",
                        "js",
                        "ts",
                        "sh",
                        "csv",
                        "log",
                        "svg",
                    )
        require(text) { "此格式暂不提供文本解析，请打开原文件查看。" }
        val bytes = file.inputStream().use { bounded(it, maxChars * 4 + 4) }
        require(!bytes.take(8192).contains(0.toByte())) { "此文件不是 UTF-8 文本" }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").take(maxChars) +
            if (file.length() > bytes.size || bytes.toString(Charsets.UTF_8).length > maxChars)
                "\n…（内容已截断）"
            else ""
    }

    private fun bounded(input: java.io.InputStream, limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (result.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - result.size()))
            if (count < 0) break
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun office(file: File, extension: String, maxChars: Int): String =
        ZipFile(file).use { zip ->
            var budget = 2_000_000
            fun xml(name: String, collect: (XmlPullParser, StringBuilder) -> Unit): String {
                val entry = zip.getEntry(name) ?: return ""
                require(entry.size in 0..2_000_000 && entry.size <= budget) { "文档解压内容超出预览限制" }
                val bytes = zip.getInputStream(entry).use { bounded(it, budget + 1) }
                budget -= bytes.size
                require(budget >= 0) { "文档解压内容超出预览限制" }
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                parser.setInput(bytes.inputStream(), "UTF-8")
                val out = StringBuilder()
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    require(parser.eventType != XmlPullParser.DOCDECL) { "文档包含不受支持的实体" }
                    collect(parser, out)
                    if (out.length > maxChars) break
                    parser.next()
                }
                return out.toString()
            }
            val paragraph: (XmlPullParser, StringBuilder) -> Unit = { parser, out ->
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "t" ->
                        out.append(parser.nextText())
                    parser.eventType == XmlPullParser.END_TAG && parser.name in setOf("p", "tr") ->
                        out.append('\n')
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "tc" ->
                        out.append('\t')
                }
            }
            val out =
                when (extension) {
                    "docx" -> xml("word/document.xml", paragraph)
                    "pptx" ->
                        zip.entries()
                            .asSequence()
                            .map { it.name }
                            .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                            .sortedBy {
                                it.substringAfter("slide", "").filter(Char::isDigit).toIntOrNull()
                                    ?: 0
                            }
                            .take(150)
                            .mapIndexed { index, name ->
                                "第 ${index + 1} 页\n" + xml(name, paragraph)
                            }
                            .joinToString("\n\n")
                    else -> {
                        val shared = mutableListOf<String>()
                        var item = StringBuilder()
                        xml("xl/sharedStrings.xml") { parser, _ ->
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "si")
                                item = StringBuilder()
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "t")
                                item.append(parser.nextText())
                            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "si")
                                shared.add(item.toString())
                        }
                        zip.entries()
                            .asSequence()
                            .map { it.name }
                            .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                            .sorted()
                            .take(30)
                            .mapIndexed { index, name ->
                                var type = ""
                                "工作表 ${index + 1}\n" +
                                    xml(name) { parser, text ->
                                        if (
                                            parser.eventType == XmlPullParser.START_TAG &&
                                                parser.name == "c"
                                        )
                                            type = parser.getAttributeValue(null, "t").orEmpty()
                                        if (
                                            parser.eventType == XmlPullParser.START_TAG &&
                                                parser.name in setOf("v", "t")
                                        ) {
                                            val value = parser.nextText()
                                            text
                                                .append(
                                                    if (type == "s")
                                                        shared
                                                            .getOrNull(value.toIntOrNull() ?: -1)
                                                            .orEmpty()
                                                    else value,
                                                )
                                                .append('\t')
                                        }
                                        if (
                                            parser.eventType == XmlPullParser.END_TAG &&
                                                parser.name == "row"
                                        )
                                            text.append('\n')
                                    }
                            }
                            .joinToString("\n\n")
                    }
                }
            out.take(maxChars) + if (out.length > maxChars) "\n…（内容已截断）" else ""
        }
}
