package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SignedData
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.OwnershipClaim
import net.corda.core.identity.Party
import java.util.*

@InitiatingFlow
class RequestKeyInitiator(private val otherParty: Party, private val uuid: UUID) : FlowLogic<SignedData<OwnershipClaim>>() {
    @Suspendable
    override fun call(): SignedData<OwnershipClaim> {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty), uuid))
    }
}

@InitiatedBy(RequestKeyInitiator::class)
class RequestKeyResponder(private val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(RequestKeyFlowHandler(otherSession))
    }
}