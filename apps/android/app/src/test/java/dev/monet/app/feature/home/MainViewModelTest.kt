package dev.monet.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {
    @Test
    fun `back from each screen goes to its parent and home exits`() {
        assertNull(Screen.Home.parent())
        assertEquals(Screen.Home, Screen.Settings.parent())
        assertEquals(Screen.Home, Screen.Library.parent())
        assertEquals(Screen.Library, Screen.Reader("doc").parent())
    }

    @Test
    fun `navigation walks down and back up the stack`() {
        val vm = MainViewModel()
        vm.open(Screen.Library)
        vm.open(Screen.Reader("d1"))
        assertEquals(Screen.Reader("d1"), vm.screen.value)
        assertTrue(vm.back())
        assertEquals(Screen.Library, vm.screen.value)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.screen.value)
        assertFalse(vm.back())
    }

    @Test
    fun `explain ignores blank text and keeps the latest otherwise`() {
        val vm = MainViewModel()
        vm.explain("   ")
        assertNull(vm.sheetText.value)
        vm.explain(" 你好 ")
        assertEquals("你好", vm.sheetText.value)
        vm.dismiss()
        assertNull(vm.sheetText.value)
    }

    @Test
    fun `notebook and review sit under home and notebook`() {
        assertEquals(Screen.Home, Screen.Notebook.parent())
        assertEquals(Screen.Notebook, Screen.Review.parent())
        val vm = MainViewModel()
        vm.open(Screen.Notebook)
        vm.open(Screen.Review)
        assertTrue(vm.back())
        assertEquals(Screen.Notebook, vm.screen.value)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun `a saved annotation opens the sheet with its stored data until dismissed or replaced`() {
        val a = kotlinx.coroutines.runBlocking {
            dev.monet.app.core.network.FakeMonetApi(latencyMs = 0).annotate(
                dev.monet.app.core.network.AnnotateRequest(text = "你好", targetLang = dev.monet.app.core.network.Lang.EN),
            )
        }
        val vm = MainViewModel()
        vm.explainSaved(a)
        assertEquals("你好", vm.sheetText.value)
        assertEquals(a, vm.sheetAnnotation.value)
        vm.explain("再见")
        assertNull(vm.sheetAnnotation.value)
        vm.explainSaved(a)
        vm.dismiss()
        assertNull(vm.sheetAnnotation.value)
        assertNull(vm.sheetText.value)
    }
}
