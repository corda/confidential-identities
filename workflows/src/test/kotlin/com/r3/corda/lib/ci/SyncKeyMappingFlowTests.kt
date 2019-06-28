package net.corda.confidential.identities

import com.r3.corda.lib.ci.ConfidentialIdentityInitiator
import com.r3.corda.lib.ci.RequestKeyResponder
import com.r3.corda.lib.ci.SyncKeyMappingInitiator
import com.r3.corda.lib.ci.SyncKeyMappingResponder
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.workflows.flows.shell.IssueTokens
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncKeyMappingFlowTests {
    private lateinit var mockNet: InternalMockNetwork
    private lateinit var aliceNode: TestStartedNode
    private lateinit var bobNode: TestStartedNode
    private lateinit var charlieNode: TestStartedNode
    private lateinit var alice: Party
    private lateinit var bob: Party
    private lateinit var charlie: Party
    private lateinit var notary: Party

    @Before
    fun before() {
        mockNet = InternalMockNetwork(
                cordappsForAllNodes = FINANCE_CORDAPPS,
                cordappPackages = listOf(
                        "com.r3.corda.lib.tokens.contracts",
                        "com.r3.corda.lib.tokens.workflows",
                        "com.r3.corda.lib.ci"),
                networkSendManuallyPumped = false,
                threadPerNode = true)


        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        charlieNode = mockNet.createPartyNode(CHARLIE_NAME)
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        charlie = charlieNode.info.singleIdentity()
        notary = mockNet.defaultNotaryIdentity

        mockNet.startNodes()
        aliceNode.registerInitiatedFlow(SyncKeyMappingResponder::class.java)
        bobNode.registerInitiatedFlow(SyncKeyMappingResponder::class.java)
        charlieNode.registerInitiatedFlow(RequestKeyResponder::class.java)
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `sync the key mapping between two parties in a transaction`() {
        // Alice issues then pays some cash to a new confidential identity that Bob doesn't know about
        val anonymousParty = aliceNode.services.startFlow(ConfidentialIdentityInitiator(charlie)).resultFuture.getOrThrow()

        val issueFlow = aliceNode.services.startFlow(
                IssueTokens(1000 of USD issuedBy alice heldBy AnonymousParty(anonymousParty.owningKey))
        )
        val issueTx = issueFlow.resultFuture.getOrThrow()
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<FungibleToken<TokenType>>().single().holder

        assertNull(bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })

        // Run the flow to sync up the identities
        aliceNode.services.startFlow(SyncKeyMappingInitiator(bob, issueTx.tx)).let {
            mockNet.waitQuiescent()
            it.resultFuture.getOrThrow()
        }

        val expected = aliceNode.database.transaction {
            aliceNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        val actual = bobNode.database.transaction {
            bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        assertEquals(expected, actual)
    }
}


