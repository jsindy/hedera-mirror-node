package com.hedera.mirror.grpc.repository;

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

import static org.assertj.core.api.Assertions.assertThat;

import javax.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.hedera.mirror.common.domain.DomainBuilder;
import com.hedera.mirror.common.domain.addressbook.AddressBook;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.entity.EntityType;
import com.hedera.mirror.grpc.GrpcIntegrationTest;

class AddressBookRepositoryTest extends GrpcIntegrationTest {

    @Resource
    private AddressBookRepository addressBookRepository;

    @Resource
    private DomainBuilder domainBuilder;

    @Test
    void findLatestTimestamp() {
        EntityId fileId = EntityId.of(101L, EntityType.FILE);
        assertThat(addressBookRepository.findLatestTimestamp(fileId.getId())).isEmpty();

        domainBuilder.addressBook().customize(a -> a.fileId(EntityId.of(999L, EntityType.FILE))).persist();
        assertThat(addressBookRepository.findLatestTimestamp(fileId.getId())).isEmpty();

        AddressBook addressBook2 = domainBuilder.addressBook().customize(a -> a.fileId(fileId)).persist();
        assertThat(addressBookRepository.findLatestTimestamp(fileId.getId())).get()
                .isEqualTo(addressBook2.getStartConsensusTimestamp());

        AddressBook addressBook3 = domainBuilder.addressBook().customize(a -> a.fileId(fileId)).persist();
        assertThat(addressBookRepository.findLatestTimestamp(fileId.getId())).get()
                .isEqualTo(addressBook3.getStartConsensusTimestamp());
    }

    @Test
    @Transactional
    void cascade() {
        AddressBook addressBook = domainBuilder.addressBook().persist();
        assertThat(addressBookRepository.findById(addressBook.getStartConsensusTimestamp()))
                .get()
                .extracting(AddressBook::getEntries)
                .isNull();

        domainBuilder.addressBookEntry(1)
                .customize(a -> a.consensusTimestamp(addressBook.getStartConsensusTimestamp()))
                .persist();
        assertThat(addressBookRepository.findById(addressBook.getStartConsensusTimestamp()))
                .get()
                .extracting(AddressBook::getEntries)
                .as("Ensure entries aren't eagerly loaded")
                .isNull();
    }
}
