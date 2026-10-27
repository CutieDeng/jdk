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

#include "precompiled.hpp"
#include "gc/shared/fastArrayListFolding.inline.hpp"
#include "logging/log.hpp"
#include "runtime/atomic.hpp"

volatile size_t FastArrayListFolding::_folds = 0;

void FastArrayListFolding::count_fold() {
  Atomic::inc(&_folds, memory_order_relaxed);
}

void FastArrayListFolding::report(const char* collection) {
  const size_t folds = Atomic::xchg(&_folds, (size_t)0);
  if (folds != 0) {
    log_debug(gc)("%s: folded %zu java.util.FastArrayList", collection, folds);
  }
}

GrowableArray<FastArrayListFolding::SerialFold>* FastArrayListFolding::_serial_full_folds = nullptr;

// Phase 1 of a Serial full collection, for an object just marked (its mark is now the marked
// prototype; mark is the one it had). A hashed list moves its hash through its preserved mark,
// so that its storage must not have another one: that is known only while the storage is not
// marked yet (marking replaces its mark).
void FastArrayListFolding::serial_full_mark(oop obj, markWord mark) {
  oop storage = storage_to_fold(obj, mark);
  if (storage == nullptr) {
    return;
  }
  if (storage->is_gc_marked()) {
    if (!mark.has_no_hash()) {
      return;
    }
  } else if (!can_take_identity(mark, storage)) {
    return;
  }
  set_size(obj, InstanceSwissTableBufferKlass::fast_list_folding);
}

// Phase 2: obj gets no space; it is forwarded to its storage once the storage has its new address.
void FastArrayListFolding::serial_full_record(oop obj, size_t size) {
  if (_serial_full_folds == nullptr) {
    _serial_full_folds = new (mtGC) GrowableArray<SerialFold>(64, mtGC);
  }
  SerialFold f = { obj, size };
  _serial_full_folds->append(f);
}

// After phase 2: forward every folded list to the new address of its storage (the storage itself
// if it does not move: then its mark is the prototype, not a forwarding pointer), so that phase 3
// adjusts every reference to the list.
void FastArrayListFolding::serial_full_forward() {
  if (_serial_full_folds == nullptr) {
    return;
  }
  for (int i = 0; i < _serial_full_folds->length(); i++) {
    oop list = _serial_full_folds->at(i).list;
    oop storage = storage_of(list);
    oop target = storage->is_forwarded() ? storage->forwardee() : storage;
    list->forward_to(target);
    count_fold();
  }
}

// After phase 3: make every folded list a dead object for phase 4, which reads the first word of
// a dead object as the address of the next object to look at (ContiguousSpace::compact).
void FastArrayListFolding::serial_full_make_dead() {
  if (_serial_full_folds == nullptr) {
    return;
  }
  for (int i = 0; i < _serial_full_folds->length(); i++) {
    const SerialFold& f = _serial_full_folds->at(i);
    HeapWord* start = cast_from_oop<HeapWord*>(f.list);
    *(HeapWord**)start = start + f.size;
  }
  _serial_full_folds->clear();
}
