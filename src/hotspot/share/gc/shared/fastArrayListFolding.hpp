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

#ifndef SHARE_GC_SHARED_FASTARRAYLISTFOLDING_HPP
#define SHARE_GC_SHARED_FASTARRAYLISTFOLDING_HPP

#include "memory/allStatic.hpp"
#include "oops/markWord.hpp"
#include "oops/oopsHierarchy.hpp"
#include "utilities/growableArray.hpp"

// Folding of java.util.FastArrayList (design B) in the young collections of Serial, Parallel and G1
// and in the full collections of Serial and G1 (-XX:+FastListFolding). When its elements outgrow it, a list O moves them to a larger list object,
// its storage S, and forwards to it: O.size is FORWARDED and O.fwd is S. A young collection that
// would copy O copies S instead (or takes S where it already is: in the old generation, or copied
// already), and installs that copy as the forwardee of O. A young collection visits every
// reference to a young object, so that every reference to O then refers to the copy of S, which
// becomes the list: its size is the number of elements, its fwd is null, and it takes the identity
// hash of O. O is not copied and is reclaimed with the young generation.
// A list is copied as usual, and folds in a later young collection, while it is locked, when both O
// and S have an identity hash, or when S cannot be copied (promotion failure). A storage that the
// elements left (RETIRED) is never folded: only stale iterators reach it, and they must fail.
// Compiled code may keep a forwarding test of O (its size or the word of cap and fwd) in a register
// across a safepoint and then take the forwarding path on the folded list, whose fwd is null;
// FastArrayList.forwardee() then answers the list itself.
//
// Young collections: Serial and Parallel (DefNewGeneration, PSPromotionManager) and G1 (young and
// mixed: G1ParScanThreadState, for an O in the collection set). The thread that folds a hashed O
// copies S itself, with the hash of O in the copy before the copy is published (with release);
// that thread may increment the age in the mark of its copy without a CAS, so that no other thread
// may write that mark. If another thread copied S first, O folds into that copy if the copy has the
// hash of O (that thread folds O too; read after an acquire), and is copied as usual if not (S was
// reached through another reference). G1 does not fold into a storage that is humongous or in an
// optional region, nor into an old storage outside the collection set in a concurrent start pause.
// Full collections: Serial (MarkSweep) and G1 (G1FullCollector) fold every O, young or old: phase 1
// decides it and tags O (size fast_list_folding); phase 2 gives O no space; when the new addresses
// are known O is forwarded to the new address of S, so that phase 3 adjusts every reference to O
// (strong and weak roots, references, the preserved mark of O, which carries its identity hash);
// then O is made a dead object for phase 4. Parallel's full collection does not fold. Generational
// ZGC folds when it relocates O: gc/z/zFastArrayListFolding.inline.hpp.
class FastArrayListFolding : AllStatic {
  static volatile size_t _folds;

public:
  // A list that a Serial full collection folds: the object and its size.
  struct SerialFold {
    oop    list;
    size_t size;
  };

private:
  static GrowableArray<SerialFold>* _serial_full_folds;

public:
  // Whether obj, of klass k, is a FastArrayList and folding is on: the test that every copied
  // object takes, inlined where the copy is, with the klass that the copy then sizes the object
  // with (size_given_klass), so that the klass is decoded once. It tests first the slow-path bit of
  // the layout helper, which the size computation tests anyway and which a FastArrayList has
  // (variable sized), so that other objects skip the loads of the flag and of the klass of
  // FastArrayList. (In young collections that copy many small objects, an out-of-line test cost
  // Serial 5 percent of their time, an inlined test of the flag and the klass 2 percent.)
  static inline bool is_candidate(oop obj, const Klass* k);
  // The same test for the marking of a full collection, which reads the kind of the klass of every
  // object it marks anyway (for stack chunks): kind is the kind of k, read once for both tests.
  static inline bool is_candidate_of_kind(const Klass* k, int kind);
  // The storage of obj if obj is a FastArrayList that forwards and may be folded, else null. m is
  // the mark of obj, which is not forwarded.
  static inline oop storage_to_fold(oop obj, markWord m);
  // Whether target can take the identity of a list with mark m: target is unlocked, and not both
  // have an identity hash (but for the same one: a copy that a folding thread made, which another
  // thread found).
  static inline bool can_take_identity(markWord m, oop target);
  static inline bool can_take_identity(markWord m, markWord target_mark);
  // Whether copy, the copy of a storage that another thread made in a young collection, has the
  // identity hash of a hashed list with mark m: that thread folds the list as well. The caller
  // acquired the forwarding of the storage; the copying thread published it with release after the
  // hash went into it, and may still increment the age in the same word, which keeps the hash.
  static inline bool carries_hash(markWord m, oop copy);
  // The size and the storage (fwd) of a FastArrayList.
  static inline jint size_of(oop list);
  static inline void set_size(oop list, jint size);
  static inline oop storage_of(oop list);

  // Whether obj, of klass k, is a list that the full collection in progress folds (phase 1 tagged it).
  static inline bool full_is_folded(oop obj, const Klass* k);
  // Serial full collection (MarkSweep): phase 1 decides (mark is the mark of obj before marking);
  // phase 2 records the folded lists; after phase 2, forward them to their storage; after phase 3,
  // make them dead objects.
  static void serial_full_mark(oop obj, markWord mark);
  static void serial_full_record(oop obj, size_t size);
  static void serial_full_forward();
  static void serial_full_make_dead();
  // Gives target the identity hash of a list with mark m, if it has one. Only for a target whose
  // mark no other thread writes during the collection.
  static inline void transfer_hash(markWord m, oop target);
  static void count_fold();
  // Logs the number of folds of the collection that ends (gc, debug) and resets it.
  static void report(const char* collection);
};

#endif // SHARE_GC_SHARED_FASTARRAYLISTFOLDING_HPP
