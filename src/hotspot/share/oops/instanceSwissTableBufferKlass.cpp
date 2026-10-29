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
#include "logging/log.hpp"
#include "memory/oopFactory.hpp"
#include "oops/instanceSwissTableBufferKlass.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/oopHandle.inline.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/handshake.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/javaThread.inline.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "runtime/threadSMR.inline.hpp"
#include "utilities/ostream.hpp"
#include "utilities/powerOfTwo.hpp"
#if INCLUDE_ZGC
#include "gc/z/zBarrierSet.hpp"
#endif

InstanceSwissTableBufferKlass::Layout InstanceSwissTableBufferKlass::_swiss     = { -1, false, -1, 1, 2 };
InstanceSwissTableBufferKlass::Layout InstanceSwissTableBufferKlass::_fast_list = { -1, false, -1, 0, 1 };
const Klass* InstanceSwissTableBufferKlass::_fast_list_klass = nullptr;
int InstanceSwissTableBufferKlass::_aux_offset = -1;
int InstanceSwissTableBufferKlass::_fast_list_size_offset = -1;
int InstanceSwissTableBufferKlass::_fast_list_fwd_offset = -1;
jlong InstanceSwissTableBufferKlass::_fast_list_forward_tag = 0;

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
  {
    TempNewSymbol list_signature = SymbolTable::new_symbol("Ljava/util/FastArrayList;");
    _fast_list_fwd_offset = local_field_offset(f, "fwd", list_signature,
                                               "java.util.FastArrayList must declare FastArrayList fwd");
  }
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
#if INCLUDE_ZGC
  if (UseZGC && ZGenerational && FastListSlot0Forwarding) {
    // Slot 0 forwarding (see fast_list_forward_tag): the elements start at fwd, the last field, so
    // that a list object has no word of its own for the storage (8 bytes less without compressed oops).
    guarantee(_fast_list_fwd_offset + heapOopSize == base, "FastArrayList.fwd must be the last field");
    base = _fast_list_fwd_offset;
    _fast_list_forward_tag = ZBarrierSet::fast_list_forward_tag();
  }
#endif
  guarantee(is_aligned(base, heapOopSize), "FastArrayList elements must be aligned");
  _fast_list.data_base = base;
  _fast_list_klass = f;
}

JRT_LEAF(jboolean, InstanceSwissTableBufferKlass::store_fast_list_forward_leaf(oopDesc* list, oopDesc* storage))
  return store_fast_list_forward(JavaThread::current(), cast_to_oop(list), cast_to_oop(storage));
JRT_END

static void store_tagged(oop list, oop storage) {
#if INCLUDE_ZGC
  if (InstanceSwissTableBufferKlass::fast_list_slot0_forwarding()) {
    ZBarrierSet::store_tagged_oop_field(list, InstanceSwissTableBufferKlass::fast_list_layout().data_base, storage,
                                        InstanceSwissTableBufferKlass::fast_list_forward_tag());
    return;
  }
#endif
  ShouldNotReachHere();
}

// Whether slot 0 of list holds the storage: the tag in its raw word.
static bool slot0_tagged(oop list) {
  const uintptr_t raw = Atomic::load(list->field_addr<uintptr_t>(InstanceSwissTableBufferKlass::fast_list_layout().data_base));
  return (raw & (uintptr_t)InstanceSwissTableBufferKlass::fast_list_forward_tag()) != 0;
}

static jint size_of(oop list) {
  return list->int_field_acquire(InstanceSwissTableBufferKlass::fast_list_size_offset());
}

