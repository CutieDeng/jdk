/*
 * Copyright (c) 2015, 2023, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OPTO_INTRINSICNODE_HPP
#define SHARE_OPTO_INTRINSICNODE_HPP

#include "opto/node.hpp"
#include "opto/opcodes.hpp"
#include "opto/connode.hpp"


//----------------------PartialSubtypeCheckNode--------------------------------
// The 2nd slow-half of a subtype check.  Scan the subklass's 2ndary superklass
// array for an instance of the superklass.  Set a hidden internal cache on a
// hit (cache is checked with exposed code in gen_subtype_check()).  Return
// not zero for a miss or zero for a hit.
class PartialSubtypeCheckNode : public Node {
 public:
  PartialSubtypeCheckNode(Node* c, Node* sub, Node* super) : Node(c,sub,super) {}
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeRawPtr::BOTTOM; }
  virtual uint ideal_reg() const { return Op_RegP; }
};

//------------------------------StrIntrinsic-------------------------------
// Base class for Ideal nodes used in String intrinsic code.
class StrIntrinsicNode: public Node {
 public:
  // Possible encodings of the parameters passed to the string intrinsic.
  // 'L' stands for Latin1 and 'U' stands for UTF16. For example, 'LU' means that
  // the first string is Latin1 encoded and the second string is UTF16 encoded.
  // 'L' means that the single string is Latin1 encoded
  typedef enum ArgEncoding { LL, LU, UL, UU, L, U, none } ArgEnc;

 protected:
  // Encoding of strings. Used to select the right version of the intrinsic.
  const ArgEncoding _encoding;
  virtual uint size_of() const;

 public:
  StrIntrinsicNode(Node* control, Node* char_array_mem,
                   Node* s1, Node* c1, Node* s2, Node* c2, ArgEncoding encoding):
  Node(control, char_array_mem, s1, c1, s2, c2), _encoding(encoding) {
  }

  StrIntrinsicNode(Node* control, Node* char_array_mem,
                   Node* s1, Node* s2, Node* c, ArgEncoding encoding):
  Node(control, char_array_mem, s1, s2, c), _encoding(encoding) {
  }

  StrIntrinsicNode(Node* control, Node* char_array_mem,
                   Node* s1, Node* s2, ArgEncoding encoding):
  Node(control, char_array_mem, s1, s2), _encoding(encoding) {
  }

  virtual bool depends_only_on_test() const { return false; }
  virtual const TypePtr* adr_type() const { return TypeAryPtr::BYTES; }
  virtual uint match_edge(uint idx) const;
  virtual uint ideal_reg() const { return Op_RegI; }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual const Type* Value(PhaseGVN* phase) const;
  ArgEncoding encoding() const { return _encoding; }
};

//------------------------------StrComp-------------------------------------
class StrCompNode: public StrIntrinsicNode {
 public:
  StrCompNode(Node* control, Node* char_array_mem,
              Node* s1, Node* c1, Node* s2, Node* c2, ArgEncoding encoding):
  StrIntrinsicNode(control, char_array_mem, s1, c1, s2, c2, encoding) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::INT; }
};

//------------------------------StrEquals-------------------------------------
class StrEqualsNode: public StrIntrinsicNode {
 public:
  StrEqualsNode(Node* control, Node* char_array_mem,
                Node* s1, Node* s2, Node* c, ArgEncoding encoding):
  StrIntrinsicNode(control, char_array_mem, s1, s2, c, encoding) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::BOOL; }
};

//------------------------------StrIndexOf-------------------------------------
class StrIndexOfNode: public StrIntrinsicNode {
 public:
  StrIndexOfNode(Node* control, Node* char_array_mem,
                 Node* s1, Node* c1, Node* s2, Node* c2, ArgEncoding encoding):
  StrIntrinsicNode(control, char_array_mem, s1, c1, s2, c2, encoding) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::INT; }
};

//------------------------------StrIndexOfChar-------------------------------------
class StrIndexOfCharNode: public StrIntrinsicNode {
 public:
  StrIndexOfCharNode(Node* control, Node* char_array_mem,
                     Node* s1, Node* c1, Node* c, ArgEncoding encoding):
  StrIntrinsicNode(control, char_array_mem, s1, c1, c, encoding) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::INT; }
};

//--------------------------StrCompressedCopy-------------------------------
class StrCompressedCopyNode: public StrIntrinsicNode {
 public:
  StrCompressedCopyNode(Node* control, Node* arymem,
                        Node* s1, Node* s2, Node* c):
  StrIntrinsicNode(control, arymem, s1, s2, c, none) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::INT; }
  virtual const TypePtr* adr_type() const { return TypePtr::BOTTOM; }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
};

//--------------------------StrInflatedCopy---------------------------------
class StrInflatedCopyNode: public StrIntrinsicNode {
 public:
  StrInflatedCopyNode(Node* control, Node* arymem,
                      Node* s1, Node* s2, Node* c):
  StrIntrinsicNode(control, arymem, s1, s2, c, none) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return Type::MEMORY; }
  virtual const TypePtr* adr_type() const { return TypePtr::BOTTOM; }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
};

//------------------------------AryEq---------------------------------------
class AryEqNode: public StrIntrinsicNode {
 public:
  AryEqNode(Node* control, Node* char_array_mem,
            Node* s1, Node* s2, ArgEncoding encoding):
  StrIntrinsicNode(control, char_array_mem, s1, s2, encoding) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::BOOL; }
};

//------------------------------CountPositives------------------------------
class CountPositivesNode: public StrIntrinsicNode {
 public:
  CountPositivesNode(Node* control, Node* char_array_mem, Node* s1, Node* c1):
  StrIntrinsicNode(control, char_array_mem, s1, c1, none) {};
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return TypeInt::POS; }
};

//------------------------------VectorizedHashCodeNode----------------------
class VectorizedHashCodeNode: public Node {
 public:
  VectorizedHashCodeNode(Node* control, Node* ary_mem, Node* arg1, Node* cnt1, Node* result, Node* basic_type)
    : Node(control, ary_mem, arg1, cnt1, result, basic_type) {};
  virtual int Opcode() const;
  virtual bool depends_only_on_test() const { return false; }
  virtual const Type* bottom_type() const { return TypeInt::INT; }
  virtual const TypePtr* adr_type() const { return TypePtr::BOTTOM; }
  virtual uint match_edge(uint idx) const;
  virtual uint ideal_reg() const { return Op_RegI; }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual const Type* Value(PhaseGVN* phase) const;
};

//------------------------------EncodeISOArray--------------------------------
// encode char[] to byte[] in ISO_8859_1 or ASCII
class EncodeISOArrayNode: public Node {
  bool ascii;
 public:
  EncodeISOArrayNode(Node* control, Node* arymem, Node* s1, Node* s2, Node* c, bool ascii)
    : Node(control, arymem, s1, s2, c), ascii(ascii) {}

  bool is_ascii() { return ascii; }
  virtual int Opcode() const;
  virtual bool depends_only_on_test() const { return false; }
  virtual const Type* bottom_type() const { return TypeInt::INT; }
  virtual const TypePtr* adr_type() const { return TypePtr::BOTTOM; }
  virtual uint match_edge(uint idx) const;
  virtual uint ideal_reg() const { return Op_RegI; }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual const Type* Value(PhaseGVN* phase) const;
};

//-------------------------------DigitNode----------------------------------------
class DigitNode : public Node {
public:
  DigitNode(Node* control, Node *in1) : Node(control, in1) {}
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//------------------------------LowerCaseNode------------------------------------
class LowerCaseNode : public Node {
public:
  LowerCaseNode(Node* control, Node *in1) : Node(control, in1) {}
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//------------------------------UpperCaseNode------------------------------------
class UpperCaseNode : public Node {
public:
  UpperCaseNode(Node* control, Node *in1) : Node(control, in1) {}
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//------------------------------WhitespaceCode-----------------------------------
class WhitespaceNode : public Node {
public:
  WhitespaceNode(Node* control, Node *in1) : Node(control, in1) {}
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//------------------------------CopySign-----------------------------------------
class CopySignDNode : public Node {
 protected:
  CopySignDNode(Node* in1, Node* in2, Node* in3) : Node(0, in1, in2, in3) {}
 public:
  static CopySignDNode* make(PhaseGVN& gvn, Node* in1, Node* in2);
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeLong::DOUBLE; }
  virtual uint ideal_reg() const { return Op_RegD; }
};

class CopySignFNode : public Node {
 public:
  CopySignFNode(Node* in1, Node* in2) : Node(0, in1, in2) {}
  virtual int Opcode() const;
  const Type* bottom_type() const { return TypeLong::FLOAT; }
  virtual uint ideal_reg() const { return Op_RegF; }
};

//------------------------------Signum-------------------------------------------
class SignumDNode : public Node {
 protected:
  SignumDNode(Node* in1, Node* in2, Node* in3) : Node(0, in1, in2, in3) {}
 public:
  static SignumDNode* make(PhaseGVN& gvn, Node* in);
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return Type::DOUBLE; }
  virtual uint ideal_reg() const { return Op_RegD; }
};

class SignumFNode : public Node {
 protected:
  SignumFNode(Node* in1, Node* in2, Node* in3) : Node(0, in1, in2, in3) {}
 public:
  static SignumFNode* make(PhaseGVN& gvn, Node* in);
  virtual int Opcode() const;
  virtual const Type* bottom_type() const { return Type::FLOAT; }
  virtual uint ideal_reg() const { return Op_RegF; }
};

//----------------------------CompressBits/ExpandBits---------------------------
class CompressBitsNode : public TypeNode {
 public:
  CompressBitsNode(Node* in1, Node* in2, const Type* type) : TypeNode(type, 3) {
    init_req(1, in1);
    init_req(2, in2);
  }
  virtual int Opcode() const;
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual const Type* Value(PhaseGVN* phase) const;
  static jlong compress_bits(jlong src, jlong mask, int bit_size);
};

class ExpandBitsNode : public TypeNode {
 public:
  ExpandBitsNode(Node* in1, Node* in2, const Type* type) : TypeNode(type, 3) {
    init_req(1, in1);
    init_req(2, in2);
  }
  virtual int Opcode() const;
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual const Type* Value(PhaseGVN* phase) const;
  static jlong expand_bits(jlong src, jlong mask, int bit_size);
};

//---------- IsInfiniteFNode -----------------------------------------------------
class IsInfiniteFNode : public Node {
  public:
  IsInfiniteFNode(Node* in1) : Node(0, in1) {}
  virtual int   Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//---------- IsInfiniteDNode -----------------------------------------------------
class IsInfiniteDNode : public Node {
  public:
  IsInfiniteDNode(Node* in1) : Node(0, in1) {}
  virtual int   Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//---------- IsFiniteFNode -----------------------------------------------------
class IsFiniteFNode : public Node {
  public:
  IsFiniteFNode(Node* in1) : Node(0, in1) {}
  virtual int   Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//---------- IsFiniteDNode -----------------------------------------------------
class IsFiniteDNode : public Node {
  public:
  IsFiniteDNode(Node* in1) : Node(0, in1) {}
  virtual int   Opcode() const;
  const Type* bottom_type() const { return TypeInt::BOOL; }
  virtual uint ideal_reg() const { return Op_RegI; }
};

//------------------------------FastListForwardee-------------------------------
// The storage of a java.util.FastArrayList with slot 0 forwarding (generational ZGC,
// InstanceSwissTableBufferKlass::fast_list_forward_tag), or null: one load of slot 0 of the list
// (in(2)), whose value is the storage if it has the tag, through the collector's load barrier, and
// null if not. One load for both, so that the reference is never one that was read without the tag.
// Its memory (in(1)) is the state of a slice of its own, Compile::fast_list_forward_adr_type, where no
// store goes: only calls and memory barriers change it. So the stores of elements, which have the
// alias class of slot 0, neither order it nor make C2 load it again, and the operations of a list
// that forwards keep its storage in a register. This is sound because the tag, and the storage while
// it is set, change only in FastArrayList.storeForward0 (a call, between the MemBarCPUOrders of
// forwardTo): every other write of slot 0 tests the tag first (a CAS from an element, or a
// StoreFastListSlot0Node), and the collector keeps the tag and the reference when it heals the field
// (only the colors change, which the barrier handles). A store that a racing move overtakes, between
// its test and its store, replaces the storage until the VM puts it back
// (InstanceSwissTableBufferKlass::repair_fast_list): a value of this node from before still names it. Not a LoadNode: the alias class of its memory differs from that of its
// address, which the transformations of MemNodes rely on. Commoned by GVN like any node of the same
// inputs. Without a control input, so that C2 takes it out of loops; it may then come ahead of the
// test of its input for null (the forwardee of a forwardee, which is null without the tag): null for
// a null list as well (the match rule tests it).
class FastListForwardeeNode : public Node {
  const uint8_t _barrier_data;   // the collector's barrier data for the load, as a LoadNode's
 public:
  FastListForwardeeNode(Node* mem, Node* list, uint8_t barrier_data)
    : Node(nullptr, mem, list), _barrier_data(barrier_data) {}
  virtual int Opcode() const;
  virtual const TypePtr* adr_type() const;
  virtual const Type* bottom_type() const;
  virtual uint ideal_reg() const { return Op_RegP; }
  virtual uint match_edge(uint idx) const { return idx == 2; }
  virtual uint size_of() const { return sizeof(*this); }
  virtual Node* Ideal(PhaseGVN* phase, bool can_reshape);
  virtual const Type* Value(PhaseGVN* phase) const;
  uint8_t barrier_data() const { return _barrier_data; }
};

#endif // SHARE_OPTO_INTRINSICNODE_HPP
