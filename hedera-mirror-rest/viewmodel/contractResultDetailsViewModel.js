/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

'use strict';

const ContractLogResultsViewModel = require('./contractResultLogViewModel');
const ContractResultStateChangeViewModel = require('./contractResultStateChangeViewModel');
const ContractResultViewModel = require('./contractResultViewModel');
const {TransactionResult} = require('../model');
const utils = require('../utils');

/**
 * Contract result details view model
 */
class ContractResultDetailsViewModel extends ContractResultViewModel {
  static _FAIL_PROTO_ID = Number.parseInt(TransactionResult.getSuccessProtoId());
  static _SUCCESS_RESULT = '0x1';
  static _FAIL_RESULT = '0x0';

  /**
   * Constructs contractResultDetails view model
   *
   * @param {ContractResult} contractResult
   * @param {RecordFile} recordFile
   * @param {Transaction} transaction
   * @param {ContractLog[]} contractLogs
   * @param {ContractStateChange[]} contractStateChanges
   */
  constructor(contractResult, recordFile, transaction, contractLogs, contractStateChanges) {
    super(contractResult);
    Object.assign(this, {
      block_hash: utils.addHexPrefix(recordFile.hash),
      block_number: Number(recordFile.index),
      hash: utils.toHexString(transaction.transactionHash, true),
      logs: contractLogs.map((contractLog) => new ContractLogResultsViewModel(contractLog)),
      result: TransactionResult.getName(transaction.result),
      state_changes: contractStateChanges.map(
        (contractStateChange) => new ContractResultStateChangeViewModel(contractStateChange)
      ),
      status:
        transaction.result === ContractResultDetailsViewModel._FAIL_PROTO_ID
          ? ContractResultDetailsViewModel._SUCCESS_RESULT
          : ContractResultDetailsViewModel._FAIL_RESULT,
    });
  }
}

module.exports = ContractResultDetailsViewModel;