bool InstanceSwissTableBufferKlass::store_fast_list_forward(JavaThread* thread, oop list, oop storage) {
  assert(list->klass() == _fast_list_klass && storage->klass() == _fast_list_klass, "FastArrayList");
  // No safepoint from here to the tagged store: the record is there before any thread can see the tag.
  NoSafepointVerifier nsv;
  objArrayOop moves = objArrayOop(thread->fast_list_moves().resolve());
  const int n = thread->fast_list_moves_len();
  if (moves == nullptr || 2 * (n + 1) > moves->length()) {
    return false;
  }
  moves->obj_at_put(2 * n, list);
  moves->obj_at_put(2 * n + 1, storage);
  thread->set_fast_list_moves_len(n + 1);
  thread->set_fast_list_moves_counter(SafepointSynchronize::safepoint_id());
  store_tagged(list, storage);
  return true;
}

// Every thread reaches a safepoint poll (or is not running Java code), so that every write of slot 0
// that raced with a move is over.
class FastListMovesHandshake : public HandshakeClosure {
 public:
  FastListMovesHandshake() : HandshakeClosure("FastArrayListMoves") {}
  void do_thread(Thread* thread) {}
};

// A storage that slot 0 of list lost to a racing write, back, unless the list does not forward any
// more (a racing change), or the elements left this storage since (a later move has the right one).
// A CAS from the untagged word read, so that a racing move that stores its storage meanwhile wins.
static void repair_if_replaced(oop list, oop storage) {
  if (list == nullptr || storage == nullptr) {
    return;
  }
  const int data_base = InstanceSwissTableBufferKlass::fast_list_layout().data_base;
  const uintptr_t tag = (uintptr_t)InstanceSwissTableBufferKlass::fast_list_forward_tag();
  for (int attempt = 0; attempt < 4; attempt++) {     // the collector may recolor the word meanwhile
    const uintptr_t raw = Atomic::load(list->field_addr<uintptr_t>(data_base));
    if ((raw & tag) != 0 || size_of(list) != InstanceSwissTableBufferKlass::fast_list_forwarded) {
      return;                                          // (the list's own words first: usually all)
    }
    if (size_of(storage) < 0) {
      return;
    }
#if INCLUDE_ZGC
    if (ZBarrierSet::cas_tagged_oop_field(list, data_base, raw, storage, tag)) {
      log_debug(gc)("FastArrayList " PTR_FORMAT ": storage " PTR_FORMAT " back in slot 0", p2i(list), p2i(storage));
      return;
    }
#else
    return;
#endif
  }
}

void InstanceSwissTableBufferKlass::check_fast_list_moves(JavaThread* thread, bool handshake) {
  if (thread->fast_list_moves_len() == 0) {
    return;
  }
  if (handshake) {
    FastListMovesHandshake hs;
    Handshake::execute(&hs);
  }
  objArrayOop moves = objArrayOop(thread->fast_list_moves().resolve());
  const int n = thread->fast_list_moves_len();
  for (int i = 0; i < n; i++) {
    repair_if_replaced(moves->obj_at(2 * i), moves->obj_at(2 * i + 1));
  }
  // The records stay until overwritten or the array is replaced (make_room_for_fast_list_moves).
  thread->set_fast_list_moves_len(0);
}

void InstanceSwissTableBufferKlass::make_room_for_fast_list_moves(JavaThread* thread, TRAPS) {
  if (!thread->fast_list_moves().is_empty()) {
    // A safepoint whose threads all stopped after the last record (the id grows once they have)
    // ends every racing write.
    const bool safepoint_since = SafepointSynchronize::safepoint_id() > thread->fast_list_moves_counter();
    check_fast_list_moves(thread, !safepoint_since);
  }
  // A new array for the next records: a young object, whose elements the stores of the records reach
  // through the fast path of the collector's store barrier (an old array, reused, would take the slow
  // path for every element after each phase of the collector), and which lets the lists of the old
  // records go.
  objArrayOop moves = oopFactory::new_objArray(vmClasses::Object_klass(), 2 * fast_list_moves_capacity, CHECK);
  if (thread->fast_list_moves().is_empty()) {
    thread->fast_list_moves() = OopHandle(JavaThread::thread_oop_storage(), moves);
  } else {
    thread->fast_list_moves().replace(moves);
  }
  thread->set_fast_list_moves_len(0);
}

