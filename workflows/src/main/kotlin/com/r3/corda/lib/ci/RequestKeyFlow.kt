package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.security.PublicKey
import java.util.*

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a [PublicKey] and a [Party]. It can generate a new key
 * pair for a given [UUID] and register the new key mapping, or a known [PublicKey] can be supplied to the flow which will register
 * a mapping between this key and the requesting party.
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original [ChallengeResponse] with its own
 * [ChallengeResponse] and signs over the concatenated hash before sending this value and the [PublicKey] and sends it back to the requesting node.
 * The requesting node verifies the signature on the [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received
 * from the counter-party.
 */
class RequestKeyFlow
private constructor(
        private val session: FlowSession,
        private val uuid: UUID,
        private val key: PublicKey?) : FlowLogic<SignedKeyForAccount>() {
    constructor(session: FlowSession, uuid: UUID) : this(session, uuid, null)
    constructor(session: FlowSession, key: PublicKey) : this(session, UniqueIdentifier().id, key)

    companion object {
        object REQUESTING_KEY : ProgressTracker.Step("Requesting a public key")
        object VERIFYING_KEY : ProgressTracker.Step("Verifying counterparty's signature")
        object KEY_VERIFIED : ProgressTracker.Step("Signature is correct")
        object VERIFYING_CHALLENGE_RESPONSE : ProgressTracker.Step("Verifying the received SHA-256 matches the original that was sent")
        object CHALLENGE_RESPONSE_VERIFIED : ProgressTracker.Step("SHA-256 is correct")

        @JvmStatic
        fun tracker(): ProgressTracker = ProgressTracker(REQUESTING_KEY, VERIFYING_KEY, KEY_VERIFIED, VERIFYING_CHALLENGE_RESPONSE, CHALLENGE_RESPONSE_VERIFIED)
    }

    override val progressTracker = tracker()

    @Suspendable
    @Throws(FlowException::class)
    override fun call(): SignedKeyForAccount {
        progressTracker.currentStep = REQUESTING_KEY
        val challengeResponseParam = SecureHash.randomSHA256()
        val requestKeyForAccount = if (key == null) RequestKeyForAccount(challengeResponseParam, uuid) else RequestKeyForAccount(challengeResponseParam, key)
        val signedKeyForAccount = session.sendAndReceive<SignedKeyForAccount>(requestKeyForAccount).unwrap { it }

        progressTracker.currentStep = VERIFYING_KEY
        verifySignedChallengeResponseSignature(signedKeyForAccount)
        progressTracker.currentStep = KEY_VERIFIED

        // Ensure the hash of both challenge response parameters matches the received hashed function
        progressTracker.currentStep = VERIFYING_CHALLENGE_RESPONSE
        val additionalParam = signedKeyForAccount.additionalChallengeResponseParam
        val resultOfHashedParameters = challengeResponseParam.hashConcat(additionalParam)
        require(resultOfHashedParameters == signedKeyForAccount.signedChallengeResponse.raw.deserialize())
        progressTracker.currentStep = CHALLENGE_RESPONSE_VERIFIED

        // Flow sessions can only be opened with parties in the networkMapCache so we can be assured this is a valid party
        val counterParty = session.counterparty
        val newKey = signedKeyForAccount.publicKey

        try {
            serviceHub.identityService.registerKeyToParty(newKey, counterParty)
        } catch (e: Exception) {
            throw FlowException("Could not register a new key for party: $counterParty as the provided public key is already registered " +
                    "or registered to a different party.")
        }
        return signedKeyForAccount
    }
}

class ProvideKeyFlow(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val request = otherSession.receive<RequestKeyForAccount>().unwrap {
            check((it.uuid != null) xor (it.knownKey != null)) {
                "RequestKeyForAccount request should provide either a uuid or public key"
            }
            it
        }
        if (request.uuid != null) {
            otherSession.send(createSignedOwnershipClaimFromUUID(serviceHub, request.challengeResponseParam, request.uuid!!))
        } else if (request.knownKey != null) {
            otherSession.send(createSignedOwnershipClaimFromKnownKey(serviceHub, request.challengeResponseParam, request.knownKey))
        }
    }
}
