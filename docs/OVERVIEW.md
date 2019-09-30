## Introduction

### What are Confidential Identities?

Confidential identities in Corda provide a way to restrict the knowledge of identities within a transaction to a need-to-know basis. In a normal transaction, the participants are `Party` objects which contain the `CordaX500Name` and the identity owning `PublicKey`. Confidential identities are modelled using `AnonymousParty` objects which only contain the owning `PublicKey`. 

This mechanism allows a node to carry out transactions in which the participants within it do not want to be known to the network. Only the parties involved in the transaction will be able to resolve the confidential owning key. This CorDapp provides two flows; one for generating confidential identities - `RequestKeyFlow`, and one for sharing them with another party `SyncKeyMappingFlow`.

This version of confidential identities differs from the old version in that we do not store the X.509 certificates for each confidential identity. This greatly reduces the storage overhead for systems that require large quantities of confidential identities. By decoupling confidential identities from the certificates, this enables deniability to the  identities. The issuer of the confidential identity can choose not to provide proof that they created it if they don't want to share that information. 

### Generation of Confidential Identities

#### RequestKeyFlow

This process is provided entirely by calling `RequestKey` as an inline subflow. The various steps of the security protocol are detailed below.

* Alice requests a new confidential identity from Bob

   `aliceNode.subFlow(RequestKey(bob))`
* Bob runs the counter-flow on his side 
  `bobNode.subFlow(ProvideKeyFlow(aliceSession))`
* Alice generates a new random `SHA256` value and sends this to Bob along with a request to generate a new key pair
* Bob's `KeyManagementService` generates a new key pair and stores it 
    
 `keyManagementService.freshKey()`

* Bob registers a mapping in his `IdentityService` between `newKeyForBob` and himself
* Bob generates his own random `SHA256` value and concatenates it with the one alice sent
* Bob signs over the concatenated value using the new `PublicKey`
* Bob then sends the new `PublicKey`, the signed concatenated `SHA256` value and his own `SHA256` value back to Alice
* Alice then verfies two things:
  * The `PublicKey` matches that of the one used to sign over the concatenated `SHA256` values
  * That the decrypted concatenated value matches the same value as when she concatenates her `SHA256` and the one Bob sent 

* If the above criteria is fulfilled, Alice can be assured that the key came from Bob and will store a mapping between the new `PublicKey` and Bob in her `IdentityService`. The optional `UUID` parameter is available for use with accounts when you need to store a mapping between the `PublicKey` and `externalId`.

 ` serviceHub.identityService.registerKey(newKeyForBob: PublicKey, counterParty: Party, externalId: UUID? = null)`

Once the flow has finished, both parties will both be able to lookup the well known party associated with the newly generated `PublicKey`. If a third party, not involved in the transaction attempts to resolve that `AnonymousParty`, they will not be able to. Thus, anonymising the identity from parties not present in the transaciton. 

### Sharing Confidential Identities

#### SyncKeyMappingFlow

If a party wishes to share a confidential identity with another party who does not yet know about them, this can be done by calling `SyncKeyMappingFlow` as an inline subflow. The flow has two constructors that allow a node two ways of sharing confidential identities:
1. Provide a `WireTransaction` whereby the participants unknown to the counter-party will be extracted and their identities will be resolved and stored by the counter-party
2. Provide a list of `AnonymousParty`'s that you wish to share with the counter-party

In the first instance, the flow protocol is as follows:

* Alice provides a transaction with participants that Charlie does not know about 

`aliceNode.subFlow(SyncKeyMappingFlow(charlieSession, wireTransaction))`

* Alice extracts the confidential identites from the list of participants by filtering those unknown to the `NetworkMapCache`
* Alice sends the list of confidential identities to Charlie 
* Charlie attempts to lookup the `Party` using the owning `PublicKey`

`serviceHub.identityService.wellKnownPartyFromAnonymous(newKeyForBob)`

