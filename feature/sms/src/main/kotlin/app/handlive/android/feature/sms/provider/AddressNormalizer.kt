package app.handlive.android.feature.sms.provider

/** Phone number normalization of group 5 (0.3 `e164`), with the country of a SIM as the region. */
interface AddressNormalizer {
    /**
     * `thread.addresses` and `message.address` (5.1.5): E.164 when [address] is a valid number, otherwise the string
     * as it is (short codes, sender names such as `VIETTEL`).
     */
    fun e164OrSelf(
        address: String,
        countryIso: String?,
    ): String

    /**
     * SMS-04 API 1 logic 2: a valid number → E.164; a string of 3–8 digits → kept as it is (short code); anything
     * else → `null` (`SMS_INVALID_ADDRESS`).
     */
    fun recipient(
        address: String,
        countryIso: String?,
    ): String?
}
