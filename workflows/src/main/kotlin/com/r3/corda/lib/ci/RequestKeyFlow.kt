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
import java.security.SignatureException
import java.util.*

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a [PublicKey] and a [Party]. It can generate a new key
 * pair for a given [UUID] and register the new key mapping, or a known [PublicKey] can be supplied to the flow which will register
 * a mapping between this key and the requesting party.
 *
 * The generation of the signed [SignedOwnershipClaim] is delegated to the counter-party that sends the signed mapping back to
 * the requesting node. The requesting node verifies the signature of the signed mapping matches that of the counter-party
 * before registering the mapping in the [net.corda.core.node.services.IdentityService].
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
        val challengeResponseId = SecureHash.randomSHA256()
        val requestKeyForAccount = if (key == null) RequestKeyForAccount(challengeResponseId, uuid) else RequestKeyForAccount(challengeResponseId, key)
        val receivedKeyForAccount = session.sendAndReceive<SignedKeyForAccount>(requestKeyForAccount).unwrap { it }

        progressTracker.currentStep = VERIFYING_KEY
        try {
            receivedKeyForAccount.signedChallengeResponse.sig.verify(receivedKeyForAccount.signedChallengeResponse.raw.hash.bytes)
        } catch (ex: SignatureException) {
            throw SignatureException("The signature on the object does not match that of the expected public key signature", ex)
        }
        progressTracker.currentStep = KEY_VERIFIED

        progressTracker.currentStep = VERIFYING_CHALLENGE_RESPONSE
        val receivedChallengeResponseId = receivedKeyForAccount.signedChallengeResponse.raw.deserialize()
        require(challengeResponseId == receivedChallengeResponseId)
        progressTracker.currentStep = CHALLENGE_RESPONSE_VERIFIED

        // Flow sessions can only be opened with parties in the networkMapCache so we can be assured this is a valid party
        val counterParty = session.counterparty
        val newKey = receivedKeyForAccount.publicKey

        try {
            serviceHub.identityService.registerKeyToParty(newKey, counterParty)
        } catch (e: Exception) {
            throw FlowException("Could not register a new key for party: $counterParty as the provided public key is already registered " +
                    "or registered to a different party.")
        }
        return receivedKeyForAccount
    }
}

class RequestKeyFlowHandler(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val request = otherSession.receive<RequestKeyForAccount>().unwrap {
            check((it.uuid != null) xor (it.knownKey != null)) {
                "CreateKeyForAccount request should porivde either uuid or knownKey"
            }
            it
        }
        if (request.uuid != null) {
            otherSession.send(createSignedOwnershipClaimFromUUID(serviceHub, request.challengeResponseId, request.uuid!!))
        } else if (request.knownKey != null) {
            otherSession.send(createSignedOwnershipClaimFromKnownKey(serviceHub, request.challengeResponseId, request.knownKey))
        }
    }
}
