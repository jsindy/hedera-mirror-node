package com.hedera.mirror.web3.service;

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

import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Named;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.scheduling.annotation.Async;

import com.hedera.mirror.web3.repository.AccountBalanceFileRepository;

@CustomLog
@Named
@RequiredArgsConstructor
public class BalanceReconciliationService {

    private static final String BALANCE_QUERY = "select account_id, balance from account_balance where " +
            "consensus_timestamp = ? and account_id = 98";
    private static final String TRANSFER_QUERY = "select ct.entity_id, ct.amount from crypto_transfer ct " +
            "where ct.consensus_timestamp > ? and ct.consensus_timestamp <= ? and entity_id = 98";

    private final AccountBalanceFileRepository accountBalanceFileRepository;
    private final JdbcOperations jdbcOperations;

    @Async
    @EventListener
    public void reconcile(ApplicationReadyEvent event) {
        Set<Long> badBalanceFiles = new TreeSet<>();
        Stopwatch stopwatch = Stopwatch.createStarted();
        String start = System.getProperty("start", "0");
        long startTimestamp = Long.valueOf(start);

        try {
            var fromTimestampOptional = accountBalanceFileRepository.findTimestampAfter(startTimestamp);
            Map<Long, Long> transferBalances = Map.of();

            while (fromTimestampOptional.isPresent()) {
                long fromTimestamp = fromTimestampOptional.get();

                var toTimestampOptional = accountBalanceFileRepository.findTimestampAfter(fromTimestamp);
                if (toTimestampOptional.isEmpty()) {
                    log.info("Processed all files");
                    return;
                }

                if (transferBalances.isEmpty()) {
                    transferBalances = getAccountBalances(fromTimestamp);
                }

                long toTimestamp = toTimestampOptional.get();

                if (!reconcile(transferBalances, fromTimestamp, toTimestamp)) {
                    badBalanceFiles.add(toTimestamp);
                }

                fromTimestampOptional = toTimestampOptional;
            }

            log.info("No balance files");
        } catch (Exception e) {
            log.error("Unable to finish reconciling", e);
        } finally {
            log.info("Finished in {}. Bad balance files: {}", stopwatch, badBalanceFiles);
        }
    }

    private boolean reconcile(Map<Long, Long> transferBalances, long fromTimestamp, long toTimestamp) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        try {
            Map<Long, Long> accountBalances = getAccountBalances(toTimestamp);
            updateTransferBalances(transferBalances, fromTimestamp, toTimestamp);
            var diff = Maps.difference(accountBalances, transferBalances);

            if (transferBalances.size() == 1) {
                long accountBalance = accountBalances.getOrDefault(98L, 0L);
                long transferBalance = transferBalances.getOrDefault(98L, 0L);
                log.info("Compared range ({}, {}] in {}: accountBalance={}, transferBalance={}, diff={}",
                        fromTimestamp, toTimestamp, stopwatch, accountBalance, transferBalance,
                        accountBalance - transferBalance);
            } else {
                log.info("Compared range ({}, {}] in {}: {}", fromTimestamp, toTimestamp, stopwatch, diff);
            }

            return diff.areEqual();
        } catch (Exception e) {
            log.error("Unable to reconcile", e);
        }
        
        return false;
    }

    private Map<Long, Long> getAccountBalances(long consensusTimestamp) {
        Map<Long, Long> balances = new HashMap<>();

        jdbcOperations.query(BALANCE_QUERY, rs -> {
            long accountId = rs.getLong(1);
            long balance = rs.getLong(2);
            balances.put(accountId, balance);
        }, consensusTimestamp);

        return balances;
    }

    private void updateTransferBalances(Map<Long, Long> balances, long fromTimestamp, long toTimestamp) {
        jdbcOperations.query(TRANSFER_QUERY, rs -> {
            long accountId = rs.getLong(1);
            long amount = rs.getLong(2);
            balances.merge(accountId, amount, (a, b) -> a + b);
        }, fromTimestamp, toTimestamp);
    }
}
