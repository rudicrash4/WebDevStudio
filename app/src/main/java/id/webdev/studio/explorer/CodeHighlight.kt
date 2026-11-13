package id.webdev.studio.explorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Pewarnaan sintaks ringan berbasis regex (JS/TS/JSON, HTML/XML, CSS). Dilewati untuk teks besar. */
object CodeHighlight {
    private val cComment = Color(0xFF6A9955)
    private val cString = Color(0xFFCE9178)
    private val cKeyword = Color(0xFF569CD6)
    private val cNumber = Color(0xFFB5CEA8)
    private val cTag = Color(0xFF4EC9B0)
    private val cProp = Color(0xFF9CDCFE)
    const val MAX_CHARS = 60_000

    private val jsKeywords = listOf(
        "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "class", "extends", "new", "this", "import", "export", "from", "default", "async", "await", "try", "catch", "finally",
        "throw", "typeof", "instanceof", "in", "of", "null", "undefined", "true", "false", "interface", "type", "enum", "implements",
        "public", "private", "protected", "readonly", "static", "yield", "void", "delete", "super"
    ).joinToString("|")

    private val js = Regex(
        """(//[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)|\b(0x[0-9a-fA-F]+|\d+(?:\.\d+)?)\b|\b($jsKeywords)\b"""
    )
    private val html = Regex("""(<!--[\s\S]*?-->)|("[^"\n]*"|'[^'\n]*')|(</?[A-Za-z][\w:-]*|/?>)|\b([A-Za-z-]+)(?==)""")
    private val css = Regex("""(/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(#[0-9a-fA-F]{3,8}\b|\b\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|s|ms|deg)?\b)|([a-zA-Z-]+)(?=\s*:)""")

    fun kind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "js", "mjs", "cjs", "jsx", "ts", "tsx", "json", "jsonc", "kt", "java" -> "js"
        "html", "htm", "xml", "svg", "vue" -> "html"
        "css", "scss", "less" -> "css"
        else -> ""
    }

    fun highlight(text: String, kind: String): AnnotatedString {
        if (kind.isEmpty() || text.length > MAX_CHARS) return AnnotatedString(text)
        val re = when (kind) { "js" -> js; "html" -> html; else -> css }
        val b = AnnotatedString.Builder(text)
        for (m in re.findAll(text)) {
            val g = (1..4).firstOrNull { m.groups[it] != null } ?: continue
            val color = when (g) {
                1 -> cComment
                2 -> cString
                3 -> if (kind == "html") cTag else cNumber
                else -> if (kind == "js") cKeyword else cProp
            }
            val r = m.groups[g]!!.range
            b.addStyle(SpanStyle(color = color), r.first, r.last + 1)
        }
        return b.toAnnotatedString()
    }
}

class CodeTransformation(private val kind: String) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(CodeHighlight.highlight(text.text, kind), OffsetMapping.Identity)
}
