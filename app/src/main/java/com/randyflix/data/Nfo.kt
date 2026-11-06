package com.randyflix.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/** Tiny XML tree for Kodi / tinyMediaManager .nfo files. */
class XNode(val name: String, val attrs: Map<String, String>) {
    val children = mutableListOf<XNode>()
    val text = StringBuilder()

    val value: String? get() = text.toString().trim().takeIf { it.isNotEmpty() }
    fun child(n: String): XNode? = children.firstOrNull { it.name.equals(n, ignoreCase = true) }
    fun all(n: String): List<XNode> = children.filter { it.name.equals(n, ignoreCase = true) }
    fun str(n: String): String? = child(n)?.value
    fun int(n: String): Int? = str(n)?.toDoubleOrNull()?.toInt()
    fun path(vararg names: String): XNode? {
        var node: XNode? = this
        for (n in names) node = node?.child(n)
        return node
    }
}

object Nfo {
    /** Returns the root element, or null if the file isn't XML (e.g. an nfo that only holds a URL). */
    fun parse(bytes: ByteArray): XNode? {
        var root: XNode? = null
        try {
            val p = Xml.newPullParser()
            runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
            p.setInput(ByteArrayInputStream(bytes), null)
            val stack = ArrayList<XNode>()
            loop@ while (true) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> {
                        val attrs = HashMap<String, String>()
                        for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                        val node = XNode(p.name, attrs)
                        if (stack.isEmpty()) root = node else stack[stack.size - 1].children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.TEXT -> if (stack.isNotEmpty()) stack[stack.size - 1].text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                        if (stack.isEmpty()) break@loop   // ignore anything after the first root (multi-episode nfo, trailing URL)
                    }
                    XmlPullParser.END_DOCUMENT -> break@loop
                }
            }
        } catch (_: Exception) {
            // keep whatever was parsed before the error
        }
        return root
    }

    fun rating(n: XNode): Double? {
        val ratings = n.child("ratings")?.all("rating").orEmpty()
        val r = ratings.firstOrNull { it.attrs["default"] == "true" } ?: ratings.firstOrNull()
        return (r?.str("value") ?: n.str("rating"))?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    fun mpaa(n: XNode): String? = n.str("mpaa")
        ?.substringAfterLast(':')
        ?.removePrefix("Rated ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun genres(n: XNode): List<String> = n.all("genre")
        .mapNotNull { it.value }
        .flatMap { it.split('/') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    fun year(n: XNode): Int? = n.int("year")?.takeIf { it > 1800 }
        ?: n.str("premiered")?.take(4)?.toIntOrNull()
        ?: n.str("aired")?.take(4)?.toIntOrNull()

    /** Online poster/fanart URLs written by scrapers, used only when no local image exists. */
    fun remotePoster(n: XNode): String? = n.all("thumb")
        .firstOrNull { (it.attrs["aspect"] == "poster" || it.attrs["aspect"] == null) && it.value?.startsWith("http") == true }
        ?.value

    fun remoteFanart(n: XNode): String? = n.child("fanart")?.all("thumb")
        ?.firstOrNull { it.value?.startsWith("http") == true }?.value
}
