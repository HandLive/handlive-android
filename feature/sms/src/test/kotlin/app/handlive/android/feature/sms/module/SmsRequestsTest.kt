package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.encoding.Base64Codecs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks of `sms/sync` (SMS-01 API 1 logic 1) and `sms/history` (SMS-03 API 1 logic 1), in their order. */
class SmsRequestsTest {
    private val access = FakeAccess()
    private val requests = SmsRequests(access)

    @Test
    fun smsOffComesFirstEvenForAMalformedRequest() {
        access.enabled = false
        access.missing += "READ_SMS"
        assertCode(ErrorCode.FEATURE_DISABLED, requests.sync(json("""{}""")))
        assertCode(ErrorCode.FEATURE_DISABLED, requests.history(json("""{}""")))
    }

    @Test
    fun aMissingReadSmsPermissionNamesItInFull() {
        access.missing += "READ_SMS"
        val error = refused(requests.sync(json("""{"thread_limit":200,"per_thread_limit":50}""")))
        assertEquals(ErrorCode.PERMISSION_MISSING, error.code)
        assertEquals("android.permission.READ_SMS", error.details!!["permission"]!!.jsonPrimitive.content)
        assertCode(
            ErrorCode.PERMISSION_MISSING,
            requests.history(json("""{"thread_id":42,"before_ts":1,"limit":50}""")),
        )
    }

    @Test
    fun limitsOutOfRangeAreBadRequests() {
        listOf(
            """{}""",
            """{"thread_limit":0,"per_thread_limit":50}""",
            """{"thread_limit":501,"per_thread_limit":50}""",
            """{"thread_limit":200,"per_thread_limit":0}""",
            """{"thread_limit":200,"per_thread_limit":201}""",
            """{"thread_limit":200,"per_thread_limit":[50]}""",
        ).forEach { assertCode(ErrorCode.BAD_REQUEST, requests.sync(json(it)), it) }
        listOf(
            """{"thread_id":42,"before_ts":1}""",
            """{"thread_id":42,"before_ts":1,"limit":0}""",
            """{"thread_id":42,"before_ts":1,"limit":201}""",
            """{"thread_id":42,"before_ts":-1,"limit":50}""",
        ).forEach { assertCode(ErrorCode.BAD_REQUEST, requests.history(json(it)), it) }
        assertTrue(requests.sync(json("""{"thread_limit":500,"per_thread_limit":200}""")) is Checked.Valid)
    }

    @Test
    fun anUnreadableCursorOrPageTokenSaysWhich() {
        val cursor = b64u("""{"v":1,"id":5,"t":7}""")
        val badCursor = refused(requests.sync(json("""{"cursor":"@@","thread_limit":200,"per_thread_limit":50}""")))
        assertEquals(ErrorCode.SMS_CURSOR_INVALID, badCursor.code)
        assertEquals("cursor", badCursor.details!!["reason"]!!.jsonPrimitive.content)

        val firstSyncToken = b64u("""{"v":1,"m":9,"th":[42],"o":0}""")
        val limits = """"thread_limit":200,"per_thread_limit":50"""
        val wrongKind = refused(requests.sync(json("""{"cursor":"$cursor","page_token":"$firstSyncToken",$limits}""")))
        assertEquals(ErrorCode.SMS_CURSOR_INVALID, wrongKind.code)
        assertEquals("page_token", wrongKind.details!!["reason"]!!.jsonPrimitive.content)
        val otherVersion = b64u("""{"v":2,"m":9,"a":3}""")
        assertCode(
            ErrorCode.SMS_CURSOR_INVALID,
            requests.sync(json("""{"cursor":"$cursor","page_token":"$otherVersion",$limits}""")),
        )
        val valid = requests.sync(json("""{"page_token":"$firstSyncToken",$limits}""")) as Checked.Valid
        assertNotNull(valid.value.token)
    }

    private fun assertCode(
        code: ErrorCode,
        checked: Checked<*>,
        message: String = code.name,
    ) = assertEquals(message, code, refused(checked).code)

    private fun refused(checked: Checked<*>): SmsError = (checked as Checked.Refused).error

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun b64u(text: String) = Base64Codecs.encodeB64u(text.toByteArray())
}

/** Settings and permissions as the tests set them: SMS on, everything granted. */
class FakeAccess : SmsAccess {
    var enabled = true
    val missing = mutableSetOf<String>()

    override fun enabled() = enabled

    override fun granted(permission: String) = permission !in missing
}
