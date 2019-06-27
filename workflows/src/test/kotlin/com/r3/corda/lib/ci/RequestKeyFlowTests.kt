package com.r3.corda.lib.ci

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.money.USD
import com.r3.corda.lib.tokens.workflows.flows.shell.IssueTokens
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.CHARLIE_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestKeyFlowTests {

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

    }

    @After
    fun after() {
        mockNet.stopNodes()
    }

    @Test
    fun `request new key from another party`() {
        // Alice requests that bob generates a new key for an account
        val keyForBob = aliceNode.services.startFlow(RequestKeyInitiator(bob, UUID.randomUUID())).resultFuture

        val bobKey = keyForBob.getOrThrow().raw.deserialize().key

        // Bob has the newly generated key as well as the owning key
        val bobKeys = bobNode.services.keyManagementService.keys
        val aliceKeys = aliceNode.services.keyManagementService.keys
        assertThat(bobKeys).hasSize(2)
        assertThat(aliceKeys).hasSize(1)

        assertThat(bobNode.services.keyManagementService.keys).contains(bobKey)

        val resolvedBobParty = aliceNode.services.identityService.wellKnownPartyFromAnonymous(AnonymousParty(bobKey))
        assertThat(resolvedBobParty).isEqualTo(bob)
    }

    @Test
    fun `verify a known key with another party`() {
        // Charlie issues then pays some cash to a new confidential identity
        val anonymousParty = charlieNode.services.startFlow(ConfidentialIdentityWrapper(alice)).resultFuture.getOrThrow()
        val issueFlow = charlieNode.services.startFlow(
                IssueTokens(1000 of USD issuedBy charlie heldBy AnonymousParty(anonymousParty.owningKey))
        )
        val issueTx = issueFlow.resultFuture.getOrThrow()
        val confidentialIdentity = issueTx.tx.outputs.map { it.data }.filterIsInstance<FungibleToken<TokenType>>().single().holder
        // Verify Bob cannot resolve the CI before we create a signed mapping of the CI key
        assertNull(bobNode.database.transaction { bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity) })
        bobNode.services.startFlow(RequestKeyFlowWrapper(charlie, confidentialIdentity.owningKey)).resultFuture.getOrThrow()

        val expected = charlieNode.database.transaction {
            charlieNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        val actual = bobNode.database.transaction {
            bobNode.services.identityService.wellKnownPartyFromAnonymous(confidentialIdentity)
        }
        assertEquals(expected, actual)
    }
}