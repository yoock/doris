// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import org.junit.jupiter.api.Assertions;

suite("docs/table-design/data-model/unique.md") {
    try {
        multi_sql """
        CREATE TABLE IF NOT EXISTS example_tbl_unique
        (
            `user_id` LARGEINT NOT NULL COMMENT "用户id",
            `username` VARCHAR(50) NOT NULL COMMENT "用户昵称",
            `city` VARCHAR(20) COMMENT "用户所在城市",
            `age` SMALLINT COMMENT "用户年龄",
            `sex` TINYINT COMMENT "用户性别",
            `phone` LARGEINT COMMENT "用户电话",
            `address` VARCHAR(500) COMMENT "用户地址",
            `register_time` DATETIME COMMENT "用户注册时间"
        )
        UNIQUE KEY(`user_id`, `username`)
        DISTRIBUTED BY HASH(`user_id`) BUCKETS 1
        PROPERTIES (
        "replication_allocation" = "tag.location.default: 1"
        );
        """

        multi_sql """
        CREATE TABLE IF NOT EXISTS example_tbl_unique_merge_on_write
        (
            `user_id` LARGEINT NOT NULL COMMENT "用户id",
            `username` VARCHAR(50) NOT NULL COMMENT "用户昵称",
            `city` VARCHAR(20) COMMENT "用户所在城市",
            `age` SMALLINT COMMENT "用户年龄",
            `sex` TINYINT COMMENT "用户性别",
            `phone` LARGEINT COMMENT "用户电话",
            `address` VARCHAR(500) COMMENT "用户地址",
            `register_time` DATETIME COMMENT "用户注册时间"
        )
        UNIQUE KEY(`user_id`, `username`)
        DISTRIBUTED BY HASH(`user_id`) BUCKETS 1
        PROPERTIES (
        "replication_allocation" = "tag.location.default: 1",
        "enable_unique_key_merge_on_write" = "true"
        );
        """
    } catch (Throwable t) {
        Assertions.fail("examples in docs/table-design/data-model/unique.md failed to exec, please fix it", t)
    }
}
