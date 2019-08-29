package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.security.PublicKey

/**
 * This flow allows a node to share the [PublicKey] to [Party] mapping data of unknown parties present in a given
 * transaction. Alternatively, the initiating party can provide a list of [AbstractParty] they wish to synchronise the
 * [PublicKey] to [Party] mappings. The initiating sends a list of confidential identities to the counter-party who attempts to resolve
 * them. Parties that cannot be resolved are returned to the initiating node.
 *
 * The counter-party will request a new key mapping for each of the unresolved identities by calling [RequestKeyFlow] as
 * an inline flow.
 */
class SyncKeyMappingFlow
private constructor(
        private val session: FlowSession,
        private val tx: WireTransaction?,
        private val identitiesToSync: List<AbstractParty>?) : FlowLogic<Unit>() {

    /**
     * Synchronize the "confidential identities" present in a transaction with the counterparty specified by the
     * supplied flow session.
     *
     * @param session a flow session for the party to synchronize the confidential identities with
     * @param tx the transaction to extract confidential identities from.
     */
    constructor(session: FlowSession, tx: WireTransaction) : this(session, tx, null)

    /**
     * Synchronize the "confidential identities" present in a list of [AbstractParty]s with the counterparty specified
     * by the supplied flow session.
     *
     * @param session a flow session for the party to synchronize the confidential identities with
     * @param identitiesToSync the confidential identities to synchronize.
     */
    constructor(session: FlowSession, identitiesToSync: List<AbstractParty>) : this(session, null, identitiesToSync)

    companion object {
        object SYNCING_KEY_MAPPINGS : ProgressTracker.Step("Syncing key mappings.")
    }

    override val progressTracker = ProgressTracker(SYNCING_KEY_MAPPINGS)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = SYNCING_KEY_MAPPINGS
        val confidentialIdentities =
                if (tx != null) {
                    extractConfidentialIdentities(tx)
                } else {
                    identitiesToSync ?: throw IllegalArgumentException("A transaction or a list of anonymous parties must be provided to this flow.")
                }

        // Send confidential identities to the counter party and return a list of parties they wish to resolve
        val requestedIdentities = session.sendAndReceive<List<AbstractParty>>(confidentialIdentities).unwrap { req ->
            require(req.all { it in confidentialIdentities }) {
                "${session.counterparty} requested resolution of a confidential identity that is not present in the list of identities initially provided."
            }
            req
        }
        val resolvedIds = requestedIdentities.map { it.owningKey to serviceHub.identityService.wellKnownPartyFromAnonymous(it) }.toMap()
        session.send(resolvedIds)
    }

    private fun extractConfidentialIdentities(tx: WireTransaction): List<AbstractParty> {
        val inputStates: List<ContractState> = (tx.inputs.toSet()).mapNotNull {
            try {
                serviceHub.loadState(it).data
            } catch (e: TransactionResolutionException) {
                logger.warn("WARNING: Could not resolve state with StateRef $it")
                null
            }
        }
        val states: List<ContractState> = inputStates + tx.outputs.map { it.data }
        val identities: Set<AbstractParty> = states.flatMap(ContractState::participants).toSet()

        return identities
                .filter { serviceHub.networkMapCache.getNodesByLegalIdentityKey(it.owningKey).isEmpty() }
                .toList()
    }
}

class SyncKeyMappingFlowHandler(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING_IDENTITIES : ProgressTracker.Step("Receiving confidential identities.")
        object RECEIVING_PARTIES : ProgressTracker.Step("Receiving potential party objects for unknown identities.")
        object NO_PARTIES_RECEIVED : ProgressTracker.Step("None of the requested unknown parties were resolved by the counter party. " +
                "Terminating the flow early.")

        object REQUESTING_PROOF_OF_ID : ProgressTracker.Step("Requesting a signed key to party mapping for the received parties to verify" +
                "the authenticity of the party.")

        object IDENTITIES_SYNCHRONISED : ProgressTracker.Step("Identities have finished synchronising.")
    }

    override val progressTracker: ProgressTracker = ProgressTracker(RECEIVING_IDENTITIES, RECEIVING_PARTIES, NO_PARTIES_RECEIVED, REQUESTING_PROOF_OF_ID, IDENTITIES_SYNCHRONISED)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING_IDENTITIES
        val allConfidentialIds = otherSession.receive<List<AbstractParty>>().unwrap { it }
        val unknownIdentities = allConfidentialIds.filter { serviceHub.identityService.wellKnownPartyFromAnonymous(it) == null }
        otherSession.send(unknownIdentities)
        progressTracker.currentStep = RECEIVING_PARTIES

        val mapConfidentialKeyToParty = otherSession.receive<Map<PublicKey, Party>>().unwrap { it }
        if (mapConfidentialKeyToParty.isEmpty()) {
            progressTracker.currentStep = NO_PARTIES_RECEIVED
        }

        progressTracker.currentStep = REQUESTING_PROOF_OF_ID

        mapConfidentialKeyToParty.forEach {
            subFlow(VerifyAndAddKey(it.value, it.key))
        }
        progressTracker.currentStep = IDENTITIES_SYNCHRONISED
    }
}