//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------
// Author: Christopher Celio
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
// Rename FreeList
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import boom.common._
import boom.util._

/**
 * IO bundle for accessing the free list
 *
 * @param numPregs number of physical registers
 * @param plWidth pipeline width (dispatch group size)
 */
class FreeListIo(
  val numPregs: Int,
  val plWidth: Int)
(implicit p: Parameters) extends BoomBundle
{
  val pregSz = log2Ceil(numPregs)

  val req_preg_vals = Input(Vec(plWidth, Bool()))
  val req_pregs     = Output(Vec(plWidth, UInt(pregSz.W))) // TODO: Confirm functionality

  // committed and newly freed register
  val enq_vals      = Input(Vec(plWidth, Bool()))
  val enq_pregs     = Input(Vec(plWidth, UInt(pregSz.W)))

  // do we have space to service incoming requests? (per inst granularity)
  val can_allocate  = Output(Vec(plWidth, Bool()))

  // handle branches (save copy of freelist on branch, merge on mispredict)
  val ren_br_vals   = Input(Vec(plWidth, Bool()))
  val ren_br_tags   = Input(Vec(plWidth, UInt(brTagSz.W)))

  // handle mispredicts
  val br_mispredict_val = Input(Bool())
  val br_mispredict_tag = Input(UInt(brTagSz.W))

  // rollback (on exceptions)
  val rollback_wens  = Input(Vec(plWidth, Bool()))
  val rollback_pdsts = Input(Vec(plWidth, UInt(pregSz.W)))

  // or...
  // TODO there are TWO free-list IOs now, based on constants. What is the best way to handle these two designs?
  // perhaps freelist.scala, and instantiate which-ever one I want?
  // TODO naming is inconsistent
  // TODO combine with rollback, whatever?
  val flush_pipeline = Input(Bool())
  val com_wens       = Input(Vec(plWidth, Bool()))
  val com_uops       = Input(Vec(plWidth, new MicroOp()))

  val debug = Output(new DebugFreeListIO(numPregs))
}

class DebugFreeListIO(val numPregs: Int) extends Bundle
{
   val freelist = Bits(numPregs.W)
   val isprlist = Bits(numPregs.W)
}

/**
 * Provide a fixed set of renamed destination registers
 * i.e., it doesn't matter if a previous UOP needs a pdst or not
 * this prevents a dependency chain from existing between UOPs when trying to
 * compute a pdst to give away (as well as computing if an available free
 * register exists.
 * NOTE: we never give out p0 -- that is the "unitialized" state of the map-table,
 * and the pipeline will give any reader of p0 0x0 as read data.
 *
 * @param numPregs number of physical registers
 * @param plWidth pipeline width (dispatch group size)
 */
