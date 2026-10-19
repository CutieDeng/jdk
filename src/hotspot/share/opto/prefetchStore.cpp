/*
 * Copyright (c) 2026, cutie-deng. All rights reserved.
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
 */

#include "precompiled.hpp"
#include "libadt/vectset.hpp"
#include "opto/block.hpp"
#include "opto/machnode.hpp"
#include "runtime/vm_version.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/resourceHash.hpp"

#ifdef AARCH64

// UsePrefetchStore
//
// A load that misses on a line another core holds gets the line in a shared
// state; a later store to the same address must then upgrade it to unique,
// which is a second coherence transaction.  A write prefetch issued before the
// load asks for the line in a unique state, so the load and the store are
// served by one transaction.  When the line is already unique in this core's
// cache the prefetch only costs an issue slot.
//
// The pass runs after GCM.  A load is paired with a later store when their
// memory operands have the same base and index values, scale and
// displacement.  Accesses between the two do not matter: the prefetch is only
// a hint, and the load and the store stay where they are.  The store must be in
// the load's block, or in a block reached from it along a chain of edges that
// each leave a block whose other successors are uncommon (null checks, range
// checks and other guards), into a block with no other predecessor.  Along
// such a chain a cache line gets at most one prefetch.  The post-allocation
// scheduler may separate the prefetch from the load, but a precedence edge
// keeps it in front.

static const int max_tracked_loads = 64;

// Primitive accesses only: oop accesses have GC barriers in between.
static bool is_prefetch_store_load(const MachNode* m) {
  switch (m->ideal_Opcode()) {
  case Op_LoadB:
  case Op_LoadUB:
  case Op_LoadS:
  case Op_LoadUS:
  case Op_LoadI:
  case Op_LoadL:
  case Op_LoadF:
  case Op_LoadD:
  case Op_ConvI2L:  // (ConvI2L (LoadX mem))
  case Op_AndL:     // (AndL (ConvI2L (LoadI mem)) mask)
    break;
  default:
    return false;
  }
  const MachOper* mem = m->memory_operand();
  return mem != nullptr && mem != (MachOper*)-1;
}

static bool is_prefetch_store_store(const MachNode* m) {
  switch (m->ideal_Opcode()) {
  case Op_StoreB:
  case Op_StoreC:
  case Op_StoreI:
  case Op_StoreL:
  case Op_StoreF:
  case Op_StoreD:
    break;
  default:
    return false;
  }
  const MachOper* mem = m->memory_operand();
  return mem != nullptr && mem != (MachOper*)-1;
}

// Whether two address inputs hold the same value.  Besides the same node,
// this accepts two copies of one AddP: the matcher gives each memory access
// its own copy of an address computation it cannot fold into the memory
// operand, as for the base register of LDAR and STLR.
static bool same_address_value(const Node* a, const Node* b) {
  if (a == b) {
    return true;
  }
  if (a == nullptr || b == nullptr || !a->is_Mach() || !b->is_Mach()) {
    return false;
  }
  const MachNode* ma = a->as_Mach();
  const MachNode* mb = b->as_Mach();
  if (ma->ideal_Opcode() != Op_AddP || mb->ideal_Opcode() != Op_AddP ||
      ma->rule() != mb->rule() || ma->req() != mb->req() ||
      ma->num_opnds() != mb->num_opnds()) {
    return false;
  }
  for (uint i = 0; i < ma->req(); i++) {
    if (ma->in(i) != mb->in(i)) {
      return false;
    }
  }
  // Register operands are covered by the inputs; compare the immediates.
  for (uint i = 1; i < ma->num_opnds(); i++) {
    if (ma->_opnds[i]->num_edges() == 0 &&
        ma->_opnds[i]->constant() != mb->_opnds[i]->constant()) {
      return false;
    }
  }
  return true;
}

class PrefetchStoreAccess {
public:
  MachNode* _node;
  Node*     _base;
  Node*     _index;
  int       _scale;
  int       _disp;
  int       _opcode;  // operand kind: sign-extended or plain index

  PrefetchStoreAccess() : _node(nullptr), _base(nullptr), _index(nullptr),
                          _scale(0), _disp(0), _opcode(0) {}
  explicit PrefetchStoreAccess(MachNode* m) : _node(m) {
    const MachOper* mem = m->memory_inputs(_base, _index);
    _scale  = mem->scale();
    _disp   = mem->constant_disp();
    _opcode = mem->opcode();
  }

  // Same base and index values; the displacements may differ.
  bool same_base(const PrefetchStoreAccess& other) const {
    return same_address_value(_base, other._base) &&
           same_address_value(_index, other._index) &&
           _scale == other._scale &&
           (_index == nullptr || _opcode == other._opcode);
  }

  bool same_address(const PrefetchStoreAccess& other) const {
    return same_base(other) && _disp == other._disp;
  }
};

// Whether a prefetch already issued for one of 'prefetched' requests the line
// of 'access'.  Without the alignment of the base this is decided on the
// displacements alone: within one line size of each other.  An unrolled loop
// over an array then prefetches once per line instead of once per element.
static bool covered(const GrowableArray<PrefetchStoreAccess>& prefetched,
                    const PrefetchStoreAccess& access) {
  const int line = VM_Version::dcache_line_size();
  for (int i = 0; i < prefetched.length(); i++) {
    const PrefetchStoreAccess& p = prefetched.at(i);
    if (p.same_base(access) && ABS(p._disp - access._disp) < line) {
      return true;
    }
  }
  return false;
}

