package com.r3.corda.lib.obligation

import net.corda.core.contracts.ContractState
import net.corda.core.transactions.LedgerTransaction

/** Get single input/output from ledger transaction. */
inline fun <reified T : ContractState> LedgerTransaction.singleInput() = inputsOfType<T>().single()
inline fun <reified T : ContractState> LedgerTransaction.singleOutput() = outputsOfType<T>().single()