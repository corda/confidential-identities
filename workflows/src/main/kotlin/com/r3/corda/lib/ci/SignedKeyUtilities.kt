package com.r3.corda.lib.ci

import net.corda.core.CordaInternal
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.newSecureRandom
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.hash
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.OpaqueBytes
import java.math.BigInteger
import java.security.PublicKey
import java.security.SignatureException
import java.util.*


@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaim(serviceHub: ServiceHub, uuid: UUID): SignedData<OwnershipClaim> {
    val nodeParty = serviceHub.myInfo.legalIdentities.first()
    val newKey = serviceHub.keyManagementService.freshKey(uuid)
    val nonce = BigInteger(123, newSecureRandom()).toByte()
    val ownershipClaim = OwnershipClaim(OpaqueBytes.of(nonce), newKey)
    val sig = serviceHub.keyManagementService.sign(ownershipClaim.serialize().hash.bytes, nodeParty.owningKey)
    return SignedData(ownershipClaim.serialize(), sig)
}

@CordaInternal
@VisibleForTesting
fun createSignedOwnershipClaimFromKnownKey(serviceHub: ServiceHub, knownKey: PublicKey): SignedData<OwnershipClaim> {
    val nodeParty = serviceHub.myInfo.legalIdentities.first()
    val nonce = BigInteger(123, newSecureRandom()).toByte()
    val ownershipClaim = OwnershipClaim(OpaqueBytes.of(nonce), knownKey)
    val sig = serviceHub.keyManagementService.sign(ownershipClaim.serialize().hash.bytes, nodeParty.owningKey)
    return SignedData(ownershipClaim.serialize(), sig)
}

@CordaInternal
@VisibleForTesting
fun validateSignature(signedOwnershipClaim: SignedData<OwnershipClaim>) {
    try {
        signedOwnershipClaim.sig.verify(signedOwnershipClaim.raw.hash.bytes)
    } catch (ex: SignatureException) {
        throw SignatureException("The signature does not match the expected.", ex)
    }
}

@CordaInternal
@VisibleForTesting
fun getPartyFromSignedOwnershipClaim(serviceHub: ServiceHub, signedOwnershipClaim: SignedData<OwnershipClaim>): Party? {
    return serviceHub.identityService.wellKnownPartyFromAnonymous(AnonymousParty(signedOwnershipClaim.raw.deserialize().key))
}

/**
 * Utility object used to parse data required for generating key mappings between different flow sessions.
 */
@CordaSerializable
class CreateKeyForAccount(private val _uuid: UUID?, val knownKey: PublicKey?) {
    constructor(knownKey: PublicKey) : this(null, knownKey)
    constructor(uuid: UUID) : this(uuid, null)

    val uuid: UUID?
        get() = _uuid
}

/**
 * To be used in conjuction with [SignedData] when using confidential identities. The [PublicKey] represents the owning key of a
 * confidential identity.
 */
@CordaSerializable
data class OwnershipClaim(val nonce: OpaqueBytes, val key: PublicKey)
