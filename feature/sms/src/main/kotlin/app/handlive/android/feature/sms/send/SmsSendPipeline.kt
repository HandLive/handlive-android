package app.handlive.android.feature.sms.send

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.id.UuidBytes
import app.handlive.android.core.protocol.sms.SmsSendRequest
import app.handlive.android.core.protocol.sms.SmsSendStatus
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.module.SmsAccess
import app.handlive.android.feature.sms.module.SmsError
import app.handlive.android.feature.sms.provider.AddressNormalizer
import kotlinx.serialization.json.JsonObject

/** The answer to one `sms/send` (SMS-04 API 1). */
sealed interface SendOutcome {
    /** `ack {accepted: true, parts}`; [duplicate] = the `local_id` was already accepted (logic 4): nothing is sent. */
    class Accepted(
        val entry: SendEntry,
        val duplicate: Boolean,
    ) : SendOutcome

    class Refused(
        val error: SmsError,
    ) : SendOutcome
}

/**
 * Sending for a client (SMS-04 steps 6–9): the checks of API 1 in the order of its error table, de-duplication by
 * `local_id`, SIM selection (logic 3, [SimSelection]), then the radio and the per-part results that drive
 * `sms/status`. The `ack` goes out before the radio sends (logic 1). Never logs the text or the recipient.
 */
class SmsSendPipeline(
    private val access: SmsAccess,
    private val sims: SimChoices,
    private val numbers: AddressNormalizer,
    private val radio: SmsRadio,
    /** The messages sent for clients, by `local_id` (SMS-04 step 5). */
    val registry: SendRegistry,
    private val clock: () -> Long,
) {
    /** Checks and records [data] from [pairId]; the caller sends the `ack`, then calls [dispatch] when accepted. */
    fun accept(
        pairId: String,
        data: JsonObject,
    ): SendOutcome {
        val request = decode(data)
        val existing = request?.let { registry.find(it.localId) }
        val refusal = refusal() ?: requestError(request, existing, pairId)
        return when {
            refusal != null -> SendOutcome.Refused(refusal)

            // Logic 4: a `local_id` already accepted is not sent again.
            existing != null -> SendOutcome.Accepted(existing, duplicate = true)

            else -> admit(pairId, checkNotNull(request))
        }
    }

    /**
     * Step 8: split and send through the chosen SIM, then `sending`; an exception from the call is `failed` with
     * `SMS_GENERIC_FAILURE` (API 3 logic 2). Returns the status to broadcast.
     */
    fun dispatch(entry: SendEntry): SmsStatusData? {
        val message = entry.message
        val sent =
            runCatching {
                radio.send(message.subId, message.address, radio.divide(message.subId, message.body), entry.localId)
            }
        val moved =
            if (sent.isSuccess) {
                entry.move(SmsSendStatus.SENDING, clock())
            } else {
                entry.move(SmsSendStatus.FAILED, clock(), ErrorCode.SMS_GENERIC_FAILURE.name)
            }
        return entry.statusData.takeIf { moved }
    }

    /** The "sent" result of part [index] (API 3): the status to broadcast when it moves, else `null`. */
    fun onSent(
        localId: String,
        index: Int,
        resultCode: Int,
    ): SmsStatusData? {
        val entry = registry.find(localId) ?: return null
        val error = SentResult.errorOf(resultCode)
        val moved =
            if (error == null) {
                entry.partSent(index) && entry.move(SmsSendStatus.SENT, clock())
            } else {
                // The first part that fails decides; later results for the same message are ignored (API 2 logic 1).
                entry.move(SmsSendStatus.FAILED, clock(), error.name)
            }
        return entry.statusData.takeIf { moved }
    }

    /** A delivery report of part [index]: `delivered` once every part has a successful one (API 3 logic 5). */
    fun onDelivered(
        localId: String,
        index: Int,
        report: DeliveryReport,
    ): SmsStatusData? {
        val entry = registry.find(localId)?.takeIf { report == DeliveryReport.DELIVERED }
        val moved = entry != null && entry.partDelivered(index) && entry.move(SmsSendStatus.DELIVERED, clock())
        return entry?.statusData?.takeIf { moved }
    }

    /** E2, then E3 of SMS-04. */
    private fun refusal(): SmsError? =
        when {
            !access.enabled() -> SmsError.featureDisabled()
            !access.granted(AndroidPermissions.SEND_SMS) -> SmsError.permissionMissing(AndroidPermissions.SEND_SMS)
            else -> null
        }

    /** `BAD_REQUEST`, then `PAYLOAD_TOO_LARGE` (E5); a retry of an accepted `local_id` passes. */
    private fun requestError(
        request: SmsSendRequest?,
        existing: SendEntry?,
        pairId: String,
    ): SmsError? =
        when {
            request == null || !request.valid() -> SmsError.badRequest("bad sms/send")
            existing != null && existing.pairId != pairId -> SmsError.badRequest("local_id already used")
            existing != null -> null
            request.body.codePointCount(0, request.body.length) > SmsConstants.BODY_MAX -> SmsError.tooLarge()
            else -> null
        }

    /** `SMS_INVALID_ADDRESS` (E4), then `SMS_SIM_UNAVAILABLE` (E6); otherwise the message joins the registry. */
    private fun admit(
        pairId: String,
        request: SmsSendRequest,
    ): SendOutcome {
        val sim = SimSelection.choose(sims, request.subId)
        val address = numbers.recipient(request.addresses.single().trim(), sims.countryIso(sim.subId))
        return when {
            address == null -> {
                SendOutcome.Refused(SmsError.invalidAddress())
            }

            !sim.available -> {
                SendOutcome.Refused(SmsError.simUnavailable(sim.valid))
            }

            else -> {
                val parts = radio.divide(sim.subId, request.body).size.coerceAtLeast(1)
                val entry =
                    SendEntry(request.localId, pairId, OutgoingSms(address, request.body, sim.subId, parts), clock())
                registry.add(entry)
                SendOutcome.Accepted(entry, duplicate = false)
            }
        }
    }
}

private fun decode(data: JsonObject): SmsSendRequest? =
    runCatching { ProtocolJson.decodeFromJsonElement(SmsSendRequest.serializer(), data) }.getOrNull()

/** `BAD_REQUEST`: a `local_id` that is not a uuid, not exactly one address, an empty text. */
private fun SmsSendRequest.valid(): Boolean =
    UuidBytes.isCanonical(localId) && addresses.size == 1 && addresses.single().isNotBlank() && body.isNotBlank()
