@file:OptIn(ExperimentalComposeUiApi::class)

package `in`.procyk.savvry

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.materialkolor.ktx.toHex
import `in`.procyk.savvry.ui.SavvryAppTheme
import `in`.procyk.savvry.vm.PlatformContext
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLHeadElement
import org.w3c.dom.HTMLMetaElement
import org.w3c.dom.asList
import org.w3c.fetch.Response
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator


fun runSavvryWebApp() {
    val head = document.head ?: error("no <head>")
    val body = document.body ?: error("no <body>")
    val platformContext = PlatformContext()

    ComposeViewport(
        configure = { isA11YEnabled = false }
    ) {
        val fontFamilyResolver = LocalFontFamilyResolver.current
        var fontsLoaded by remember { mutableStateOf(false) }

        when {
            fontsLoaded -> {
                SavvryAppTheme(platformContext) { context ->
                    val backgroundColor = SavvryAppTheme.colors.background
                    LaunchedEffect(backgroundColor) {
                        head.replaceThemeColor(backgroundColor)
                        body.replaceBackgroundColor(backgroundColor)
                    }
                    SavvryAppInternal(
                        context = context,
                        initListId = when {
                            window.location.hash.isNotEmpty() -> window.location.hash.substring(1)
                            else -> null
                        }
                    )
                }
            }

            else -> {}
        }

        LaunchedEffect(Unit) {
            val notoColorEmojiBytes = loadRes(NotoColorEmoji)
            val fontFamily = FontFamily(listOf(Font("NotoColorEmoji", notoColorEmojiBytes)))
            fontFamilyResolver.preload(fontFamily)
            fontsLoaded = true
        }
    }
}

private fun HTMLHeadElement.replaceThemeColor(color: Color) {
    children.asList()
        .filterIsInstance<HTMLMetaElement>()
        .filter { it.getAttribute("name") == "theme-color" }
        .forEach { it.remove() }

    fun addThemeColor(content: String, media: String? = null) {
        val node = document.createElement("meta").apply {
            setAttribute("name", "theme-color")
            setAttribute("content", content)
            if (media != null) setAttribute("media", media)
        }
        appendChild(node)
    }

    val hex = color.toHex()
    addThemeColor(hex)
    addThemeColor(hex, "(prefers-color-scheme: light)")
    addThemeColor(hex, "(prefers-color-scheme: dark)")
}

private fun HTMLElement.replaceBackgroundColor(color: Color) {
    setAttribute("style", "background-color: rgb(${color.red.in255()}, ${color.green.in255()}, ${color.blue.in255()});")
}

private fun Float.in255(): Int = (this * 255).toInt()

private const val NotoColorEmoji: String = "./NotoColorEmoji.ttf"

@OptIn(ExperimentalWasmJsInterop::class)
private suspend fun loadRes(url: String): ByteArray =
    window.fetch(url).await<Response>().arrayBuffer().await<ArrayBuffer>().toByteArray()


private fun ArrayBuffer.toByteArray(): ByteArray {
    val source = Int8Array(this, 0, byteLength)
    return jsInt8ArrayToKotlinByteArray(source)
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """ (src, size, dstAddr) => {
        const mem8 = new Int8Array(wasmExports.memory.buffer, dstAddr, size);
        mem8.set(src);
    }
"""
)
private external fun jsExportInt8ArrayToWasm(src: Int8Array, size: Int, dstAddr: Int)

private fun jsInt8ArrayToKotlinByteArray(x: Int8Array): ByteArray {
    val size = x.length

    @OptIn(UnsafeWasmMemoryApi::class)
    return withScopedMemoryAllocator { allocator ->
        val memBuffer = allocator.allocate(size)
        val dstAddress = memBuffer.address.toInt()
        jsExportInt8ArrayToWasm(x, size, dstAddress)
        ByteArray(size) { i -> (memBuffer + i).loadByte() }
    }
}