* Any parties that Charlie cannot resolve are sent back to Alice 
* Alice looks up the parties on her `IdentityService` and sends the owning `PublicKey` and `Party` object back to charlie 
* Charlie then initiates a `VerifyAndAddKey` flow with each of those parties
* The security protocol is exactly the same as in `RequestKeyFlow` just without the generation of a new key


Once the flow has completed, Charlie will then have a mapping between the owning `PublicKey` of the confidential identity and the `Party` associated with it in his `IdentityService`. The other constructor that takes a `List` of `AnonymousParty`'s can be used when there is a state on the ledger containing a confidential identity but no transaction present. 

 ### Using Confidential Identities with Accounts
 
This new version of confidential identities was written with integration with accounts in mind. The new API on `KeyManagementService` allows us to supply an account identifier, classified by a `UUID`, as a parameter. 

`keyManagementService.freshKey(uuid)`

This API persists a mapping between the new `PublicKey` and the account identitifier in the `PublicKeyHashToExternalId`. There's are two new API's on `IdentityService` for use with accounts:
1. `externalIdForPublicKey(publicKey: PublicKey) : UUID?`
2. `publicKeysForExternalId(externalId: UUID): Iterable<PublicKey>` 

These methods provide a mechanism to lookup an account identifier associated with a confidential identity or lookup the confidential `PublicKey`'s associated with a given account identifier.


### Example Usage 

Consider a simple `IOUFlow` between two parties in which we would want to utilise confidentital identities. It has one output state:
`IOUState(value: Int, lender: AbstractParty, borrower: AbstractParty)`
The flow and counter-flow would be as follows:

```
@InitiatingFlow
@StartableByRPC
class IOUFlow(val iouValue: Int, val otherParty: Party) : FlowLogic<Unit>() {
    /** The progress tracker provides checkpoints indicating the progress of the flow to observers. */
    override val progressTracker = ProgressTracker()
    
     @Suspendable
    override fun call() {
        // We retrieve the notary identity from the network map.
        val notary = serviceHub.networkMapCache.notaryIdentities[0]

        // We create a confidential identity for ourself acting as the lender
        val anonymousLender = AnonymousParty(serviceHub.keyManagementService.freshKey())
        
        // Register the key in our identity service
        serviceHub.identityService.registerKey(anonymousMe.owningKey, ourIdentity)
        
        // Creating a session with the other party.
        val otherPartySession = initiateFlow(otherParty)
        
        // Create a confidential identity for the counter-party acting as the borrower
        // Call counter-flow on the other side
        val anonymousBorrower = subFlow(RequestKeyFlow(otherPartySession)) 
        
        // We create the transaction components.
        val outputState = IOUState(iouValue, anonymousLender, anonymousBorrower)
        val command = Command(IOUContract.Create(), listOf(anonymousLender.owningKey, anonymousBorrower.owningKey))
        
        val txBuilder = TransactionBuilder(notary = notary)
                .addOutputState(outputState, IOUContract.ID)
                .addCommand(command)

        // Verifying the transaction.
        txBuilder.verify(serviceHub)

        // Signing the transaction.
        val signedTx = serviceHub.signInitialTransaction(txBuilder)

        // Obtaining the counterparty's signature.
        val fullySignedTx = subFlow(CollectSignaturesFlow(signedTx, listOf(otherPartySession), CollectSignaturesFlow.tracker()))

        // Finalising the transaction.
        subFlow(FinalityFlow(fullySignedTx))
    }
}
```

```

@InitiatedBy(IOUFlow::class)
class IOUFlowResponder(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subflow(ProvideKeyFlow(otherPartySession))
        val signTransactionFlow = object : SignTransactionFlow(otherPartySession, SignTransactionFlow.tracker()) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction." using (output is IOUState)
                val iou = output as IOUState
                "The IOU's value can't be too high." using (iou.value < 100)
            }
        }

        subFlow(signTransactionFlow)
    }
}
```