MachPrefetchStoreNode::MachPrefetchStoreNode(const MachNode* load) {
  const MachOper* mem = load->memory_operand();
  _num_opnds = 2;
  _opnds = _opnd_array;
  // Operand 0 is the result slot of a MachNode; this node has no result.
  _opnd_array[0] = mem->clone();
  _opnd_array[1] = mem->clone();
  add_req(nullptr);  // no control: placed by the pass, after GCM
  int idx = load->operand_index(mem);
  assert(idx > 0, "memory operand has address inputs");
  for (uint i = 0; i < mem->num_edges(); i++) {
    add_req(load->in(idx + i));
  }
}

// Loads not yet paired, in program order so that the first match is the
// earliest load of an address, and the addresses already prefetched.
class PrefetchStoreState : public ResourceObj {
public:
  GrowableArray<PrefetchStoreAccess> _loads;
  GrowableArray<PrefetchStoreAccess> _prefetched;
};

// The block whose state continues into 'block': its only predecessor, when
// every other successor of that predecessor is uncommon.
static Block* prefetch_store_carrier(PhaseCFG* cfg, const Block* block) {
  // The entry block's head is the Start node, whose input 1 is Root.
  if (block->num_preds() != 2 || block->head()->is_Start()) {
    return nullptr;
  }
  Block* pred = cfg->get_block_for_node(block->pred(1));
  if (pred == nullptr) {
    return nullptr;
  }
  for (uint i = 0; i < pred->_num_succs; i++) {
    Block* succ = pred->_succs[i];
    if (succ != block && !cfg->is_uncommon(succ)) {
      return nullptr;
    }
  }
  return pred;
}

typedef ResourceHashtable<const Block*, PrefetchStoreState*, 1031> PrefetchStoreStates;

// Pairs the loads and stores of 'block', starting from the state its carrier
// ended with, and records the state it ends with.
static void prefetch_store_scan(PhaseCFG* cfg, Block* block, PrefetchStoreStates& end_states,
                                Node_List& prefetch_loads) {
  PrefetchStoreState* state = new PrefetchStoreState();
  GrowableArray<PrefetchStoreAccess>& loads = state->_loads;
  GrowableArray<PrefetchStoreAccess>& prefetched = state->_prefetched;
  Block* carrier = prefetch_store_carrier(cfg, block);
  PrefetchStoreState** from = carrier != nullptr ? end_states.get(carrier) : nullptr;
  if (from != nullptr) {
    loads.appendAll(&(*from)->_loads);
    prefetched.appendAll(&(*from)->_prefetched);
  }

  for (uint j = 0; j < block->number_of_nodes(); j++) {
    Node* n = block->get_node(j);
    if (!n->is_Mach()) {
      continue;
    }
    MachNode* m = n->as_Mach();
    if (is_prefetch_store_load(m)) {
      PrefetchStoreAccess load(m);
      if (loads.length() < max_tracked_loads && !covered(prefetched, load)) {
        loads.append(load);
      }
      continue;
    }
    if (!is_prefetch_store_store(m)) {
      continue;
    }
    PrefetchStoreAccess store(m);
    if (covered(prefetched, store)) {
      continue;
    }
    for (int k = 0; k < loads.length(); k++) {
      if (!loads.at(k).same_address(store)) {
        continue;
      }
      prefetch_loads.push(loads.at(k)._node);
      prefetched.append(store);
      // Retire every candidate for this address; the rest keep their order.
      for (int r = loads.length() - 1; r >= k; r--) {
        if (loads.at(r).same_address(store)) {
          loads.remove_at(r);
        }
      }
      break;
    }
  }
  end_states.put(block, state);
}

void PhaseCFG::insert_prefetch_store() {
  PrefetchStoreStates end_states;
  Node_List prefetch_loads;
  for (uint i = 0; i < number_of_blocks(); i++) {
    // Scan the not yet scanned carriers of this block first, outermost first.
    // A chain of single-predecessor blocks can only close on itself in
    // unreachable code; 'seen' stops the walk there.
    GrowableArray<Block*> chain;
    VectorSet seen;
    for (Block* b = get_block(i); b != nullptr && end_states.get(b) == nullptr &&
                                  !seen.test_set(b->head()->_idx);
         b = prefetch_store_carrier(this, b)) {
      chain.push(b);
    }
    for (int c = chain.length() - 1; c >= 0; c--) {
      prefetch_store_scan(this, chain.at(c), end_states, prefetch_loads);
    }
  }

  // A load carried into two blocks (both successors uncommon) can be picked
  // twice; it gets one prefetch.
  VectorSet done;
  for (uint i = 0; i < prefetch_loads.size(); i++) {
    MachNode* load = prefetch_loads.at(i)->as_Mach();
    if (done.test_set(load->_idx)) {
      continue;
    }
    Block* block = get_block_for_node(load);
    MachPrefetchStoreNode* prefetch = new MachPrefetchStoreNode(load);
    block->insert_node(prefetch, block->find_node(load));
    map_node_to_block(prefetch, block);
    // Keeps the prefetch ahead of the load and gives it a user.
    load->add_prec(prefetch);
  }
}

#endif // AARCH64
