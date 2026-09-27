package app.handlive.android.feature.call.system

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CALL-01 API 1 logic 7 and the Query of CALL-01: E.164 for the SIM's country (the original string when that fails)
 * and the contact name through `PhoneLookup`, looked up once per number and only with `READ_CONTACTS`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemCallNumbersTest {
    private var contacts = true
    private val numbers =
        SystemCallNumbers(
            ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver,
            { "VN" },
        ) { contacts }

    @Before
    fun setUp() {
        lookups = 0
        Robolectric
            .buildContentProvider(Contacts::class.java)
            .create(ProviderInfo().also { it.authority = ContactsContract.AUTHORITY })
    }

    @Test
    fun numbersBecomeE164ForTheSimCountryOrStayAsTheyAre() {
        assertEquals("+84900000123", numbers.normalize("0900000123", 1))
        assertEquals("+84900000123", numbers.normalize("+84 90 000 0123", null))
        assertEquals("VIETTEL", numbers.normalize("VIETTEL", 1))
        assertEquals("", numbers.normalize("", 1))
    }

    @Test
    fun namesComeFromPhoneLookupOnceAndOnlyWithTheContactsPermission() {
        assertEquals("Nguyễn Văn A", numbers.name("+84900000123"))
        assertEquals("Nguyễn Văn A", numbers.name("+84900000123"))
        assertNull(numbers.name("+84911111111"))
        assertNull(numbers.name("+84911111111"))
        assertEquals("the cache answers the repeats", 2, lookups)

        numbers.forgetNames()
        numbers.name("+84900000123")
        assertEquals(3, lookups)

        contacts = false
        assertNull(numbers.name("+84900000123"))
        assertEquals(3, lookups)
    }

    /** `PhoneLookup` of the contacts provider with one contact. */
    class Contacts : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            lookups++
            val cursor = MatrixCursor(arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME))
            if (uri.lastPathSegment == "+84900000123") cursor.addRow(arrayOf("Nguyễn Văn A"))
            return cursor
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        var lookups = 0
    }
}
