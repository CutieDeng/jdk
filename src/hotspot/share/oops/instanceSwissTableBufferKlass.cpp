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
#include "classfile/symbolTable.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/vmSymbols.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/memAllocator.hpp"
#include "jni.h"
#include "oops/instanceSwissTableBufferKlass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "utilities/ostream.hpp"
#include "utilities/powerOfTwo.hpp"

InstanceSwissTableBufferKlass::Layout InstanceSwissTableBufferKlass::_swiss     = { -1, false, -1, 1, 2 };
InstanceSwissTableBufferKlass::Layout InstanceSwissTableBufferKlass::_fast_list = { -1, false, -1, 0, 1 };
const Klass* InstanceSwissTableBufferKlass::_fast_list_klass = nullptr;
int InstanceSwissTableBufferKlass::_aux_offset = -1;
int InstanceSwissTableBufferKlass::_fast_list_size_offset = -1;

InstanceSwissTableBufferKlass::InstanceSwissTableBufferKlass(const ClassFileParser& parser)
  : InstanceKlass(parser, Kind) {
  // Instances are variable sized: use the slow path so that every size query goes to oop_size(),
  // and so that no allocation path allocates the fixed instance size of the class.
  const jint lh = Klass::instance_layout_helper(size_helper(), true);
  set_layout_helper(lh);
}

static int local_field_offset(InstanceKlass* k, const char* name, Symbol* signature, const char* what) {
  fieldDescriptor fd;
  TempNewSymbol sym = SymbolTable::new_symbol(name);
  const bool found = k->find_local_field(sym, signature, &fd);
  guarantee(found && !fd.is_static(), "%s", what);
  return fd.offset();
}

void InstanceSwissTableBufferKlass::init_offsets() {
  InstanceKlass* k = vmClasses::SwissTableBuffer_klass();
  guarantee(k->is_swiss_table_buffer_instance_klass(), "java.util.SwissTableBuffer must have InstanceSwissTableBufferKlass");
  _swiss.capacity_offset = local_field_offset(k, "capacity", vmSymbols::int_signature(),
                                              "java.util.SwissTableBuffer must declare int capacity");
  _swiss.data_base = cast(k)->ctrl_base_in_bytes();
  _aux_offset = local_field_offset(k, "aux", vmSymbols::object_signature(),
                                   "java.util.SwissTableBuffer must declare Object aux");

  InstanceKlass* f = vmClasses::FastArrayList_klass();
  guarantee(f->is_swiss_table_buffer_instance_klass(), "java.util.FastArrayList must have InstanceSwissTableBufferKlass");
  _fast_list_size_offset = local_field_offset(f, "size", vmSymbols::int_signature(),
                                              "java.util.FastArrayList must declare int size");
  _fast_list.capacity_offset = local_field_offset(f, "cap", vmSymbols::int_signature(),
                                                  "java.util.FastArrayList must declare int cap");
  // The elements follow the declared fields. Element 0 must not be adjacent to a field of its own
  // size that is loaded on its own (see the class comment): size; cap unless it starts an aligned
  // word, which the Java code then reads at once (with fwd, when fwd is the other half).
  int base = cast(f)->ctrl_base_in_bytes();
  if (base == _fast_list_size_offset + BytesPerInt && heapOopSize == BytesPerInt) {
    base += heapOopSize;
  }
  if (base == _fast_list.capacity_offset + BytesPerInt && heapOopSize == BytesPerInt &&
      !is_aligned(_fast_list.capacity_offset, BytesPerLong)) {
    base += heapOopSize;
  }
  guarantee(is_aligned(base, heapOopSize), "FastArrayList elements must be aligned");
  _fast_list.data_base = base;
  _fast_list_klass = f;
}

jint InstanceSwissTableBufferKlass::max_capacity() const {
  if (!is_fast_list()) {
    // Keep the instance size below 2^31 bytes: 9 bytes per slot with compressed oops, 17 without.
    return heapOopSize == 4 ? (1 << 27) : (1 << 26);
  }
  // Keep the instance size below 2^31 bytes, and at most as many elements as an array may have.
  const jlong by_size = ((jlong)max_jint - _fast_list.data_base - MinObjAlignmentInBytes) / heapOopSize;
  return (jint)MIN2(by_size, (jlong)max_jint - 8);
}

bool InstanceSwissTableBufferKlass::is_valid_capacity(jint capacity) const {
  if (capacity < 0 || capacity > max_capacity()) {
    return false;
  }
  return is_fast_list() || capacity == 0 || (capacity >= 8 && is_power_of_2(capacity));
}

size_t InstanceSwissTableBufferKlass::instance_size(jint capacity) const {
  assert(is_valid_capacity(capacity), "invalid capacity %d", capacity);
  const Layout& l = layout();
  const size_t bytes = (size_t)l.data_base +
                       (size_t)capacity * ((size_t)l.bytes_per_slot + (size_t)l.refs_per_slot * heapOopSize);
  return align_object_size(align_up(bytes, (size_t)HeapWordSize) >> LogHeapWordSize);
}

size_t InstanceSwissTableBufferKlass::oop_size(oop obj) const {
  return instance_size(capacity(obj));
}

// Writes the capacity before the klass is published: once the klass is non-null, a concurrent
// collector may parse the object, and its size comes from the capacity.
class SwissTableBufferAllocator : public MemAllocator {
  const InstanceSwissTableBufferKlass::Layout& _layout;
  const jint _capacity;
public:
  SwissTableBufferAllocator(Klass* klass, size_t word_size, jint capacity, Thread* thread)
    : MemAllocator(klass, word_size, thread),
      _layout(InstanceSwissTableBufferKlass::layout(klass)), _capacity(capacity) {}