class RenameFreeListHelper(
   numPregs: Int,
   plWidth: Int)
   (implicit p: Parameters) extends BoomModule
{
  val io = IO(new FreeListIo(numPregs, plWidth))

  // ** FREE LIST TABLE ** //
  val free_list = RegInit(~(1.U(numPregs.W)))

  // track all allocations that have occurred since branch passed by
  // can quickly reset pipeline on branch mispredict
  val allocation_lists = Reg(Vec(maxBrCount, Bits(numPregs.W)))

  // TODO why is this a Vec? can I do this all on one bit-vector?
  val enq_mask = Wire(Vec(plWidth, Bits(numPregs.W)))

  // ------------------------------------------
  // find new,free physical registers

  val requested_pregs_oh_array = Array.fill(plWidth,numPregs){false.B}
  val requested_pregs_oh       = Wire(Vec(plWidth, Bits(numPregs.W)))
  val requested_pregs          = Wire(Vec(plWidth, UInt(log2Ceil(numPregs).W)))
  var allocated                = Wire(Vec(plWidth, Bool())) // did each inst get allocated a register?

  // init
  for (w <- 0 until plWidth) {
    allocated(w) := false.B
  }


  // don't give out p0
  for (i <- 1 until numPregs) {
    val next_allocated = Wire(Vec(plWidth, Bool()))
    var can_allocate = free_list(i)

    for (w <- 0 until plWidth) {
       requested_pregs_oh_array(w)(i) = can_allocate && !allocated(w)

       next_allocated(w) := can_allocate | allocated(w)
       can_allocate = can_allocate && allocated(w)
    }

    allocated = next_allocated
  }

  for (w <- 0 until plWidth) {
    requested_pregs_oh(w) := VecInit(requested_pregs_oh_array(w)).asUInt
    requested_pregs(w) := PriorityEncoder(requested_pregs_oh(w))
  }


  // ------------------------------------------
  // Calculate next Free List
  val req_free_list = Wire(Bits(numPregs.W))
  val enq_free_list = Wire(Bits(numPregs.W))
  req_free_list := free_list
  enq_free_list := free_list

  // ** Set requested PREG to "Not Free" ** //

  // bit vector of newly allocated physical registers
  var just_allocated_mask = 0.U(numPregs.W)

  // track which allocation_lists just got cleared out by a branch,
  // to enforce a write priority to allocation_lists()
  val br_cleared = Wire(Vec(maxBrCount, Bool()))
  for (i <- 0 until maxBrCount) { br_cleared(i) := false.B }

  for (w <- plWidth-1 to 0 by -1) {
    // When branching, start a fresh copy of the allocation_list
    // but don't forget to bypass in the allocations within our bundle
    when (io.ren_br_vals(w)) {
      allocation_lists(io.ren_br_tags(w)) := just_allocated_mask
      br_cleared(io.ren_br_tags(w)) := true.B
    }

    // check that we both request a register and were able to allocate a register
    just_allocated_mask = Mux(io.req_preg_vals(w) && allocated(w),
                            requested_pregs_oh(w) | just_allocated_mask,
                            just_allocated_mask)
  }

  for (i <- 0 until maxBrCount) {
    when (!br_cleared(i)) {
      allocation_lists(i) := allocation_lists(i) | just_allocated_mask
    }
  }


  // ** Set enqueued PREG to "Free" ** //
  for (w <- 0 until plWidth) {
    enq_mask(w) := 0.U(numPregs.W)
    when (io.enq_vals(w)) {
      enq_mask(w) := 1.U << io.enq_pregs(w)
    } .elsewhen (io.rollback_wens(w)) {
      enq_mask(w) := 1.U << io.rollback_pdsts(w)
    }
  }


  // Update the Free List
  when (!io.br_mispredict_val) {
    free_list := (free_list & ~(just_allocated_mask)) | (enq_mask.reduce(_|_))
  }

  // Handle Misprediction
  //merge misspeculated allocation_list with free_list
  val allocation_list = Wire(Bits(numPregs.W))
  allocation_list := allocation_lists(io.br_mispredict_tag)

  when (io.br_mispredict_val) {
    // include newly freed register as well!
    free_list := allocation_list | free_list | (enq_mask.reduce(_|_))

    // set other branch allocation_lists to zero where allocation_list(j) == 1...
    for (i <- 0 until maxBrCount) {
      allocation_lists(i) := allocation_lists(i) & ~allocation_list
    }
  }


  // OPTIONALLY: handle single-cycle resets
  // Committed Free List tracks what the free list is at the commit point,
  // allowing for a single-cycle reset of the rename state on a pipeline flush.
  if (enableCommitMapTable) {
    val committed_free_list = RegInit(~(1.U(numPregs.W)))

    val com_mask = Wire(Vec(plWidth,   Bits(numPregs.W)))
    val stale_mask = Wire(Vec(plWidth, Bits(numPregs.W)))
    for (w <- 0 until plWidth) {
      com_mask(w) :=   0.U(numPregs.W)
      stale_mask(w) := 0.U(numPregs.W)
      when (io.com_wens(w)) {
        com_mask(w) := 1.U << io.com_uops(w).pdst
        stale_mask(w) := 1.U << io.com_uops(w).stale_pdst
      }
    }

    committed_free_list := (committed_free_list & ~(com_mask.reduce(_|_))) | stale_mask.reduce(_|_)

    when (io.flush_pipeline) {
      free_list := committed_free_list
    }
    io.debug.isprlist := committed_free_list
  }

  // ** SET OUTPUTS ** //
  io.req_pregs := requested_pregs
  io.can_allocate := allocated
  io.debug.freelist := free_list
}

