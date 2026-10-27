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

// Folding of java.util.FastArrayList (design B) in the young collections of Serial and Parallel
// (-XX:+FastListFolding). When its elements outgrow it, a list O moves them to a larger list object,
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
class FastArrayListFolding : AllStatic {
  static volatile size_t _folds;

public:
  // Whether obj, of klass k, is a FastArrayList and folding is on: the test that every copied
  // object takes, inlined where the copy is, with the klass that the copy then sizes the object
  // with (size_given_klass), so that the klass is decoded once. It tests first the slow-path bit of
  // the layout helper, which the size computation tests anyway and which a FastArrayList has
  // (variable sized), so that other objects skip the loads of the flag and of the klass of
  // FastArrayList. (In young collections that copy many small objects, an out-of-line test cost
  // Serial 5 percent of their time, an inlined test of the flag and the klass 2 percent.)
  static inline bool is_candidate(oop obj, const Klass* k);
  // The storage of obj if obj is a FastArrayList that forwards and may be folded, else null. m is
  // the mark of obj, which is not forwarded.
  static inline oop storage_to_fold(oop obj, markWord m);
  // Whether target can take the identity of a list with mark m: target is unlocked, and not both
  // have an identity hash.
  static inline bool can_take_identity(markWord m, oop target);
  // Gives target the identity hash of a list with mark m, if it has one. Only for a target whose
  // mark no other thread writes during the collection.
  static inline void transfer_hash(markWord m, oop target);
  static void count_fold();
  // Logs the number of folds of the collection that ends (gc, debug) and resets it.
  static void report(const char* collection);
};

#endif // SHARE_GC_SHARED_FASTARRAYLISTFOLDING_HPP