  virtual oop initialize(HeapWord* mem) const {
    mem_clear(mem);                                   // primitive bytes 0 (control bytes EMPTY), references null
    address cap = (address)mem + _layout.capacity_offset;
    if (_layout.capacity_is_long) {
      *(jlong*)cap = _capacity;
    } else {
      *(jint*)cap = _capacity;
    }
    return finish(mem);
  }
};

instanceOop InstanceSwissTableBufferKlass::allocate_table(jint capacity, TRAPS) {
  if (!is_valid_capacity(capacity)) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(),
                   is_fast_list() ? "invalid FastArrayList capacity" : "invalid SwissTableBuffer capacity");
  }
  assert(is_initialized(), "allocated from Java code of the class");
  SwissTableBufferAllocator allocator(this, instance_size(capacity), capacity, THREAD);
  return (instanceOop)allocator.allocate();
}

#ifndef PRODUCT
void InstanceSwissTableBufferKlass::oop_print_on(oop obj, outputStream* st) {
  InstanceKlass::oop_print_on(obj, st);
  const jint cap = capacity(obj);
  const Layout& l = layout();
  st->print_cr(" - capacity %d; primitive bytes: %d at offset %d, references: %d at offset %d",
               cap, l.bytes_per_slot * cap, l.data_base, l.refs_per_slot * cap,
               l.data_base + l.bytes_per_slot * cap);
}
#endif

// Natives of java.util.SwissTableBuffer, registered by its static initializer.

JVM_ENTRY(jobject, SwissTableBuffer_allocate(JNIEnv* env, jclass cls, jint capacity)) {
  InstanceSwissTableBufferKlass* k = InstanceSwissTableBufferKlass::cast(vmClasses::SwissTableBuffer_klass());
  oop obj = k->allocate_table(capacity, CHECK_NULL);
  return JNIHandles::make_local(THREAD, obj);
} JVM_END

JVM_ENTRY(jint, SwissTableBuffer_ctrlBase(JNIEnv* env, jclass cls)) {
  return InstanceSwissTableBufferKlass::cast(vmClasses::SwissTableBuffer_klass())->ctrl_base_in_bytes();
} JVM_END

JVM_ENTRY(jint, SwissTableBuffer_maxCapacity(JNIEnv* env, jclass cls)) {
  return InstanceSwissTableBufferKlass::cast(vmClasses::SwissTableBuffer_klass())->max_capacity();
} JVM_END

#define CC (char*)
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod java_util_SwissTableBuffer_methods[] = {
  {CC "allocate",    CC "(I)Ljava/util/SwissTableBuffer;", FN_PTR(SwissTableBuffer_allocate)},
  {CC "ctrlBase",    CC "()I",                       FN_PTR(SwissTableBuffer_ctrlBase)},
  {CC "maxCapacity", CC "()I",                       FN_PTR(SwissTableBuffer_maxCapacity)},
};

#undef CC
#undef FN_PTR

JVM_ENTRY(void, JVM_RegisterSwissTableBufferMethods(JNIEnv* env, jclass cls)) {
  ThreadToNativeFromVM ttnfv(thread);
  int ok = env->RegisterNatives(cls, java_util_SwissTableBuffer_methods,
                                sizeof(java_util_SwissTableBuffer_methods) / sizeof(JNINativeMethod));
  guarantee(ok == 0, "register java.util.SwissTableBuffer natives");
} JVM_END

// Natives of java.util.FastArrayList, registered by its static initializer.

JVM_ENTRY(jobject, FastArrayList_allocate(JNIEnv* env, jclass cls, jint capacity)) {
  InstanceSwissTableBufferKlass* k = InstanceSwissTableBufferKlass::cast(vmClasses::FastArrayList_klass());
  oop obj = k->allocate_table(capacity, CHECK_NULL);
  return JNIHandles::make_local(THREAD, obj);
} JVM_END

JVM_ENTRY(jint, FastArrayList_dataBase(JNIEnv* env, jclass cls)) {
  return InstanceSwissTableBufferKlass::fast_list_layout().data_base;
} JVM_END

JVM_ENTRY(jint, FastArrayList_maxCapacity(JNIEnv* env, jclass cls)) {
  return InstanceSwissTableBufferKlass::cast(vmClasses::FastArrayList_klass())->max_capacity();
} JVM_END

#define CC (char*)
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod java_util_FastArrayList_methods[] = {
  {CC "allocate",    CC "(I)Ljava/util/FastArrayList;", FN_PTR(FastArrayList_allocate)},
  {CC "dataBase",    CC "()I",                          FN_PTR(FastArrayList_dataBase)},
  {CC "maxCapacity", CC "()I",                          FN_PTR(FastArrayList_maxCapacity)},
};

#undef CC
#undef FN_PTR

JVM_ENTRY(void, JVM_RegisterFastArrayListMethods(JNIEnv* env, jclass cls)) {
  ThreadToNativeFromVM ttnfv(thread);
  int ok = env->RegisterNatives(cls, java_util_FastArrayList_methods,
                                sizeof(java_util_FastArrayList_methods) / sizeof(JNINativeMethod));
  guarantee(ok == 0, "register java.util.FastArrayList natives");
} JVM_END
