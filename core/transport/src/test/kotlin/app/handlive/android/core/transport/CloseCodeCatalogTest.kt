package app.handlive.android.core.transport

import app.handlive.android.core.protocol.testing.SharedTestVectors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** [WsCloseCode] is exactly the table of 0.8.3, as `common.schema.json#/$defs/ws-close-code` of shared lists it. */
class CloseCodeCatalogTest {
    @Test
    fun closeCodesMatchTheSharedSchema() {
        val schema =
            Json.parseToJsonElement(File(SharedTestVectors.schemasDir, "common.schema.json").readText()).jsonObject
        val expected =
            schema
                .getValue("\$defs")
                .jsonObject
                .getValue("ws-close-code")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content.toInt() }
                .toSortedSet()
        val declared =
            WsCloseCode::class.java.declaredFields
                .filter { it.type == Short::class.javaPrimitiveType }
                .map { (it.get(null) as Short).toInt() }
                .toSortedSet()
        assertEquals(expected, declared)
    }
}
