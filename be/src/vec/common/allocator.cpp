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

#include "vec/common/allocator.h"

#include <glog/logging.h>

#include <atomic>
// IWYU pragma: no_include <bits/chrono.h>
#include <chrono> // IWYU pragma: keep
#include <memory>
#include <new>
#include <random>
#include <thread>

// Allocator is used by too many files. For compilation speed, put dependencies in `.cpp` as much as possible.
#include "common/compiler_util.h"
#include "common/status.h"
#include "runtime/fragment_mgr.h"
#include "runtime/memory/global_memory_arbitrator.h"
#include "runtime/memory/mem_tracker_limiter.h"
#include "runtime/memory/thread_mem_tracker_mgr.h"
#include "runtime/thread_context.h"
#include "util/defer_op.h"
#include "util/mem_info.h"
#include "util/stack_util.h"
#include "util/uid_util.h"

std::unordered_map<void*, size_t> RecordSizeMemoryAllocator::_allocated_sizes;
std::mutex RecordSizeMemoryAllocator::_mutex;

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::sys_memory_check(
        size_t size) const {
#ifdef BE_TEST
    if (!doris::ExecEnv::ready()) {
        return;
    }
#endif
    if (doris::thread_context()->skip_memory_check != 0) {
        return;
    }

    if (UNLIKELY(doris::config::mem_alloc_fault_probability > 0.0)) {
        std::random_device rd;
        std::mt19937 gen(rd());
        std::bernoulli_distribution fault(doris::config::mem_alloc_fault_probability);
        if (fault(gen)) {
            const std::string injection_err_msg = fmt::format(
                    "[MemAllocInjectFault] Query {} alloc memory failed due to fault "
                    "injection.",
                    print_id(doris::thread_context()->task_id()));
            // Print stack trace for debug.
            [[maybe_unused]] auto stack_trace_st =
                    doris::Status::Error<doris::ErrorCode::MEM_ALLOC_FAILED, true>(
                            injection_err_msg);
            if (!doris::config::enable_stacktrace) {
                LOG(INFO) << stack_trace_st.to_string();
            }
            if (!doris::enable_thread_catch_bad_alloc) {
                doris::thread_context()->thread_mem_tracker_mgr->cancel_query(injection_err_msg);
            } else {
                throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, injection_err_msg);
            }
        }
    }

    if (doris::GlobalMemoryArbitrator::is_exceed_hard_mem_limit(size)) {
        // Only thread attach query, and has not completely waited for thread_wait_gc_max_milliseconds,
        // will wait for gc, asynchronous cancel or throw bad::alloc.
        // Otherwise, if the external catch, directly throw bad::alloc.
        std::string err_msg;
        err_msg += fmt::format(
                "Allocator sys memory check failed: Cannot alloc:{}, consuming "
                "tracker:<{}>, peak used {}, current used {}, exec node:<{}>, {}.",
                size, doris::thread_context()->thread_mem_tracker()->label(),
                doris::thread_context()->thread_mem_tracker()->peak_consumption(),
                doris::thread_context()->thread_mem_tracker()->consumption(),
                doris::thread_context()->thread_mem_tracker_mgr->last_consumer_tracker_label(),
                doris::GlobalMemoryArbitrator::process_limit_exceeded_errmsg_str());

        if (doris::config::stacktrace_in_alloc_large_memory_bytes > 0 &&
            size > doris::config::stacktrace_in_alloc_large_memory_bytes) {
            err_msg += "\nAlloc Stacktrace:\n" + doris::get_stack_trace();
        }

        // TODO, Save the query context in the thread context, instead of finding whether the query id is canceled in fragment_mgr.
        if (doris::thread_context()->thread_mem_tracker_mgr->is_query_cancelled()) {
            if (doris::enable_thread_catch_bad_alloc) {
                throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
            }
            return;
        }

        if (doris::thread_context()->thread_mem_tracker_mgr->is_attach_query() &&
            doris::thread_context()->thread_mem_tracker_mgr->wait_gc()) {
            int64_t wait_milliseconds = 0;
            LOG(INFO) << fmt::format(
                    "Query:{} waiting for enough memory in thread id:{}, maximum {}ms, {}.",
                    print_id(doris::thread_context()->task_id()),
                    doris::thread_context()->get_thread_id(),
                    doris::config::thread_wait_gc_max_milliseconds, err_msg);
            if (!doris::config::disable_memory_gc) {
                while (wait_milliseconds < doris::config::thread_wait_gc_max_milliseconds) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                    if (!doris::GlobalMemoryArbitrator::is_exceed_hard_mem_limit(size)) {
                        doris::GlobalMemoryArbitrator::refresh_interval_memory_growth += size;
                        break;
                    }
                    if (doris::thread_context()->thread_mem_tracker_mgr->is_query_cancelled()) {
                        if (doris::enable_thread_catch_bad_alloc) {
                            throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
                        }
                        return;
                    }
                    wait_milliseconds += 100;
                }
            }
            if (wait_milliseconds >= doris::config::thread_wait_gc_max_milliseconds) {
                // Make sure to completely wait thread_wait_gc_max_milliseconds only once.
                doris::thread_context()->thread_mem_tracker_mgr->disable_wait_gc();
                doris::MemTrackerLimiter::print_log_process_usage();
                // If the external catch, throw bad::alloc first, let the query actively cancel. Otherwise asynchronous cancel.
                if (!doris::enable_thread_catch_bad_alloc) {
                    LOG(INFO) << fmt::format(
                            "Query:{} canceled asyn, after waiting for memory {}ms, {}.",
                            print_id(doris::thread_context()->task_id()), wait_milliseconds,
                            err_msg);
                    doris::thread_context()->thread_mem_tracker_mgr->cancel_query(err_msg);
                } else {
                    LOG(INFO) << fmt::format(
                            "Query:{} throw exception, after waiting for memory {}ms, {}.",
                            print_id(doris::thread_context()->task_id()), wait_milliseconds,
                            err_msg);
                    throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
                }
            }
            // else, enough memory is available, the query continues execute.
        } else if (doris::enable_thread_catch_bad_alloc) {
            LOG(INFO) << fmt::format("sys memory check failed, throw exception, {}.", err_msg);
            doris::MemTrackerLimiter::print_log_process_usage();
            throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
        } else {
            LOG(INFO) << fmt::format("sys memory check failed, no throw exception, {}.", err_msg);
        }
    }
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::memory_tracker_check(
        size_t size) const {
#ifdef BE_TEST
    if (!doris::ExecEnv::ready()) {
        return;
    }
#endif
    if (doris::thread_context()->skip_memory_check != 0) {
        return;
    }
    auto st = doris::thread_context()->thread_mem_tracker()->check_limit(size);
    if (!st) {
        auto err_msg = fmt::format("Allocator mem tracker check failed, {}", st.to_string());
        doris::thread_context()->thread_mem_tracker()->print_log_usage(err_msg);
        // If the external catch, throw bad::alloc first, let the query actively cancel. Otherwise asynchronous cancel.
        if (doris::thread_context()->thread_mem_tracker_mgr->is_attach_query()) {
            doris::thread_context()->thread_mem_tracker_mgr->disable_wait_gc();
            if (!doris::enable_thread_catch_bad_alloc) {
                LOG(INFO) << fmt::format("query/load:{} canceled asyn, {}.",
                                         print_id(doris::thread_context()->task_id()), err_msg);
                doris::thread_context()->thread_mem_tracker_mgr->cancel_query(err_msg);
            } else {
                LOG(INFO) << fmt::format("query/load:{} throw exception, {}.",
                                         print_id(doris::thread_context()->task_id()), err_msg);
                throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
            }
        } else if (doris::enable_thread_catch_bad_alloc) {
            LOG(INFO) << fmt::format("memory tracker check failed, throw exception, {}.", err_msg);
            throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err_msg);
        } else {
            LOG(INFO) << fmt::format("memory tracker check failed, no throw exception, {}.",
                                     err_msg);
        }
    }
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::memory_check(
        size_t size) const {
    sys_memory_check(size);
    memory_tracker_check(size);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::consume_memory(
        size_t size) const {
    CONSUME_THREAD_MEM_TRACKER(size);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::release_memory(
        size_t size) const {
    RELEASE_THREAD_MEM_TRACKER(size);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::throw_bad_alloc(
        const std::string& err) const {
    LOG(WARNING) << err
                 << fmt::format("{}, Stacktrace: {}",
                                doris::GlobalMemoryArbitrator::process_mem_log_str(),
                                doris::get_stack_trace());
    doris::MemTrackerLimiter::print_log_process_usage();
    throw doris::Exception(doris::ErrorCode::MEM_ALLOC_FAILED, err);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::add_address_sanitizers(
        void* buf, size_t size) const {
#ifdef BE_TEST
    if (!doris::ExecEnv::ready()) {
        return;
    }
#endif
    doris::thread_context()->thread_mem_tracker()->add_address_sanitizers(buf, size);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::remove_address_sanitizers(
        void* buf, size_t size) const {
#ifdef BE_TEST
    if (!doris::ExecEnv::ready()) {
        return;
    }
#endif
    doris::thread_context()->thread_mem_tracker()->remove_address_sanitizers(buf, size);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void* Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::alloc(size_t size,
                                                                                size_t alignment) {
    return alloc_impl(size, alignment);
}

template <bool clear_memory_, bool mmap_populate, bool use_mmap, typename MemoryAllocator>
void* Allocator<clear_memory_, mmap_populate, use_mmap, MemoryAllocator>::realloc(
        void* buf, size_t old_size, size_t new_size, size_t alignment) {
    return realloc_impl(buf, old_size, new_size, alignment);
}

template class Allocator<true, true, true, DefaultMemoryAllocator>;
template class Allocator<true, true, false, DefaultMemoryAllocator>;
template class Allocator<true, false, true, DefaultMemoryAllocator>;
template class Allocator<true, false, false, DefaultMemoryAllocator>;
template class Allocator<false, true, true, DefaultMemoryAllocator>;
template class Allocator<false, true, false, DefaultMemoryAllocator>;
template class Allocator<false, false, true, DefaultMemoryAllocator>;
template class Allocator<false, false, false, DefaultMemoryAllocator>;

/** It would be better to put these Memory Allocators where they are used, such as in the orc memory pool and arrow memory pool.
  * But currently allocators use templates in .cpp instead of all in .h, so they can only be placed here.
  */
template class Allocator<true, true, false, ORCMemoryAllocator>;
template class Allocator<true, false, true, ORCMemoryAllocator>;
template class Allocator<true, false, false, ORCMemoryAllocator>;
template class Allocator<false, true, true, ORCMemoryAllocator>;
template class Allocator<false, true, false, ORCMemoryAllocator>;
template class Allocator<false, false, true, ORCMemoryAllocator>;
template class Allocator<false, false, false, ORCMemoryAllocator>;

template class Allocator<true, true, true, RecordSizeMemoryAllocator>;
template class Allocator<true, true, false, RecordSizeMemoryAllocator>;
template class Allocator<true, false, true, RecordSizeMemoryAllocator>;
template class Allocator<true, false, false, RecordSizeMemoryAllocator>;
template class Allocator<false, true, true, RecordSizeMemoryAllocator>;
template class Allocator<false, true, false, RecordSizeMemoryAllocator>;
template class Allocator<false, false, true, RecordSizeMemoryAllocator>;
template class Allocator<false, false, false, RecordSizeMemoryAllocator>;
