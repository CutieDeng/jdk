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

 Instances whose size depends on a capacity field: declared fields, then trailing data of
 primitive bytes and heap references, sized by the capacity. Two classes of java.base have this
 kind, each with a Layout of its own:

 java.util.SwissTableBuffer, the single storage buffer of a java.util.SwissTable
 (-XX:+UseSwissTable), so a table is one heap object instead of a control array plus a slot array.

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

 capacity is 0 or a power of two >= 8, so both regions are word aligned.

 java.util.FastArrayList, a list that is its own buffer: size, capacity and elements in one object.

                   +--------------------------------+  0
                   | object header                  |
                   | declared fields:               |
                   |   int size                     |  -1 once the list was replaced by a larger one
                   |   int cap                      |  the capacity, never changed
                   |   FastArrayList fwd            |  the list that replaced it, else null
                   +================================+  data_base: the instance size of the class
                   | elements                       |  cap heap oops
                   |                                |
                   +--------------------------------+  data_base + cap * heapOopSize, rounded up to
                                                       the object alignment

 cap is any value from 0 to max_capacity(). No two hot locations of one access size are adjacent
 (size, cap, element 0): the MacroAssembler merges adjacent loads or stores of one size into
 ldp/stp, and a load pair that an older 4-byte store covers only in part cannot take its data from
 the store (Kunpeng 920: 13 instead of 8 cycles per dependent step). When cap starts an aligned
 8-byte word (with compressed class pointers: size at 12, cap at 16, and with compressed oops fwd
 at 20, so that the elements start at 24), the Java code reads cap only as that word, with fwd
 when fwd is its other half (FastArrayList.capWord0): then neither cap nor fwd is loaded on its own
 next to size or element 0, but fwd of a list that forwards, whose elements are unused. Otherwise
 element 0 does not follow a 4-byte size or cap.

 The garbage collectors visit the declared reference fields and the trailing references; the
 primitive bytes are not references. The capacity is written before the klass is published, so the
 object is parsable as soon as a concurrent collector can see it. Java code reads and writes the
 trailing data with Unsafe, so every reference access carries the collector's barriers in the
 interpreter, C1 and C2. In C2 the trailing data of each class is one alias class of its own
 (Compile::is_swiss_table_buffer_data).

************************************************/

class InstanceSwissTableBufferKlass: public InstanceKlass {
  friend class VMStructs;
  friend class InstanceKlass;

public:
  static const KlassKind Kind = InstanceSwissTableBufferKlassKind;

  // Where the capacity is and how the trailing data is laid out, for one class of this kind.
  struct Layout {
    int  capacity_offset;      // offset of the capacity field
    bool capacity_is_long;     // a long field (else an int); the capacity itself is below 2^31
    int  data_base;            // offset of the trailing data, word aligned
    int  bytes_per_slot;       // primitive bytes per unit of capacity, at data_base (0: none)
    int  refs_per_slot;        // references per unit of capacity, after the primitive bytes
  };

private:
  static Layout _swiss;                     // java.util.SwissTableBuffer
  static Layout _fast_list;                 // java.util.FastArrayList
  static const Klass* _fast_list_klass;     // set by init_offsets
  static int _aux_offset;                   // SwissTableBuffer.aux
  static int _fast_list_size_offset;        // FastArrayList.size
  static int _fast_list_fwd_offset;         // FastArrayList.fwd

  InstanceSwissTableBufferKlass(const ClassFileParser& parser);

public:
  InstanceSwissTableBufferKlass() { assert(DumpSharedSpaces || UseSharedSpaces, "only for CDS"); }

  static InstanceSwissTableBufferKlass* cast(Klass* k) {
    assert(k->is_swiss_table_buffer_instance_klass(), "cast to InstanceSwissTableBufferKlass");
    return static_cast<InstanceSwissTableBufferKlass*>(k);
  }

  // Field offsets and layouts of java.util.SwissTableBuffer and java.util.FastArrayList, computed
  // once the classes are loaded.
  static void init_offsets();

  static const Layout& layout(const Klass* k) { return k == _fast_list_klass ? _fast_list : _swiss; }
  const Layout& layout() const { return layout(this); }
  bool is_fast_list() const { return this == _fast_list_klass; }
  static const Layout& swiss_layout() { return _swiss; }
  static const Layout& fast_list_layout() { return _fast_list; }

  static int capacity_offset() { return _swiss.capacity_offset; }   // SwissTableBuffer.capacity
  static int aux_offset() { return _aux_offset; }
  static int fast_list_size_offset() { return _fast_list_size_offset; }
  static int fast_list_fwd_offset() { return _fast_list_fwd_offset; }
  static const Klass* fast_list_klass() { return _fast_list_klass; }
  // Sizes of a FastArrayList that does not hold its elements (FastArrayList.FORWARDED, RETIRED): the
  // list object that forwards to its storage (fwd), which a young collection may fold into the
  // storage (FastListFolding); and a storage that the elements left, which forwards too but is not
  // folded (only stale iterators reach it, and they must fail).
  static const jint fast_list_forwarded = -1;
  static const jint fast_list_retired   = -2;

  // Byte offset of the control bytes of a SwissTableBuffer: the instance size of the class.
  int ctrl_base_in_bytes() const { return size_helper() << LogHeapWordSize; }
  // Byte offset of the trailing data.
  int data_base_in_bytes() const { return layout().data_base; }

  static jint capacity(oop obj);

  // Size in words of an instance with the given capacity.
  size_t instance_size(jint capacity) const;
  // Largest capacity whose instance size stays below 2^31 bytes.
  jint max_capacity() const;
  // Whether an instance of this class may have the given capacity.
  bool is_valid_capacity(jint capacity) const;

  virtual size_t oop_size(oop obj) const override;

  // Allocates a zeroed instance: every primitive byte 0 (a table's control bytes EMPTY), every
  // reference null.
  instanceOop allocate_table(jint capacity, TRAPS);

  // The trailing references of obj.
  template <typename T> inline T* refs_begin(oop obj) const;
  template <typename T> inline T* refs_end(oop obj) const;

  // Chunked marking (the full collections, which split object arrays into ObjArrayTasks): the
  // trailing references by index, like the elements of an object array; the instance part (the
  // metadata and the declared reference fields) separately, once.
  static inline int ref_count(oop obj);
  template <class OopClosureType>
  static inline void oop_iterate_refs(oop obj, OopClosureType* cl, int start, int end);
  template <class OopClosureType>
  static inline void oop_iterate_instance(oop obj, OopClosureType* cl);

  // Oop fields (and metadata) iterators: the declared reference fields, then the trailing references.

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
