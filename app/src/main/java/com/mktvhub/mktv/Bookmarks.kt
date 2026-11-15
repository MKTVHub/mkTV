package com.mktvhub.mktv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Favourites as a small bookmark tree: links at the top level plus one level of folders.
 * Pure (only org.json) so it is unit-tested on the JVM.
 *
 * Storage stays backward compatible with the old flat `shortcuts` list: a link is still `{"title","url"}`,
 * a folder is `{"title","items":[links]}`. A list without folders is byte-for-byte the old format, and old
 * data loads unchanged; the new shape is only written once the user makes a folder.
 */
object Bookmarks {
    sealed class Node { abstract val title: String }
    data class Link(override val title: String, val url: String) : Node()
    data class Folder(override val title: String, val items: List<Link> = emptyList()) : Node()

    /** Where a node lives: [folder] is the top-level index of its folder, or -1 for the top level. */
    data class Loc(val folder: Int, val index: Int) { val inFolder get() = folder >= 0 }

    fun parse(raw: String): List<Node> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val items = o.optJSONArray("items")
            if (items != null) Folder(o.optString("title").ifBlank { "Folder" }, (0 until items.length()).mapNotNull { j -> link(items.optJSONObject(j)) })
            else link(o)
        }
    }

    private fun link(o: JSONObject?): Link? {
        val url = o?.optString("url").orEmpty()
        if (url.isBlank()) return null
        return Link(o!!.optString("title").ifBlank { url }, url)
    }

    fun toJson(list: List<Node>): String {
        fun linkJson(l: Link) = JSONObject().put("title", l.title).put("url", l.url)
        val arr = JSONArray()
        list.forEach { n ->
            when (n) {
                is Link -> arr.put(linkJson(n))
                is Folder -> arr.put(JSONObject().put("title", n.title).put("items", JSONArray().also { a -> n.items.forEach { a.put(linkJson(it)) } }))
            }
        }
        return arr.toString()
    }

    /** Every link, folders opened in place, in display order. */
    fun flatten(list: List<Node>): List<Link> = list.flatMap { if (it is Folder) it.items else listOf(it as Link) }

    fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')

    fun containsUrl(list: List<Node>, url: String?) = flatten(list).any { sameUrl(it.url, url) }

    /** Drops the URL wherever it is (top level or inside a folder). */
    fun removeUrl(list: List<Node>, url: String?): List<Node> = list.mapNotNull { n ->
        when (n) {
            is Link -> n.takeUnless { sameUrl(it.url, url) }
            is Folder -> n.copy(items = n.items.filterNot { sameUrl(it.url, url) })
        }
    }

    /** Adds at the end of the top level (or of [folder]); an existing copy of the URL anywhere is replaced. */
    fun add(list: List<Node>, link: Link, folder: Int = -1): List<Node> {
        val cleaned = removeUrl(list, link.url)
        if (folder < 0 || cleaned.getOrNull(folder) !is Folder) return cleaned + link
        return cleaned.mapIndexed { i, n -> if (i == folder) (n as Folder).copy(items = n.items + link) else n }
    }

    fun get(list: List<Node>, at: Loc): Node? =
        if (at.inFolder) (list.getOrNull(at.folder) as? Folder)?.items?.getOrNull(at.index) else list.getOrNull(at.index)

    fun newFolder(list: List<Node>, name: String = "New folder"): List<Node> = list + Folder(uniqueName(list, name))

    private fun uniqueName(list: List<Node>, name: String): String {
        val taken = list.filterIsInstance<Folder>().map { it.title }.toSet()
        if (name !in taken) return name
        return (2..999).map { "$name $it" }.first { it !in taken }
    }

    fun rename(list: List<Node>, at: Loc, title: String): List<Node> {
        val t = title.trim()
        if (t.isEmpty()) return list
        return update(list, at) { n -> when (n) { is Link -> n.copy(title = t); is Folder -> n.copy(title = t) } }
    }

    /** Removes the node; a removed folder's links go back to the top level where the folder was, so nothing is lost. */
    fun remove(list: List<Node>, at: Loc): List<Node> {
        if (at.inFolder) return updateFolder(list, at.folder) { it.copy(items = it.items.filterIndexed { i, _ -> i != at.index }) }
        val n = list.getOrNull(at.index) ?: return list
        val m = list.toMutableList(); m.removeAt(at.index)
        if (n is Folder) m.addAll(at.index, n.items)
        return m
    }

    /** Moves the node one place left (-1) or right (+1) within its own row; returns the list and its new location. */
    fun move(list: List<Node>, at: Loc, by: Int): Pair<List<Node>, Loc> {
        if (at.inFolder) {
            val f = list.getOrNull(at.folder) as? Folder ?: return list to at
            val to = at.index + by
            if (at.index !in f.items.indices || to !in f.items.indices) return list to at
            return updateFolder(list, at.folder) { it.copy(items = swap(it.items, at.index, to)) } to at.copy(index = to)
        }
        val to = at.index + by
        if (at.index !in list.indices || to !in list.indices) return list to at
        return swap(list, at.index, to) to at.copy(index = to)
    }

    /** Puts a top-level link at the end of folder [folder]. */
    fun moveInto(list: List<Node>, at: Loc, folder: Int): List<Node> {
        val n = list.getOrNull(at.index) as? Link ?: return list
        if (at.inFolder || list.getOrNull(folder) !is Folder || folder == at.index) return list
        val withItem = list.mapIndexed { i, x -> if (i == folder) (x as Folder).copy(items = x.items + n) else x }
        return withItem.filterIndexed { i, _ -> i != at.index }
    }

    /** Takes a link out of its folder and puts it right after the folder at the top level. */
    fun moveOut(list: List<Node>, at: Loc): List<Node> {
        if (!at.inFolder) return list
        val n = get(list, at) as? Link ?: return list
        val m = remove(list, at).toMutableList()
        m.add(at.folder + 1, n)
        return m
    }

    private fun update(list: List<Node>, at: Loc, f: (Node) -> Node): List<Node> =
        if (at.inFolder) updateFolder(list, at.folder) { fo -> fo.copy(items = fo.items.mapIndexed { i, l -> if (i == at.index) f(l) as Link else l }) }
        else list.mapIndexed { i, n -> if (i == at.index) f(n) else n }

    private fun updateFolder(list: List<Node>, folder: Int, f: (Folder) -> Folder): List<Node> =
        list.mapIndexed { i, n -> if (i == folder && n is Folder) f(n) else n }

    private fun <T> swap(l: List<T>, a: Int, b: Int): List<T> = l.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
}
