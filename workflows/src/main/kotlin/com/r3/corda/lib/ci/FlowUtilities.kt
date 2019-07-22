package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.workflows.internal.flows.confidential.RequestConfidentialIdentityFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.confidential.RequestConfidentialIdentityFlowHandler
import net.corda.core.crypto.SignedData
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.transactions.WireTransaction
import java.security.PublicKey
import java.util.*

@InitiatingFlow
class ConfidentialIdentityInitiator(private val party: Party) : FlowLogic<PartyAndCertificate>() {
    @Suspendable
    override fun call(): PartyAndCertificate {
        return subFlow(RequestConfidentialIdentityFlow(initiateFlow(party)))
    }
}

@InitiatedBy(ConfidentialIdentityInitiator::class)
class ConfidentialIdentityResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(RequestConfidentialIdentityFlowHandler(otherSession))
    }

}

@InitiatingFlow
class RequestKeyInitiator(
        private val otherParty: Party,
        private val uuid: UUID?,
        private val key: PublicKey?) : FlowLogic<SignedData<OwnershipClaim>>() {

    constructor(otherParty: Party, uuid: UUID) : this(otherParty, uuid, null)
    constructor(otherParty: Party, key: PublicKey) : this(otherParty, null, key)

    @Suspendable
    override fun call(): SignedData<OwnershipClaim> {
        return if (uuid != null) {
            subFlow(RequestKeyFlow(initiateFlow(otherParty), uuid))
        } else {
            subFlow(RequestKeyFlow(initiateFlow(otherParty), key ?: throw IllegalArgumentException("You must specify" +
                    "the identifier or a known public key")))
        }
    }
}

@InitiatedBy(RequestKeyInitiator::class)
class RequestKeyResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(RequestKeyFlowHandler(otherSession))
    }
}

@InitiatingFlow
class ShareKeyInitiator(private val otherParty: Party, private val uuid: UUID) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ShareKeyFlow(initiateFlow(otherParty), uuid))
    }
}

@InitiatedBy(ShareKeyInitiator::class)
class ShareKeyResponder(private val otherSession: FlowSession) : FlowLogic<SignedData<OwnershipClaim>>() {
    @Suspendable
    override fun call(): SignedData<OwnershipClaim> {
        return subFlow(ShareKeyFlowHandler(otherSession))
    }
}


@InitiatingFlow
class SyncKeyMappingInitiator(private val otherParty: Party, private val tx: WireTransaction) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), tx))
    }
}

@InitiatedBy(SyncKeyMappingInitiator::class)
class SyncKeyMappingResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlowHandler(otherSession))
    }
}