/**
 * Rename free list that keeps track of what registers are free in the physical
 * register file
 *
 * @param plWidth pipeline width (dispatch group size)
 * @param rtype type of register the free list is operating on
 * @param numPregs number of physical registers
 */
class RenameFreeList(
  plWidth: Int,
  rtype: BigInt,
  numPregs: Int)
  (implicit p: Parameters) extends BoomModule
{
  val pregSz = log2Ceil(numPregs)

  val io = IO(new Bundle {
    // Inputs
    val brinfo           = Input(new BrResolutionInfo())
    val kill             = Input(Bool())

    val ren_will_fire    = Input(Vec(plWidth, Bool()))
    val ren_uops         = Input(Vec(plWidth, new MicroOp()))
    val ren_br_vals      = Input(Vec(plWidth, Bool()))


    val com_valids       = Input(Vec(plWidth, Bool()))
    val com_uops         = Input(Vec(plWidth, new MicroOp()))
    val com_rbk_valids   = Input(Vec(plWidth, Bool()))


    val flush_pipeline   = Input(Bool())

    // Outputs
    val can_allocate     = Output(Vec(plWidth, Bool()))
    val req_pregs        = Output(Vec(plWidth, UInt(pregSz.W)))

    val debug            = Output(new DebugFreeListIO(numPregs))
    val debug_rob_empty  = Input(Bool())
  })

  val freelist = Module(new RenameFreeListHelper(
    numPregs,
    plWidth))

  freelist.io.br_mispredict_val := io.brinfo.mispredict
  freelist.io.br_mispredict_tag := io.brinfo.tag
  freelist.io.flush_pipeline    := io.flush_pipeline

  for (w <- 0 until plWidth) {
    freelist.io.req_preg_vals(w)  := !io.kill &&
                                     io.ren_will_fire(w) &&
                                     io.ren_uops(w).ldst_val &&
                                     io.ren_uops(w).dst_rtype === rtype.U

    freelist.io.enq_vals(w)       := io.com_valids(w) &&
                                       io.com_uops(w).dst_rtype === rtype.U &&
                                       (io.com_uops(w).stale_pdst =/= 0.U || rtype.U === RT_FLT)
    freelist.io.enq_pregs(w)      := io.com_uops(w).stale_pdst

    freelist.io.ren_br_vals(w)    := io.ren_br_vals(w)
    freelist.io.ren_br_tags(w)    := io.ren_uops(w).br_tag


    freelist.io.rollback_wens(w)  := io.com_rbk_valids(w) &&
                                     (io.com_uops(w).pdst =/= 0.U || rtype.U === RT_FLT) &&
                                     io.com_uops(w).dst_rtype === rtype.U
    freelist.io.rollback_pdsts(w) := io.com_uops(w).pdst

    freelist.io.com_wens(w)       := io.com_valids(w) &&
                                     (io.com_uops(w).pdst =/= 0.U || rtype.U === RT_FLT) &&
                                     io.com_uops(w).dst_rtype === rtype.U
    freelist.io.com_uops(w)       := io.com_uops(w)

    if (rtype == RT_FIX.litValue) {
      // x0 is a special-case and should not be renamed
      io.req_pregs(w) := Mux(io.ren_uops(w).ldst === 0.U, 0.U, freelist.io.req_pregs(w))
    } else {
      io.req_pregs(w) := freelist.io.req_pregs(w)
    }
  }

  io.can_allocate := freelist.io.can_allocate
  io.debug := freelist.io.debug

  when (io.debug_rob_empty) {
    assert (PopCount(freelist.io.debug.freelist) >= (numPregs - 32).U,
      "[freelist] We're leaking physical registers")
  }
}