oop InstanceSwissTableBufferKlass::repair_fast_list(Handle list) {
  FastListMovesHandshake hs;
  Handshake::execute(&hs);
  oop found = nullptr;
  {
    // Moves that other threads record or drop meanwhile: a move missed here leaves the list throwing
    ThreadsListHandle tlh;
    for (JavaThread* jt : tlh) {
      objArrayOop moves = objArrayOop(jt->fast_list_moves().resolve());
      if (moves == nullptr) {
        continue;
      }
      const int n = MIN2(jt->fast_list_moves_len(), moves->length() / 2);
      for (int i = n - 1; i >= 0 && found == nullptr; i--) {
        if (moves->obj_at(2 * i) == list()) {
          oop s = moves->obj_at(2 * i + 1);
          if (s != nullptr && size_of(s) >= 0) {
            found = s;
          }
        }
      }
      if (found != nullptr) {
        break;
      }
    }
  }
  repair_if_replaced(list(), found);
  return slot0_tagged(list()) ? oop(HeapAccess<>::oop_load_at(list(), _fast_list.data_base)) : oop(nullptr);
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

JVM_ENTRY(jlong, FastArrayList_forwardTag(JNIEnv* env, jclass cls)) {
  return InstanceSwissTableBufferKlass::fast_list_forward_tag();
} JVM_END

JVM_ENTRY(jboolean, FastArrayList_storeForward0(JNIEnv* env, jclass cls, jobject list, jobject storage)) {
  Handle l(THREAD, JNIHandles::resolve_non_null(list));
  Handle s(THREAD, JNIHandles::resolve_non_null(storage));
  if (!InstanceSwissTableBufferKlass::store_fast_list_forward(thread, l(), s())) {
    InstanceSwissTableBufferKlass::make_room_for_fast_list_moves(thread, CHECK_false);
    const bool stored = InstanceSwissTableBufferKlass::store_fast_list_forward(thread, l(), s());
    guarantee(stored, "room for a FastArrayList move");
  }
  return true;
} JVM_END

JVM_ENTRY(jobject, FastArrayList_repairForward0(JNIEnv* env, jclass cls, jobject list)) {
  Handle l(THREAD, JNIHandles::resolve_non_null(list));
  return JNIHandles::make_local(THREAD, InstanceSwissTableBufferKlass::repair_fast_list(l));
} JVM_END

#define CC (char*)
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod java_util_FastArrayList_methods[] = {
  {CC "allocate",    CC "(I)Ljava/util/FastArrayList;", FN_PTR(FastArrayList_allocate)},
  {CC "dataBase",    CC "()I",                          FN_PTR(FastArrayList_dataBase)},
  {CC "maxCapacity", CC "()I",                          FN_PTR(FastArrayList_maxCapacity)},
  {CC "forwardTag",  CC "()J",                          FN_PTR(FastArrayList_forwardTag)},
  {CC "storeForward0", CC "(Ljava/util/FastArrayList;Ljava/util/FastArrayList;)Z", FN_PTR(FastArrayList_storeForward0)},
  {CC "storeForwardSlow0", CC "(Ljava/util/FastArrayList;Ljava/util/FastArrayList;)Z", FN_PTR(FastArrayList_storeForward0)},
  {CC "repairForward0", CC "(Ljava/util/FastArrayList;)Ljava/util/FastArrayList;", FN_PTR(FastArrayList_repairForward0)},
};

#undef CC
#undef FN_PTR

JVM_ENTRY(void, JVM_RegisterFastArrayListMethods(JNIEnv* env, jclass cls)) {
  ThreadToNativeFromVM ttnfv(thread);
  int ok = env->RegisterNatives(cls, java_util_FastArrayList_methods,
                                sizeof(java_util_FastArrayList_methods) / sizeof(JNINativeMethod));
  guarantee(ok == 0, "register java.util.FastArrayList natives");
} JVM_END
