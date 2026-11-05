package ru.nordcam.app.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Простейшее дерево XML без пространств имён — для разбора ответов ONVIF. */
class XNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<XNode>()
    var text: String = ""

    fun child(n: String): XNode? = children.firstOrNull { it.name == n }

    /** Первый потомок на любой глубине с таким именем. */
    fun find(n: String): XNode? {
        for (c in children) {
            if (c.name == n) return c
            c.find(n)?.let { return it }
        }
        return null
    }

    fun findAll(n: String, out: MutableList<XNode> = mutableListOf()): List<XNode> {
        for (c in children) {
            if (c.name == n) out += c
            c.findAll(n, out)
        }
        return out
    }

    fun path(vararg names: String): XNode? {
        var cur: XNode? = this
        for (n in names) cur = cur?.child(n) ?: cur?.find(n)
        return cur
    }

    fun textOf(n: String): String = find(n)?.text?.trim().orEmpty()

    /** Весь текст узла и потомков — для тем событий. */
    fun allText(): String = buildString {
        append(text)
        children.forEach { append(' ').append(it.allText()) }
    }

    companion object {
        fun parse(xml: String): XNode {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(StringReader(xml))
            val root = XNode("#root", emptyMap())
            val stack = ArrayDeque<XNode>()
            stack.addLast(root)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val attrs = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                        val n = XNode(p.name, attrs)
                        stack.last().children += n
                        stack.addLast(n)
                    }
                    XmlPullParser.TEXT -> stack.last().text += p.text
                    XmlPullParser.END_TAG -> if (stack.size > 1) stack.removeLast()
                }
                ev = p.next()
            }
            return root
        }
    }
}
