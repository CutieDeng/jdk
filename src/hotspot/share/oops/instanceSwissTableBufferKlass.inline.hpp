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

#ifndef SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_INLINE_HPP
#define SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_INLINE_HPP

#include "oops/instanceSwissTableBufferKlass.hpp"

#include "memory/iterator.hpp"
#include "memory/memRegion.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/globalDefinitions.hpp"

inline jint InstanceSwissTableBufferKlass::capacity(oop obj) {
  return obj->int_field(_capacity_offset);
}

template <typename T>
inline T* InstanceSwissTableBufferKlass::refs_begin(oop obj) const {
  return (T*)(cast_from_oop<address>(obj) + ctrl_base_in_bytes() + capacity(obj));
}

template <typename T>
inline T* InstanceSwissTableBufferKlass::refs_end(oop obj) const {
  return refs_begin<T>(obj) + 2 * (size_t)capacity(obj);
}

template <typename T, class OopClosureType>
void InstanceSwissTableBufferKlass::oop_oop_iterate(oop obj, OopClosureType* closure) {
  InstanceKlass::oop_oop_iterate<T>(obj, closure);      // metadata, declared reference fields
  T* p = refs_begin<T>(obj);
  T* const end = refs_end<T>(obj);
  for (; p < end; p++) {
    Devirtualizer::do_oop(closure, p);
  }
}

template <typename T, class OopClosureType>
void InstanceSwissTableBufferKlass::oop_oop_iterate_reverse(oop obj, OopClosureType* closure) {
  assert(!Devirtualizer::do_metadata(closure), "Code to handle metadata is not implemented");
  T* const begin = refs_begin<T>(obj);
  for (T* p = refs_end<T>(obj); p > begin; ) {
    Devirtualizer::do_oop(closure, --p);
  }
  InstanceKlass::oop_oop_iterate_reverse<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceSwissTableBufferKlass::oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr) {
  InstanceKlass::oop_oop_iterate_bounded<T>(obj, closure, mr);
  T* p = MAX2(refs_begin<T>(obj), (T*)mr.start());
  T* const end = MIN2(refs_end<T>(obj), (T*)mr.end());
  for (; p < end; p++) {
    Devirtualizer::do_oop(closure, p);
  }
}

#endif // SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_INLINE_HPP
