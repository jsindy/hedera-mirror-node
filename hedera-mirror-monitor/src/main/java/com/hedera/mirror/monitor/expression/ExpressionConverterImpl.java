package com.hedera.mirror.monitor.expression;

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

import com.google.common.collect.Iterables;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Named;

import com.hedera.mirror.monitor.publish.transaction.AdminKeyable;
import com.hedera.mirror.monitor.publish.transaction.TransactionSupplier;
import com.hedera.mirror.monitor.publish.transaction.TransactionType;

import com.hedera.mirror.monitor.publish.transaction.schedule.ScheduleCreateTransactionSupplier;
import com.hedera.mirror.monitor.publish.transaction.token.TokenCreateTransactionSupplier;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import com.hedera.hashgraph.sdk.PrivateKey;
import com.hedera.hashgraph.sdk.TokenType;
import com.hedera.hashgraph.sdk.TransactionReceipt;
import com.hedera.mirror.monitor.MonitorProperties;
import com.hedera.mirror.monitor.NodeProperties;
import com.hedera.mirror.monitor.publish.PublishRequest;
import com.hedera.mirror.monitor.publish.PublishResponse;
import com.hedera.mirror.monitor.publish.PublishScenario;
import com.hedera.mirror.monitor.publish.PublishScenarioProperties;
import com.hedera.mirror.monitor.publish.TransactionPublisher;

@Log4j2
@Named
@RequiredArgsConstructor
public class ExpressionConverterImpl implements ExpressionConverter {

    private static final String EXPRESSION_START = "${";
    private static final String EXPRESSION_END = "}";
    private static final Pattern EXPRESSION_PATTERN = Pattern
            .compile("\\$\\{(account|nft|token|topic|schedule)\\.([A-Za-z0-9_]+)}");

    private final Map<Expression, String> expressions = new ConcurrentHashMap<>();
    private final MonitorProperties monitorProperties;
    private final TransactionPublisher transactionPublisher;

    @Override
    public String convert(String property) {
        if (!StringUtils.startsWith(property, EXPRESSION_START) || !StringUtils.endsWith(property, EXPRESSION_END)) {
            return property;
        }

        Expression expression = parse(property);
        String convertedProperty = expressions.computeIfAbsent(expression, this::doConvert);
        log.info("Converted property {} to {}", property, convertedProperty);
        return convertedProperty;
    }

    private synchronized String doConvert(Expression expression) {
        if (expressions.containsKey(expression)) {
            return expressions.get(expression);
        }

        try {
            log.debug("Processing expression {}", expression);
            ExpressionType type = expression.getType();
            Class<? extends TransactionSupplier<?>> supplierClass = type.getTransactionType().getSupplier();
            TransactionSupplier<?> transactionSupplier = supplierClass.getConstructor().newInstance();

            if (transactionSupplier instanceof AdminKeyable) {
                AdminKeyable adminKeyable = (AdminKeyable) transactionSupplier;
                PrivateKey privateKey = PrivateKey.fromString(monitorProperties.getOperator().getPrivateKey());
                adminKeyable.setAdminKey(privateKey.getPublicKey().toString());
            }

            if (transactionSupplier instanceof TokenCreateTransactionSupplier) {
                TokenCreateTransactionSupplier tokenSupplier = (TokenCreateTransactionSupplier) transactionSupplier;
                tokenSupplier.setTreasuryAccountId(monitorProperties.getOperator().getAccountId());
                if (type == ExpressionType.NFT) {
                    tokenSupplier.setType(TokenType.NON_FUNGIBLE_UNIQUE);
                }
            }

            // if ScheduleCreate set the properties to the inner scheduledTransactionProperties
            if (transactionSupplier instanceof ScheduleCreateTransactionSupplier) {
                ScheduleCreateTransactionSupplier scheduleCreateTransactionSupplier =
                        (ScheduleCreateTransactionSupplier) transactionSupplier;
                NodeProperties singleNodeProperty = Iterables.get(monitorProperties.getNodes(), 0);
                scheduleCreateTransactionSupplier.setNodeAccountId(singleNodeProperty.getAccountId());
                scheduleCreateTransactionSupplier
                        .setOperatorAccountId(monitorProperties.getOperator().getAccountId());
            }

            PublishScenarioProperties publishScenarioProperties = new PublishScenarioProperties();
            publishScenarioProperties.setName(expression.toString());
            publishScenarioProperties.setTimeout(Duration.ofSeconds(30L));
            publishScenarioProperties.setType(type.getTransactionType());
            PublishScenario scenario = new PublishScenario(publishScenarioProperties);
            PublishRequest request = PublishRequest.builder()
                    .receipt(true)
                    .scenario(scenario)
                    .timestamp(Instant.now())
                    .transaction(transactionSupplier.get())
                    .build();

            Retry retrySpec = Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(500L))
                    .maxBackoff(Duration.ofSeconds(8L))
                    .doBeforeRetry(r -> log.warn("Retry attempt #{} after failure: {}",
                            r.totalRetries() + 1, r.failure().getMessage()));

            String createdId = Mono.defer(() -> transactionPublisher.publish(request))
                    .retryWhen(retrySpec)
                    .map(PublishResponse::getReceipt)
                    .map(type.getIdExtractor()::apply)
                    .toFuture()
                    .join();
            log.info("Created {} entity {}", type, createdId);
            return createdId;
        } catch (Exception e) {
            log.error("Error converting expression {}:", expression, e);
            throw new RuntimeException(e);
        }
    }

    private Expression parse(String expression) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(expression);

        if (!matcher.matches() || matcher.groupCount() != 2) {
            throw new IllegalArgumentException("Not a valid property expression: " + expression);
        }

        ExpressionType type = ExpressionType.valueOf(matcher.group(1).toUpperCase());
        String id = matcher.group(2);
        return new Expression(type, id);
    }

    @Getter
    @RequiredArgsConstructor
    private enum ExpressionType {

        ACCOUNT(TransactionType.ACCOUNT_CREATE, r -> r.accountId.toString()),
        NFT(TransactionType.TOKEN_CREATE, r -> r.tokenId.toString()),
        SCHEDULE(TransactionType.SCHEDULE_CREATE, r -> r.scheduleId.toString()),
        TOKEN(TransactionType.TOKEN_CREATE, r -> r.tokenId.toString()),
        TOPIC(TransactionType.CONSENSUS_CREATE_TOPIC, r -> r.topicId.toString());

        private final TransactionType transactionType;
        private final Function<TransactionReceipt, String> idExtractor;
    }

    @Value
    private class Expression {
        private ExpressionType type;
        private String id;

        @Override
        public String toString() {
            return type.name().toLowerCase() + "." + id;
        }
    }
}
