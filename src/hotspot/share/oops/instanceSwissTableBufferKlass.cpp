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

int InstanceSwissTableBufferKlass::_capacity_offset = -1;
int InstanceSwissTableBufferKlass::_aux_offset = -1;

InstanceSwissTableBufferKlass::InstanceSwissTableBufferKlass(const ClassFileParser& parser)
  : InstanceKlass(parser, Kind) {
  // Instances are variable sized: use the slow path so that every size query goes to oop_size(),
  // and so that no allocation path allocates the fixed instance size of the class.
  const jint lh = Klass::instance_layout_helper(size_helper(), true);
  set_layout_helper(lh);
}

void InstanceSwissTableBufferKlass::init_offsets() {
  InstanceKlass* k = vmClasses::SwissTableBuffer_klass();
  fieldDescriptor fd;
  TempNewSymbol name = SymbolTable::new_symbol("capacity");
  bool found = k->find_local_field(name, vmSymbols::int_signature(), &fd);
  guarantee(found && !fd.is_static(), "java.util.SwissTableBuffer must declare int capacity");
  _capacity_offset = fd.offset();
  TempNewSymbol aux = SymbolTable::new_symbol("aux");
  found = k->find_local_field(aux, vmSymbols::object_signature(), &fd);
  guarantee(found && !fd.is_static(), "java.util.SwissTableBuffer must declare Object aux");
  _aux_offset = fd.offset();
  guarantee(k->is_swiss_table_buffer_instance_klass(), "java.util.SwissTableBuffer must have InstanceSwissTableBufferKlass");
}

jint InstanceSwissTableBufferKlass::max_capacity() {
  // Keep the instance size below 2^31 bytes: 9 bytes per slot with compressed oops, 17 without.
  return heapOopSize == 4 ? (1 << 27) : (1 << 26);
}

size_t InstanceSwissTableBufferKlass::instance_size(jint capacity) const {
  assert(capacity == 0 || (capacity >= 8 && is_power_of_2(capacity)), "invalid capacity %d", capacity);
  const size_t bytes = (size_t)ctrl_base_in_bytes() + (size_t)capacity * (1 + 2 * (size_t)heapOopSize);
  assert(is_aligned(bytes, HeapWordSize), "control bytes and references are word aligned");
  return align_object_size(bytes >> LogHeapWordSize);
}

size_t InstanceSwissTableBufferKlass::oop_size(oop obj) const {
  return instance_size(capacity(obj));
}

// Writes the capacity before the klass is published: once the klass is non-null, a concurrent
// collector may parse the object, and its size comes from the capacity.
class SwissTableBufferAllocator : public MemAllocator {
  const jint _capacity;
public:
  SwissTableBufferAllocator(Klass* klass, size_t word_size, jint capacity, Thread* thread)
    : MemAllocator(klass, word_size, thread), _capacity(capacity) {}

  virtual oop initialize(HeapWord* mem) const {
    mem_clear(mem);                                   // control bytes EMPTY (0), references null
    *(jint*)((address)mem + InstanceSwissTableBufferKlass::capacity_offset()) = _capacity;
    return finish(mem);
  }
};

instanceOop InstanceSwissTableBufferKlass::allocate_table(jint capacity, TRAPS) {
  if (capacity < 0 || capacity > max_capacity() || (capacity != 0 && (capacity < 8 || !is_power_of_2(capacity)))) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(), "invalid SwissTableBuffer capacity");
  }
  assert(is_initialized(), "allocated from Java code of the class");
  SwissTableBufferAllocator allocator(this, instance_size(capacity), capacity, THREAD);
  return (instanceOop)allocator.allocate();
}

#ifndef PRODUCT
void InstanceSwissTableBufferKlass::oop_print_on(oop obj, outputStream* st) {
  InstanceKlass::oop_print_on(obj, st);
  const jint cap = capacity(obj);
  st->print_cr(" - control bytes: %d at offset %d, references: %d at offset %d",
               cap, ctrl_base_in_bytes(), 2 * cap, ctrl_base_in_bytes() + cap);
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
  return InstanceSwissTableBufferKlass::max_capacity();
} JVM_END

JVM_ENTRY(jint, SwissTableBuffer_purgeMinReclaim(JNIEnv* env, jclass cls)) {
  return (jint) SwissTablePurgeMinReclaim;
} JVM_END

#define CC (char*)
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod java_util_SwissTableBuffer_methods[] = {
  {CC "allocate",    CC "(I)Ljava/util/SwissTableBuffer;", FN_PTR(SwissTableBuffer_allocate)},
  {CC "ctrlBase",    CC "()I",                       FN_PTR(SwissTableBuffer_ctrlBase)},
  {CC "maxCapacity", CC "()I",                       FN_PTR(SwissTableBuffer_maxCapacity)},
  {CC "purgeMinReclaim", CC "()I",                   FN_PTR(SwissTableBuffer_purgeMinReclaim)},
};

#undef CC
#undef FN_PTR

JVM_ENTRY(void, JVM_RegisterSwissTableBufferMethods(JNIEnv* env, jclass cls)) {
  ThreadToNativeFromVM ttnfv(thread);
  int ok = env->RegisterNatives(cls, java_util_SwissTableBuffer_methods,
                                sizeof(java_util_SwissTableBuffer_methods) / sizeof(JNINativeMethod));
  guarantee(ok == 0, "register java.util.SwissTableBuffer natives");
} JVM_END
