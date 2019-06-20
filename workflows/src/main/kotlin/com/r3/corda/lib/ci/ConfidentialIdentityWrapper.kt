package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.workflows.flows.internal.confidential.RequestConfidentialIdentityFlow
import com.r3.corda.lib.tokens.workflows.flows.internal.confidential.RequestConfidentialIdentityFlowHandler
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate

@InitiatingFlow
class ConfidentialIdentityWrapper(private val party: Party) : FlowLogic<PartyAndCertificate>() {
    @Suspendable
    override fun call(): PartyAndCertificate {
        return subFlow(RequestConfidentialIdentityFlow(initiateFlow(party)))
    }
}

@InitiatedBy(ConfidentialIdentityWrapper::class)
class ConfidentialIdentityResponder(private val otherSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(RequestConfidentialIdentityFlowHandler(otherSession))
    }

}