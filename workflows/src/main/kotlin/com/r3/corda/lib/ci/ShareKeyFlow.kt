package net.corda.confidential.identities


import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.ci.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AnonymousParty
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.toBase58String
import net.corda.core.utilities.unwrap
import java.util.*

/**
 * This flow is the inverse of [RequestKeyFlow] in that the initiating node generates the signed [SignedOwnershipClaim] and
 * shares it with the counter-party node who must verify the signature before registering a mapping between the new
 * [PublicKey] and the party that generated it.
 */
class ShareKeyFlow(
        private val session: FlowSession,
        private val uuid: UUID) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val nonce = OwnershipClaim(SecureHash.randomSHA256().bytes)
        val signedOwnershipClaim = createSignedOwnershipClaimFromUUID(serviceHub, nonce, uuid)
        session.send(signedOwnershipClaim)
    }
}

class ShareKeyFlowHandler(private val otherSession: FlowSession) : FlowLogic<SerializedSignedOwnershipClaim<SignedOwnershipClaim>>() {

    companion object {
        object VERIFYING_SIGNATURE : ProgressTracker.Step("Verifying counterparty's signature")
        object SIGNATURE_VERIFIED : ProgressTracker.Step("Signature is correct")

        @JvmStatic
        fun tracker(): ProgressTracker = ProgressTracker(VERIFYING_SIGNATURE, SIGNATURE_VERIFIED)
    }

    override val progressTracker = tracker()

    @Suspendable
    @Throws(FlowException::class)
    override fun call(): SerializedSignedOwnershipClaim<SignedOwnershipClaim> {
        val signedOwnershipClaim = otherSession.receive<SerializedSignedOwnershipClaim<SignedOwnershipClaim>>().unwrap { it }
        // Ensure the counter party was the one that generated the key
        require(otherSession.counterparty.owningKey == signedOwnershipClaim.hostNodeSig.by) {
            "Expected a signature by ${otherSession.counterparty.owningKey.toBase58String()}, but received by ${signedOwnershipClaim.hostNodeSig.by.toBase58String()}}"
        }
        progressTracker.currentStep = VERIFYING_SIGNATURE
        validateHostNodeAndPublicKeySignatures(signedOwnershipClaim)
        progressTracker.currentStep = SIGNATURE_VERIFIED

        // Use the networkMapCache to lookup the legal identity of the node that signed the ownership claim
        require(otherSession.counterparty == serviceHub.networkMapCache.getNodesByLegalIdentityKey(signedOwnershipClaim.hostNodeSig.by).single().legalIdentities.single())

        val party = serviceHub.identityService.wellKnownPartyFromAnonymous(AnonymousParty(signedOwnershipClaim.hostNodeSig.by))
                ?: throw FlowException("Could not resolve party for key ${signedOwnershipClaim.hostNodeSig.by}")
        val isRegistered = serviceHub.identityService.registerKeyToParty(signedOwnershipClaim.publicKeySig.by, party)
        if (!isRegistered) {
            throw FlowException("Could not generate a new key for $party as the key is already registered or registered to a different party.")
        }
        return signedOwnershipClaim
    }
}