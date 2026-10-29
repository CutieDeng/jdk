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

#ifndef SHARE_GC_Z_ZFASTARRAYLISTFOLDING_INLINE_HPP
#define SHARE_GC_Z_ZFASTARRAYLISTFOLDING_INLINE_HPP

#include "gc/z/zAddress.inline.hpp"
#include "gc/z/zHeap.inline.hpp"
#include "gc/z/zPage.inline.hpp"
#include "memory/allStatic.hpp"
#include "oops/instanceSwissTableBufferKlass.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/atomic.hpp"
#include "runtime/globals.hpp"

// Folding of java.util.FastArrayList (design B, FastListFolding) in generational ZGC; the other
// collectors: gc/shared/fastArrayListFolding.hpp. A list O that forwards to its storage S (size
// FORWARDED, fwd S) and that a relocation moves is not copied: its forwarding entry gets the final
// address T of S, flagged as a fold (ZForwardingEntry::is_fold), so that every reference to O, when
// it is remapped, refers to T, which is the list from then on (zRelocate.cpp, fast_list_fold).
// Marking leaves such lists out of the live bytes of their pages (zMark.cpp), so that pages of
// lists that forward are selected for relocation, which folds them; a list that relocation then
// copies after all (it was locked, or its storage was not at its final address yet, ...) makes the
// estimate of the relocation set short by its bytes, and folds in a later relocation.
class ZFastArrayListFolding : AllStatic {
public:
  // Whether the object at addr is a FastArrayList that forwards (FORWARDED, not FORWARDING, whose
  // slots forwardTo still clears) and folding is on
  static bool is_forwarding_list(zaddress addr) {
    const Klass* const list_klass = InstanceSwissTableBufferKlass::fast_list_klass();
    const oop obj = to_oop(addr);
    return FastListFolding && list_klass != nullptr && obj->klass() == list_klass &&
           Atomic::load(obj->field_addr<jint>(InstanceSwissTableBufferKlass::fast_list_size_offset())) ==
               InstanceSwissTableBufferKlass::fast_list_forwarded;
  }

  // The colored reference to the storage of a list that forwards, as it is in the list (fwd, or slot
  // 0 with slot 0 forwarding, where only a tagged one is the storage), or null
  static zpointer storage_pointer(zaddress list) {
    volatile zpointer* const p = to_oop(list)->field_addr<zpointer>(InstanceSwissTableBufferKlass::fast_list_fwd_offset());
    const zpointer ptr = Atomic::load(p);
    const uintptr_t tag = (uintptr_t)InstanceSwissTableBufferKlass::fast_list_forward_tag();
    if (tag != 0 && (untype(ptr) & tag) == 0) {
      return zpointer::null;
    }
    return ptr;
  }

  // Marking, for a list at addr in page that forwards: whether to leave it out of the live bytes of
  // the page, as relocation will likely fold it. An old list folds only into an old storage, which
  // its reference, healed by the marking of its fields just before, tells.
  static bool mark_excludes(zaddress addr, ZPage* page) {
    const zpointer ptr = storage_pointer(addr);
    if (is_null_any(ptr)) {
      return false;
    }
    if (page->is_young()) {
      return true;
    }
    return ZPointer::is_load_good(ptr) && ZHeap::heap()->is_old(ZPointer::uncolor(ptr));
  }
};

#endif // SHARE_GC_Z_ZFASTARRAYLISTFOLDING_INLINE_HPP
