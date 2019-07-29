package net.corda.confidential.identities


import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.ci.RequestKeyFlow
import com.r3.corda.lib.ci.SignedKeyForAccount
import com.r3.corda.lib.ci.createSignedOwnershipClaimFromUUID
import com.r3.corda.lib.ci.verifySignedChallengeResponseSignature
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.util.*

/**
 * This flow is the inverse of [RequestKeyFlow] in that the initiating node generates the signed [SignedKeyForAccount] and
 * shares it with the counter-party node who must verify the signature and the [ChallengeResponse] before registering a mapping between the new
 * [PublicKey] and the counter-party.
 */
class ShareKeyFlow(
        private val session: FlowSession,
        private val uuid: UUID) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val challengeResponseId = SecureHash.randomSHA256()
        val signedKeyForAccount = createSignedOwnershipClaimFromUUID(serviceHub, challengeResponseId, uuid)
        session.send(signedKeyForAccount)
    }
}

class ShareKeyFlowHandler(private val otherSession: FlowSession) : FlowLogic<SignedKeyForAccount>() {

    companion object {
        object VERIFYING_SIGNATURE : ProgressTracker.Step("Verifying counterparty's signature")
        object SIGNATURE_VERIFIED : ProgressTracker.Step("Signature is correct")

        @JvmStatic
        fun tracker(): ProgressTracker = ProgressTracker(VERIFYING_SIGNATURE, SIGNATURE_VERIFIED)
    }

    override val progressTracker = tracker()

    @Suspendable
    @Throws(FlowException::class)
    override fun call(): SignedKeyForAccount {
        val signedKeyForAccount = otherSession.receive<SignedKeyForAccount>().unwrap { it }

        progressTracker.currentStep = VERIFYING_SIGNATURE
        verifySignedChallengeResponseSignature(signedKeyForAccount)
        progressTracker.currentStep = SIGNATURE_VERIFIED

        // Flow sessions can only be opened with parties in the networkMapCache so we can be assured this is a valid party
        val counterParty = otherSession.counterparty
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