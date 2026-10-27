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

inline bool FastArrayListFolding::is_candidate_of_kind(const Klass* k, int kind) {
  return kind == Klass::InstanceSwissTableBufferKlassKind && FastListFolding &&
         k == InstanceSwissTableBufferKlass::fast_list_klass();
}

inline oop FastArrayListFolding::storage_to_fold(oop obj, markWord m) {
  const Klass* list_klass = InstanceSwissTableBufferKlass::fast_list_klass();
  if (!FastListFolding || list_klass == nullptr || obj->klass() != list_klass) {
    return nullptr;
  }
  if (!m.is_unlocked()) {
    return nullptr;                     // locked: its mark is elsewhere (stack lock, monitor)
  }
  if (size_of(obj) != InstanceSwissTableBufferKlass::fast_list_forwarded) {
    return nullptr;
  }
  oop storage = storage_of(obj);
  if (storage == nullptr || storage == obj || storage->klass() != list_klass) {
    return nullptr;
  }
  // The fields of an object that is copied already are still in place.
  if (size_of(storage) < 0) {
    return nullptr;                     // a racing change left another state
  }
  return storage;
}

inline bool FastArrayListFolding::can_take_identity(markWord m, markWord t) {
  return t.is_unlocked() && (m.has_no_hash() || t.has_no_hash() || t.hash() == m.hash());
}

inline bool FastArrayListFolding::carries_hash(markWord m, oop copy) {
  const markWord c = copy->mark();
  return c.is_unlocked() && !c.has_no_hash() && c.hash() == m.hash();
}

inline bool FastArrayListFolding::can_take_identity(markWord m, oop target) {
  return can_take_identity(m, target->mark());
}

inline jint FastArrayListFolding::size_of(oop list) {
  return *list->field_addr<jint>(InstanceSwissTableBufferKlass::fast_list_size_offset());
}

inline void FastArrayListFolding::set_size(oop list, jint size) {
  *list->field_addr<jint>(InstanceSwissTableBufferKlass::fast_list_size_offset()) = size;
}

// A raw load: only stop-the-world collections fold. (A HeapAccess would instantiate the barrier
// dispatch of every barrier set where this is inlined, Shenandoah's evacuating load barrier
// included, whose object copy then keeps the copy of the young collection out of line.)
inline oop FastArrayListFolding::storage_of(oop list) {
  return RawAccess<>::oop_load_at(list, InstanceSwissTableBufferKlass::fast_list_fwd_offset());
}

inline bool FastArrayListFolding::full_is_folded(oop obj, const Klass* k) {
  return is_candidate(obj, k) && size_of(obj) == InstanceSwissTableBufferKlass::fast_list_folding;
}

inline void FastArrayListFolding::transfer_hash(markWord m, oop target) {
  if (!m.has_no_hash()) {
    target->set_mark(target->mark().copy_set_hash(m.hash()));
  }
}

#endif // SHARE_GC_SHARED_FASTARRAYLISTFOLDING_INLINE_HPP
