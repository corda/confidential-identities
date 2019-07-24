package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.crypto.newSecureRandom
import net.corda.core.crypto.secureRandomBytes
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.toBase58String
import net.corda.core.utilities.unwrap
import java.math.BigInteger
import java.security.PublicKey
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
class RequestKeyFlow(
        private val session: FlowSession,
        private val uuid: UUID,
        private val key: PublicKey?) : FlowLogic<SerializedSignedOwnershipClaim<SignedOwnershipClaim>>() {
    constructor(session: FlowSession, uuid: UUID) : this(session, uuid, null)
    constructor(session: FlowSession, key: PublicKey) : this(session, UniqueIdentifier().id, key)

    companion object {
        object REQUESTING_KEY : ProgressTracker.Step("Generating a public key")
        object VERIFYING_KEY : ProgressTracker.Step("Verifying counterparty's signature")
        object KEY_VERIFIED : ProgressTracker.Step("Signature is correct")

        @JvmStatic
        fun tracker(): ProgressTracker = ProgressTracker(REQUESTING_KEY, VERIFYING_KEY, KEY_VERIFIED)
    }

    override val progressTracker = tracker()

    @Suspendable
    @Throws(FlowException::class)
    override fun call(): SerializedSignedOwnershipClaim<SignedOwnershipClaim> {
        progressTracker.currentStep = REQUESTING_KEY
        val nonce = OwnershipClaim(SecureHash.randomSHA256().bytes)
        var accountData = CreateKeyForAccount(nonce, uuid)
        if (key != null) {
           accountData = CreateKeyForAccount(nonce, key)
        }
        val signedOwnershipClaim = session.sendAndReceive<SerializedSignedOwnershipClaim<SignedOwnershipClaim>>(accountData).unwrap { it }

        // Ensure the counter party was the one that generated the ownership claim
        require(session.counterparty.owningKey == signedOwnershipClaim.hostNodeSig.by) {
            "Expected a signature by ${session.counterparty.owningKey.toBase58String()}, but received by ${signedOwnershipClaim.hostNodeSig.by.toBase58String()}}"
        }
        progressTracker.currentStep = VERIFYING_KEY
        validateHostNodeAndPublicKeySignatures(signedOwnershipClaim)
        progressTracker.currentStep = KEY_VERIFIED

        // Use the networkMapCache to lookup the legal identity of the node that signed the ownership claim
        require(session.counterparty == serviceHub.networkMapCache.getNodesByLegalIdentityKey(signedOwnershipClaim.hostNodeSig.by).single().legalIdentities.single())

        val party = serviceHub.identityService.wellKnownPartyFromAnonymous(AnonymousParty(signedOwnershipClaim.hostNodeSig.by))
                ?: throw FlowException("Could not resolve party for key ${signedOwnershipClaim.hostNodeSig.by}")
        val newKey = signedOwnershipClaim.raw.deserialize().key
        val isRegistered = serviceHub.identityService.registerKeyToParty(newKey, party)
        if (!isRegistered) {
            throw FlowException("Could not generate a new key for $party as the key is already registered or registered to a different party.")
        }
        return signedOwnershipClaim
    }
}

class RequestKeyFlowHandler(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val request = otherSession.receive<CreateKeyForAccount>().unwrap { it }
        when {
            request.uuid != null -> otherSession.send(createSignedOwnershipClaim(serviceHub, request.nonce, request.uuid!!))
            request.knownKey != null -> otherSession.send(createSignedOwnershipClaimFromKnownKey(serviceHub, request.nonce, request.knownKey))
            else -> FlowException("Unable to generate a signed key mapping from the data provided.")
        }
    }
}
