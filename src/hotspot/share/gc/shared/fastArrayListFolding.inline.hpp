/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_GC_SHARED_FASTARRAYLISTFOLDING_INLINE_HPP
#define SHARE_GC_SHARED_FASTARRAYLISTFOLDING_INLINE_HPP

#include "gc/shared/fastArrayListFolding.hpp"

#include "oops/access.inline.hpp"
#include "oops/instanceSwissTableBufferKlass.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/globals.hpp"

inline bool FastArrayListFolding::is_candidate(oop obj, const Klass* k) {
  assert(obj->klass() == k, "the klass of obj");
  const jint lh = k->layout_helper();
  return lh > Klass::_lh_neutral_value && Klass::layout_helper_needs_slow_path(lh) &&
         FastListFolding && k == InstanceSwissTableBufferKlass::fast_list_klass();
}

inline oop FastArrayListFolding::storage_to_fold(oop obj, markWord m) {
  const Klass* list_klass = InstanceSwissTableBufferKlass::fast_list_klass();
  if (!FastListFolding || list_klass == nullptr || obj->klass() != list_klass) {
    return nullptr;
  }
  if (!m.is_unlocked()) {
    return nullptr;                     // locked: its mark is elsewhere (stack lock, monitor)
  }
  const int size_offset = InstanceSwissTableBufferKlass::fast_list_size_offset();
  if (*obj->field_addr<jint>(size_offset) != InstanceSwissTableBufferKlass::fast_list_forwarded) {
    return nullptr;
  }
  oop storage = HeapAccess<AS_NO_KEEPALIVE>::oop_load_at(obj, InstanceSwissTableBufferKlass::fast_list_fwd_offset());
  if (storage == nullptr || storage == obj || storage->klass() != list_klass) {
    return nullptr;
  }
  // The fields of an object that is copied already are still in place.
  if (*storage->field_addr<jint>(size_offset) < 0) {
    return nullptr;                     // a racing change left another state
  }
  return storage;
}

inline bool FastArrayListFolding::can_take_identity(markWord m, oop target) {
  const markWord t = target->mark();
  return t.is_unlocked() && (m.has_no_hash() || t.has_no_hash());
}

inline void FastArrayListFolding::transfer_hash(markWord m, oop target) {
  if (!m.has_no_hash()) {
    target->set_mark(target->mark().copy_set_hash(m.hash()));
  }
}

#endif // SHARE_GC_SHARED_FASTARRAYLISTFOLDING_INLINE_HPP
