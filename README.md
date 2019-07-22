<p align="center">
    <img src="https://www.corda.net/wp-content/uploads/2016/11/fg005_corda_b.png" alt="Corda" width="500">
</p>

# Confidential Identities CorDapp

## Reminder

This project is open source under an Apache 2.0 licence. That means you
can submit PRs to fix bugs and add new features if they are not currently
available.

## How does the new Confidential Identities CorDapp differ from the existing confidential identities?

In this version of confidential identities we do not store X.509 certificates for confidential identities. This 
significantly reduces the memory overhead for node operators that may want to host multiple _accounts_ and anonymise 
transaction participation. 

Instead, we still store a mapping between a newly generated `PublicKey` for a confidential identity and a `CordaX500Name`. 

This CorDapp contains three flows which can be used to generate, share and synchronise data required for use of confidential
identities in an application. 

### Adding confidential identities dependencies to an existing CorDapp

First, add a variable for the confidential identities version you wish to use:

    buildscript {
        ext {
            confidential_id_release_version = '1.0-SNAPSHOT'
            confidential_id_release_group = 'com.r3.corda.lib.ci'
        }
    }

Second, you must add the confidential identities development artifactory repository to the
list of repositories for your project:

    repositories {
        maven { url 'http://ci-artifactory.corda.r3cev.com/artifactory/corda-lib-dev' }
    }

Now, you can add the confidential identities dependencies to the `dependencies` block
in each module of your CorDapp. In your workflow `build.gradle` add:

    cordaCompile "$confidential_id_release_group:workflows:$confidential_id_release_version"

If you want to use the `deployNodes` task, you will need to add the
following dependency to your root `build.gradle` file:

    cordapp "$confidential_id_release_group:ci-workflows:$confidential_id_release_version"

These should also be added to the `deployNodes` task with the following syntax:

    nodeDefaults {
        projectCordapp {
            deploy = false
        }
        cordapp("$confidential_id_release_group:ci-workflows:$confidential_id_release_version")
    }

## Flows 

### RequestKeyFlow

This flow can be used to register a mapping in the `IdentityService` between the `PublicKey` and the `CordaX500Name` for 
a confidential identity. This mapping is required for the node operator to be able to resolve the confidential identity. 
A new key pair can be generated for an external ID given by a `UUID`. Alternatively, a node can provide the owning key for 
confidential identity that will register the mapping between this key and the `CordaX500Name`.

After node requests a new confidential key, the counterparty sends back the `SignedData<OwnershipClaim>` object that 
wraps the confidential identity `PublicKey` and encrypts the `CordaX500Name`.

### ShareKeyFlow

The inverse of `RequestKeyFlow` where the initiating node generates the `SignedData<OwnershipClaim>` and shares this with
the flow counterparty who then registers `PublicKey` to `CordaX500Name` in their `IdentityService`.

### SyncKeyMappingsFlow

This flow should be used when a node wishes to synchronise the `PublicKey` to `CordaX500Name` mapping of a confidential 
identity with another node. This confidential participants of a transaction can be extracted from a given `WireTransaction` 
if this is passed as a parameter to the flow. Alternatively, the node can bypass the need for a transaction and accept a
list of `AnonymousParty` that it wishes to synchronize with another node. 
