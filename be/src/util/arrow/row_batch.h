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

#pragma once

#include <memory>
#include <string>

#include "common/status.h"
#include "runtime/types.h"
#include "vec/core/block.h"
#include "vec/exprs/vexpr_fwd.h"

// This file will convert Doris RowBatch to/from Arrow's RecordBatch
// RowBatch is used by Doris query engine to exchange data between
// each execute node.

namespace arrow {

class DataType;
class RecordBatch;
class Schema;

} // namespace arrow

namespace doris {

class RowDescriptor;

Status convert_to_arrow_type(const TypeDescriptor& type, std::shared_ptr<arrow::DataType>* result);

Status get_arrow_schema(const vectorized::Block& block, std::shared_ptr<arrow::Schema>* result);

Status convert_expr_ctxs_arrow_schema(const vectorized::VExprContextSPtrs& output_vexpr_ctxs,
                                      std::shared_ptr<arrow::Schema>* result);

Status serialize_record_batch(const arrow::RecordBatch& record_batch, std::string* result);

Status serialize_arrow_schema(std::shared_ptr<arrow::Schema>* schema, std::string* result);

} // namespace doris
