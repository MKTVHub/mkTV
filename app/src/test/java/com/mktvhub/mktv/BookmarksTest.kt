package com.mktvhub.mktv

import com.mktvhub.mktv.Bookmarks.Folder
import com.mktvhub.mktv.Bookmarks.Link
import com.mktvhub.mktv.Bookmarks.Loc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {
    private val a = Link("A", "https://a.com/")
    private val b = Link("B", "https://b.com/")
    private val c = Link("C", "https://c.com/")

    @Test fun legacyFlatListLoadsAndSavesInTheSameFormat() {
        val old = """[{"title":"Google","url":"https:\/\/www.google.com\/"},{"title":"BBC","url":"https:\/\/www.bbc.co.uk\/"}]"""
        val list = Bookmarks.parse(old)
        assertEquals(listOf(Link("Google", "https://www.google.com/"), Link("BBC", "https://www.bbc.co.uk/")), list)
        // Without folders the saved JSON is the old shape, so older builds can still read it.
        assertEquals(list, Bookmarks.parse(Bookmarks.toJson(list)))
        assertFalse(Bookmarks.toJson(list).contains("items"))
    }

    @Test fun foldersRoundTrip() {
        val list = listOf(a, Folder("News", listOf(b, c)))
        val json = Bookmarks.toJson(list)
        assertEquals(list, Bookmarks.parse(json))
        assertEquals(listOf(a, b, c), Bookmarks.flatten(list))
    }

    @Test fun badEntriesAreSkippedNotFatal() {
        assertEquals(emptyList<Bookmarks.Node>(), Bookmarks.parse("not json"))
        val list = Bookmarks.parse("""[{"title":"x"},{"url":"https://u.com/"},5,{"title":"","items":[{"title":"n"}]}]""")
        assertEquals(listOf(Link("https://u.com/", "https://u.com/"), Folder("Folder")), list)
    }

    @Test fun addDedupesAcrossFoldersAndCanTargetAFolder() {
        val list = listOf(Folder("F", listOf(a)), b)
        assertEquals(listOf(Folder("F"), b, Link("A2", "https://a.com")), Bookmarks.add(list, Link("A2", "https://a.com")))
        assertEquals(listOf(Folder("F", listOf(a, c)), b), Bookmarks.add(list, c, folder = 0))
        // A folder index that isn't a folder falls back to the top level.
        assertEquals(listOf(Folder("F", listOf(a)), b, c), Bookmarks.add(list, c, folder = 1))
    }

    @Test fun containsAndRemoveUrlLookInsideFolders() {
        val list = listOf(Folder("F", listOf(a)), b)
        assertTrue(Bookmarks.containsUrl(list, "https://a.com"))
        assertEquals(listOf(Folder("F"), b), Bookmarks.removeUrl(list, "https://a.com/"))
    }

    @Test fun renameTrimsAndIgnoresBlank() {
        val list = listOf(a, Folder("F", listOf(b)))
        assertEquals(Link("Apple", a.url), Bookmarks.rename(list, Loc(-1, 0), "  Apple ")[0])
        assertEquals(Folder("Video", listOf(b)), Bookmarks.rename(list, Loc(-1, 1), "Video")[1])
        assertEquals(Link("Bee", b.url), (Bookmarks.rename(list, Loc(1, 0), "Bee")[1] as Folder).items[0])
        assertSame(list, Bookmarks.rename(list, Loc(-1, 0), "   "))
    }

    @Test fun moveWithinRowAndStopsAtEdges() {
        val list = listOf(a, b, c)
        val (moved, at) = Bookmarks.move(list, Loc(-1, 0), 1)
        assertEquals(listOf(b, a, c), moved)
        assertEquals(Loc(-1, 1), at)
        assertEquals(list to Loc(-1, 0), Bookmarks.move(list, Loc(-1, 0), -1))
        assertEquals(list to Loc(-1, 2), Bookmarks.move(list, Loc(-1, 2), 1))
        val nested = listOf(Folder("F", listOf(a, b)))
        assertEquals(listOf(Folder("F", listOf(b, a))) to Loc(0, 0), Bookmarks.move(nested, Loc(0, 1), -1))
    }

    @Test fun moveIntoAndOutOfFolder() {
        val list = listOf(a, Folder("F", listOf(b)), c)
        val into = Bookmarks.moveInto(list, Loc(-1, 2), 1)
        assertEquals(listOf(a, Folder("F", listOf(b, c))), into)
        val out = Bookmarks.moveOut(into, Loc(1, 0))
        assertEquals(listOf(a, Folder("F", listOf(c)), b), out)
        // Folders can't go into folders, and only top-level links move in.
        assertSame(list, Bookmarks.moveInto(list, Loc(-1, 1), 1))
        assertSame(list, Bookmarks.moveInto(list, Loc(-1, 0), 2))
    }

    @Test fun removingAFolderKeepsItsSites() {
        val list = listOf(a, Folder("F", listOf(b, c)), Link("D", "https://d.com/"))
        assertEquals(listOf(a, b, c, Link("D", "https://d.com/")), Bookmarks.remove(list, Loc(-1, 1)))
        assertEquals(listOf(a, Folder("F", listOf(c)), Link("D", "https://d.com/")), Bookmarks.remove(list, Loc(1, 0)))
    }

    @Test fun newFolderNamesAreUnique() {
        var list = Bookmarks.newFolder(emptyList())
        list = Bookmarks.newFolder(list)
        assertEquals(listOf("New folder", "New folder 2"), list.map { it.title })
    }
}
