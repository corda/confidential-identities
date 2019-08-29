package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.KeyManagementService
import net.corda.core.transactions.WireTransaction
import java.security.PublicKey
import java.util.*

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a [PublicKey] and a [Party]. It generate's a new key
 * pair for a given [UUID] and register's the new key mapping.
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original [ChallengeResponse] with its own
 * [ChallengeResponse] and signs over the concatenated hash before sending this value and the [PublicKey] and sends it back to the requesting node.
 * The requesting node verifies the signature on the [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received
 * from the counter-party.
 */
@StartableByRPC
@InitiatingFlow
class RequestKeyForAccount(private val otherParty: Party, private val uuid: UUID) : FlowLogic<AnonymousParty>() {

    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty), uuid))
    }
}

/**
 * Responder flow to [RequestKeyForAccount].
 */
@InitiatedBy(RequestKeyForAccount::class)
class RequestKeyForAccountResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a known [PublicKey] and a [Party].
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original [ChallengeResponse] with its own
 * [ChallengeResponse] and signs over the concatenated hash before sending this value and the [PublicKey] and sends it back to the requesting node.
 * The requesting node verifies the signature on the [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received
 * from the counter-party.
 */
@StartableByRPC
@InitiatingFlow
class VerifyAndAddKey(private val otherParty: Party, private val key: PublicKey) : FlowLogic<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty), key))
    }
}

/**
 * Responder flow to [VerifyAndAddKey].
 */
@InitiatedBy(VerifyAndAddKey::class)
class VerifyAndAddKeyResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a [PublicKey] and a [Party]. The counter-party will generate
 * a fresh [PublicKey] using the [KeyManagementService].
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original [ChallengeResponse] with its own
 * [ChallengeResponse] and signs over the concatenated hash before sending this value and the [PublicKey] and sends it back to the requesting node.
 * The requesting node verifies the signature on the [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received
 * from the counter-party.
 */
@StartableByRPC
@InitiatingFlow
class RequestKey(private val otherParty: Party) : FlowLogic<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty)))
    }
}

/**
 * Responder flow to [RequestKey].
 */
@InitiatedBy(RequestKey::class)
class RequestKeyResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow allows a node to share the [PublicKey] to [Party] mapping data of unknown parties present in a given
 * transaction. Alternatively, the initiating party can provide a list of [AbstractParty] they wish to synchronise the
 * [PublicKey] to [Party] mappings. The initiating sends a list of confidential identities to the counter-party who attempts to resolve
 * them. Parties that cannot be resolved are returned to the initiating node.
 *
 * The counter-party will request a new key mapping for each of the unresolved identities by calling [RequestKeyFlow] as
 * an inline flow.
 */
@InitiatingFlow
@StartableByRPC
class SyncKeyMappingInitiator
private constructor(
        private val otherParty: Party,
        private val tx: WireTransaction?,
        private val identitiesToSync: List<AbstractParty>?) : FlowLogic<Unit>() {
    constructor(otherParty: Party, tx: WireTransaction) : this(otherParty, tx, null)
    constructor(otherParty: Party, identitiesToSync: List<AbstractParty>) : this(otherParty, null, identitiesToSync)

    @Suspendable
    override fun call() {
        if (tx != null) {
            subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), tx))
        } else {
            subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), identitiesToSync
                    ?: throw IllegalArgumentException("A list of anonymous parties or a valid tx id must be provided to this flow.")))
        }
    }
}

/**
 * Responder flow to [SyncKeyMappingInitiator].
 */
@InitiatedBy(SyncKeyMappingInitiator::class)
class SyncKeyMappingResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlowHandler(otherSession))
    }
}
