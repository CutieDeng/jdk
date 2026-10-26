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

#ifndef SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_HPP
#define SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_HPP

#include "oops/instanceKlass.hpp"
#include "oops/oopsHierarchy.hpp"
#include "utilities/macros.hpp"

class ClassFileParser;
class MemRegion;

/************************************************

 The single storage buffer of a java.util.SwissTable (-XX:+UseSwissTable): an instance of
 java.util.SwissTableBuffer whose size depends on its capacity, so a table is one heap object instead of a
 control array plus a slot array.

                   +--------------------------------+  0
                   | object header                  |
                   | declared fields:               |
                   |   int capacity                 |
                   |   int growthLeft               |
                   |   Object aux                   |
                   +================================+  ctrl_base = instance size of the class
                   | control bytes                  |  capacity bytes, one per slot; read as 8-byte
                   |                                |  control words, one per group of 8 slots
                   +--------------------------------+  ctrl_base + capacity
                   | references                     |  2 * capacity heap oops: the key of slot i
                   |                                |  at 2i, its value at 2i + 1
                   +--------------------------------+  ctrl_base + capacity * (1 + 2 * heapOopSize)

 capacity is 0 or a power of two >= 8, so both regions are word aligned. The garbage collectors
 visit the declared reference field and the trailing references; the control bytes are primitive
 data. capacity is written before the klass is published, so the object is parsable as soon as a
 concurrent collector can see it. Java code reads and writes the buffer with Unsafe, so every
 reference access carries the collector's barriers in the interpreter, C1 and C2.

************************************************/

class InstanceSwissTableBufferKlass: public InstanceKlass {
  friend class VMStructs;
  friend class InstanceKlass;

public:
  static const KlassKind Kind = InstanceSwissTableBufferKlassKind;

private:
  static int _capacity_offset;
  static int _aux_offset;

  InstanceSwissTableBufferKlass(const ClassFileParser& parser);

public:
  InstanceSwissTableBufferKlass() { assert(DumpSharedSpaces || UseSharedSpaces, "only for CDS"); }

  static InstanceSwissTableBufferKlass* cast(Klass* k) {
    assert(k->is_swiss_table_buffer_instance_klass(), "cast to InstanceSwissTableBufferKlass");
    return static_cast<InstanceSwissTableBufferKlass*>(k);
  }

  // Field offsets of java.util.SwissTableBuffer, computed once the class is loaded.
  static void init_offsets();
  static int capacity_offset() { return _capacity_offset; }
  static int aux_offset() { return _aux_offset; }

  // Byte offset of the control bytes: the instance size of the class, word aligned.
  int ctrl_base_in_bytes() const { return size_helper() << LogHeapWordSize; }

  static jint capacity(oop obj);

  // Size in words of a table with the given capacity.
  size_t instance_size(jint capacity) const;
  // Largest capacity whose instance size fits the limits below.
  static jint max_capacity();

  virtual size_t oop_size(oop obj) const override;

  // Allocates a zeroed table: every control byte EMPTY (0), every reference null.
  instanceOop allocate_table(jint capacity, TRAPS);

  // The trailing references of obj.
  template <typename T> inline T* refs_begin(oop obj) const;
  template <typename T> inline T* refs_end(oop obj) const;

  // Chunked marking (the full collections, which split object arrays into ObjArrayTasks): the
  // trailing references by index, like the elements of an object array; the instance part (the
  // metadata and the declared reference field) separately, once.
  static int ref_count(oop obj) { return 2 * capacity(obj); }
  template <class OopClosureType>
  static inline void oop_iterate_refs(oop obj, OopClosureType* cl, int start, int end);
  template <class OopClosureType>
  static inline void oop_iterate_instance(oop obj, OopClosureType* cl);

  // Oop fields (and metadata) iterators: the declared reference field, then the trailing references.

  // Forward iteration
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate(oop obj, OopClosureType* closure);

  // Reverse iteration
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_reverse(oop obj, OopClosureType* closure);

  // Bounded range iteration
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr);

#ifndef PRODUCT
  void oop_print_on(oop obj, outputStream* st) override;
#endif
};

#endif // SHARE_OOPS_INSTANCESWISSTABLEBUFFERKLASS_HPP
