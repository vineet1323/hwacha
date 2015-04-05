package hwacha

import Chisel._
import Node._

abstract trait SeqParameters extends UsesHwachaParameters
{
  val nRPorts = 3
  val szRPorts = log2Up(nRPorts)
  val maxWPortLatency = nRPorts +
    List(int_stages, imul_stages, fma_stages,
         fconv_stages, fcmp_stages).reduceLeft((x, y) => if (x > y) x else y)
  val szWPortLatency = log2Up(maxWPortLatency)
}

class Sequencer extends HwachaModule with LaneParameters
{
  val io = new Bundle {
    val op = new VXUIssueOpIO().flip
    val lane = new LaneOpIO
    val ack = new LaneAckIO().flip
    val debug = new Bundle {
      val valid = Vec.fill(nseq){Bool(OUTPUT)}
      val vlen = Vec.fill(nseq){UInt(width=szvlen)}.asOutput
      val e = Vec.fill(nseq){new SequencerEntry}.asOutput
      val head = UInt(OUTPUT, log2Up(nseq))
      val tail = UInt(OUTPUT, log2Up(nseq))
      val full = Bool(OUTPUT)
    }
  }

  class BuildSequencer
  {
    require(isPow2(nseq))

    // STATE
    val valid = Vec.fill(nseq){Reg(init=Bool(false))}
    val vlen = Vec.fill(nseq){Reg(UInt(width=szvlen))}
    val e = Vec.fill(nseq){Reg(new SequencerEntry)}

    val full = Reg(init = Bool(false))
    val head = Reg(init = UInt(0, log2Up(nseq)))
    val tail = Reg(init = UInt(0, log2Up(nseq)))

    // WIRES
    val h1 = head + UInt(1)
    val t0 = tail
    val t1 = tail + UInt(1)
    val t2 = tail + UInt(2)
    val t3 = tail + UInt(3)
    val t4 = tail + UInt(4)

    val update_head = Bool()
    val update_tail = Bool()
    val next_head = UInt(width = log2Up(nseq))
    val next_tail = UInt(width = log2Up(nseq))

    val update_haz = Vec.fill(nseq){Bool()}
    val next_raw = Vec.fill(nseq){Vec.fill(nseq){Bool()}}
    val next_war = Vec.fill(nseq){Vec.fill(nseq){Bool()}}
    val next_waw = Vec.fill(nseq){Vec.fill(nseq){Bool()}}

    // HELPERS
    def count = Cat(full && head === tail, tail - head)
    def empty = UInt(nseq) - count

    def set_head(n: UInt) = {
      update_head := Bool(true)
      next_head := n
    }
    def set_tail(n: UInt) = {
      update_tail := Bool(true)
      next_tail := n
    }
    def update_counter = {
      when (update_head) { head := next_head }
      when (update_tail) { tail := next_tail }
      when (update_head && !update_tail) {
        full := Bool(false)
      }
      when (update_tail) {
        full := next_head === next_tail
      }
    }

    def ready = {
      val a = io.op.bits.active
      (a.vint || a.vimul || a.vfma || a.vfcmp || a.vfconv) && (empty >= UInt(1)) ||
      (a.vidiv || a.vfdiv || a.vld || a.vst) && (empty >= UInt(2)) ||
      (a.vldx || a.vstx) && (empty >= UInt(3)) ||
      (a.vamo) && (empty >= UInt(4))
    }

    def set_entry(n: UInt) = {
      valid(n) := Bool(true)
      vlen(n) := io.op.bits.vlen
      update_haz(n) := Bool(true)
    }

    def clear_entry(n: UInt) = {
      valid(n) := Bool(false)
      e(n).active := e(0).active.clone().fromBits(Bits(0))
      e(n).reg.vs1.valid := Bool(false)
      e(n).reg.vs2.valid := Bool(false)
      e(n).reg.vs3.valid := Bool(false)
      e(n).reg.vd.valid := Bool(false)
      e(n).reg.vs1.scalar := Bool(false)
      e(n).reg.vs2.scalar := Bool(false)
      e(n).reg.vs3.scalar := Bool(false)
      e(n).reg.vd.scalar := Bool(false)
      e(n).base.vs1.valid := Bool(false)
      e(n).base.vs2.valid := Bool(false)
      e(n).base.vs3.valid := Bool(false)
      e(n).base.vd.valid := Bool(false)
      e(n).base.vs1.scalar := Bool(false)
      e(n).base.vs2.scalar := Bool(false)
      e(n).base.vs3.scalar := Bool(false)
      e(n).base.vd.scalar := Bool(false)
      for (i <- 0 until nseq) {
        e(n).raw(i) := Bool(false)
        e(n).war(i) := Bool(false)
        e(n).waw(i) := Bool(false)
      }
    }

    def retire_entry(n: UInt) = {
      clear_entry(n)
      for (i <- 0 until nseq) {
        update_haz(UInt(i)) := Bool(true)
        clear_raw_haz(UInt(i), n)
        clear_war_haz(UInt(i), n)
        clear_waw_haz(UInt(i), n)
      }
    }

    def header = {
      for (i <- 0 until nseq) {
        update_haz(i) := Bool(false)
        for  (j <- 0 until nseq) {
          next_raw(i)(j) := e(i).raw(j)
          next_war(i)(j) := e(i).war(j)
          next_waw(i)(j) := e(i).waw(j)
        }
      }
      update_head := Bool(false)
      update_tail := Bool(false)
      next_head := head
      next_tail := tail
    }

    def footer = {
      when (valid(head) && vlen(head) === UInt(0)) {
        retire_entry(head)
        set_head(h1)
      }

      update_counter

      for (i <- 0 until nseq) {
        when (update_haz(i)) {
          e(i).raw := next_raw(i)
          e(i).war := next_war(i)
          e(i).waw := next_waw(i)
        }
      }

      io.debug.valid := valid
      io.debug.vlen := vlen
      io.debug.e := e
      io.debug.head := head
      io.debug.tail := tail
      io.debug.full := full

      when (reset) {
        for (i <- 0 until nseq) {
          clear_entry(UInt(i))
        }
      }
    }

    val reg_vs1 = (reg: DecodedRegisters) => reg.vs1
    val reg_vs2 = (reg: DecodedRegisters) => reg.vs2
    val reg_vs3 = (reg: DecodedRegisters) => reg.vs3
    val reg_vd  = (reg: DecodedRegisters) => reg.vd

    def set_raw_haz(n: UInt, o: UInt) = { next_raw(n)(o) := Bool(true) }
    def set_war_haz(n: UInt, o: UInt) = { next_war(n)(o) := Bool(true) }
    def set_waw_haz(n: UInt, o: UInt) = { next_waw(n)(o) := Bool(true) }
    def clear_raw_haz(n: UInt, o: UInt) = { next_raw(n)(o) := Bool(false) }
    def clear_war_haz(n: UInt, o: UInt) = { next_war(n)(o) := Bool(false) }
    def clear_waw_haz(n: UInt, o: UInt) = { next_waw(n)(o) := Bool(false) }

    def issue_base_eq(ifn: DecodedRegisters=>RegInfo, sfn: DecodedRegisters=>RegInfo) =
      Vec((0 until nseq).map{ i =>
        valid(i) && sfn(e(i).base).valid && !sfn(e(i).base).scalar &&
        ifn(io.op.bits.reg).valid && !ifn(io.op.bits.reg).scalar &&
        sfn(e(i).base).id === ifn(io.op.bits.reg).id })
    val ivs1_evd_eq = issue_base_eq(reg_vs1, reg_vd)
    val ivs2_evd_eq = issue_base_eq(reg_vs2, reg_vd)
    val ivs3_evd_eq = issue_base_eq(reg_vs3, reg_vd)
    val ivd_evs1_eq = issue_base_eq(reg_vd, reg_vs1)
    val ivd_evs2_eq = issue_base_eq(reg_vd, reg_vs2)
    val ivd_evs3_eq = issue_base_eq(reg_vd, reg_vs3)
    val ivd_evd_eq  = issue_base_eq(reg_vd, reg_vd)

    def set_raw_hazs(n: UInt, eq: Vec[Bool]) = {
      for (i <- 0 until nseq) {
        when (eq(i)) {
          set_raw_haz(n, UInt(i))
        }
      }
    }
    def set_war_hazs(n: UInt) = {
      for (i <- 0 until nseq) {
        when (ivd_evs1_eq(i) || ivd_evs2_eq(i) || ivd_evs3_eq(i)) {
          set_war_haz(n, UInt(i))
        }
      }
    }
    def set_waw_hazs(n: UInt) = {
      for (i <- 0 until nseq) {
        when (ivd_evd_eq(i)) {
          set_waw_haz(n, UInt(i))
        }
      }
    }
    def set_raw_hazs_vs1(n: UInt) = set_raw_hazs(n, ivs1_evd_eq)
    def set_raw_hazs_vs2(n: UInt) = set_raw_hazs(n, ivs2_evd_eq)
    def set_raw_hazs_vs3(n: UInt) = set_raw_hazs(n, ivs3_evd_eq)
    def set_raw_hazs_vd(n: UInt) = set_raw_hazs(n, ivd_evd_eq)
    def set_war_hazs_vd(n: UInt) = set_war_hazs(n)
    def set_waw_hazs_vd(n: UInt) = set_waw_hazs(n)

    def set_vfu(n: UInt, afn: SequencerEntry=>Bool, fn: DecodedInstruction=>Bundle) = {
      afn(e(n)) := Bool(true)
      fn(e(n)) := fn(io.op.bits)
    }
    def set_viu(n: UInt)  = set_vfu(n, (e: SequencerEntry) => e.active.viu, (d: DecodedInstruction) => d.fn.viu)
    def set_vimu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vimu, (d: DecodedInstruction) => d.fn.vimu)
    def set_vidu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vidu, (d: DecodedInstruction) => d.fn.vidu)
    def set_vfmu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vfmu, (d: DecodedInstruction) => d.fn.vfmu)
    def set_vfdu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vfdu, (d: DecodedInstruction) => d.fn.vfdu)
    def set_vfcu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vfcu, (d: DecodedInstruction) => d.fn.vfcu)
    def set_vfvu(n: UInt) = set_vfu(n, (e: SequencerEntry) => e.active.vfvu, (d: DecodedInstruction) => d.fn.vfvu)

    def set_vmu(n: UInt, afn: SequencerEntry=>Bool) = {
      afn(e(n)) := Bool(true)
      e(n).fn.vmu := io.op.bits.fn.vmu
    }
    def set_vgu(n: UInt) = set_vmu(n, (e: SequencerEntry) => e.active.vgu)
    def set_vcu(n: UInt) = set_vmu(n, (e: SequencerEntry) => e.active.vcu)
    def set_vlu(n: UInt) = set_vmu(n, (e: SequencerEntry) => e.active.vlu)
    def set_vsu(n: UInt) = set_vmu(n, (e: SequencerEntry) => e.active.vsu)

    def set_vqu(n: UInt) = {
      assert(io.op.bits.active.vidiv || io.op.bits.active.vfdiv, "vqu should only be issued for idiv/fdiv")
      e(n).active.vqu := Bool(true)
      e(n).fn.vqu := io.op.bits.fn.vqu
    }

    def set_vs(n: UInt, vsfn: DecodedRegisters=>RegInfo, ssfn: ScalarRegisters=>Bits) = {
      when (vsfn(io.op.bits.reg).valid) {
        vsfn(e(n).reg) := vsfn(io.op.bits.reg)
        vsfn(e(n).base) := vsfn(io.op.bits.reg)
        when (vsfn(io.op.bits.reg).scalar) {
          ssfn(e(n).sreg) := ssfn(io.op.bits.sreg)
        }
      }
    }
    def set_vs1(n: UInt) = {
      set_vs(n, reg_vs1, (sreg: ScalarRegisters) => sreg.ss1)
      set_raw_hazs_vs1(n)
    }
    def set_vs2(n: UInt) = {
      set_vs(n, reg_vs2, (sreg: ScalarRegisters) => sreg.ss2)
      set_raw_hazs_vs2(n)
    }
    def set_vs3(n: UInt) = {
      set_vs(n, reg_vs3, (sreg: ScalarRegisters) => sreg.ss3)
      set_raw_hazs_vs3(n)
    }
    def set_vd_as_vs1(n: UInt) = {
      assert(!io.op.bits.reg.vd.valid || !io.op.bits.reg.vd.scalar, "set_vd_as_vs1: vd should always be vector")
      when (io.op.bits.reg.vd.valid) {
        e(n).reg.vs1 := io.op.bits.reg.vd
        e(n).base.vs1 := io.op.bits.reg.vd
      }
      set_raw_hazs_vd(n)
    }
    def set_vd(n: UInt) = {
      assert(!io.op.bits.reg.vd.valid || !io.op.bits.reg.vd.scalar, "set_vd: vd should always be vector")
      when (io.op.bits.reg.vd.valid) {
        e(n).reg.vd := io.op.bits.reg.vd
        e(n).base.vd := io.op.bits.reg.vd
      }
      set_war_hazs_vd(n)
      set_waw_hazs_vd(n)
    }

    def nports_list(fns: DecodedRegisters=>RegInfo*) =
      PopCount(fns.map{ fn => fn(io.op.bits.reg).valid && !fn(io.op.bits.reg).scalar })
    val nrports = nports_list(reg_vs1, reg_vs2, reg_vs3)
    val nrport_vs1 = nports_list(reg_vs1)
    val nrport_vs2 = nports_list(reg_vs2)
    val nrport_vd = nports_list(reg_vd)

    def set_ports(n: UInt, rports: UInt, wport: UInt) = {
      e(n).rports := rports
      e(n).wport := wport
    }
    def set_noports(n: UInt) = set_ports(n, UInt(0), UInt(0))
    def set_rport_vs1(n: UInt) = set_ports(n, nrport_vs1, UInt(0))
    def set_rport_vs2(n: UInt) = set_ports(n, nrport_vs2, UInt(0))
    def set_rport_vd(n: UInt) = set_ports(n, nrport_vd, UInt(0))
    def set_rports(n: UInt) = set_ports(n, nrports, UInt(0))
    def set_rwports(n: UInt, latency: Int) = set_ports(n, nrports, nrports + UInt(latency))

    def issue_vint = {
      set_entry(t0); set_viu(t0); set_vs1(t0); set_vs2(t0); set_vd(t0)
                      set_rwports(t0, int_stages)
      set_tail(t1)
    }
    def issue_vimul = {
      set_entry(t0); set_vimu(t0); set_vs1(t0); set_vs2(t0); set_vd(t0)
                      set_rwports(t0, imul_stages)
      set_tail(t1)
    }
    def issue_vidiv = {
      set_entry(t0); set_vqu(t0); set_vs1(t0); set_vs2(t0)
                      set_rports(t0); set_war_hazs_vd(t0); set_waw_hazs_vd(t0)
      set_entry(t1); set_vidu(t1); set_vd(t1)
                      set_noports(t1)
      set_tail(t2)
    }
    def issue_vfma = {
      set_entry(t0); set_vfmu(t0); set_vs1(t0); set_vs2(t0); set_vs3(t0); set_vd(t0)
                      set_rwports(t0, fma_stages)
      set_tail(t1)
    }
    def issue_vfdiv = {
      set_entry(t0); set_vqu(t0); set_vs1(t0); set_vs2(t0)
                      set_rports(t0); set_war_hazs_vd(t0); set_waw_hazs_vd(t0)
      set_entry(t1); set_vfdu(t1); set_vd(t1)
                      set_noports(t1)
      set_tail(t2)
    }
    def issue_vfcmp = {
      set_entry(t0); set_vfcu(t0); set_vs1(t0); set_vs2(t0); set_vd(t0)
                      set_rwports(t0, fcmp_stages)
      set_tail(t1)
    }
    def issue_vfconv = {
      set_entry(t0); set_vfvu(t0); set_vs1(t0); set_vd(t0)
                      set_rwports(t0, fconv_stages)
      set_tail(t1)
    }
    def issue_vamo = {
      set_entry(t0); set_vgu(t0); set_vs1(t0)
                      set_rport_vs1(t0)
      set_entry(t1); set_vcu(t1)
                      set_noports(t1); set_war_hazs_vd(t1); set_waw_hazs_vd(t1)
      set_entry(t2); set_vsu(t2); set_vs2(t2)
                      set_rport_vs2(t2); set_raw_haz(t2, t1)
      set_entry(t3); set_vlu(t3); set_vd(t3)
                      set_noports(t3)
      set_tail(t4)
    }
    def issue_vldx = {
      set_entry(t0); set_vgu(t0); set_vs2(t0)
                      set_rport_vs2(t0)
      set_entry(t1); set_vcu(t1)
                      set_noports(t1); set_war_hazs_vd(t1); set_waw_hazs_vd(t1)
      set_entry(t2); set_vlu(t2); set_vd(t2)
                      set_noports(t2)
      set_tail(t3)
    }
    def issue_vstx = {
      set_entry(t0); set_vgu(t0); set_vs2(t0)
                      set_rport_vs2(t0)
      set_entry(t1); set_vcu(t1)
                      set_noports(t1)
      set_entry(t2); set_vsu(t2); set_vd_as_vs1(t2)
                      set_rport_vd(t2); set_raw_haz(t2, t1)
      set_tail(t3)
    }
    def issue_vld = {
      set_entry(t0); set_vcu(t0)
                      set_noports(t0); set_war_hazs_vd(t0); set_waw_hazs_vd(t0)
      set_entry(t1); set_vlu(t1); set_vd(t1)
                      set_noports(t1)
      set_tail(t2)
    }
    def issue_vst = {
      set_entry(t0); set_vcu(t0)
                      set_noports(t0)
      set_entry(t1); set_vsu(t1); set_vd_as_vs1(t1)
                      set_rport_vd(t1); set_raw_haz(t1, t0)
      set_tail(t2)
    }
  }

  val seq = new BuildSequencer

  seq.header

  io.op.ready := seq.ready

  when (io.op.fire()) {
    when (io.op.bits.active.vint) { seq.issue_vint }
    when (io.op.bits.active.vimul) { seq.issue_vimul }
    when (io.op.bits.active.vidiv) { seq.issue_vidiv }
    when (io.op.bits.active.vfma) { seq.issue_vfma }
    when (io.op.bits.active.vfdiv) { seq.issue_vfdiv }
    when (io.op.bits.active.vfcmp) { seq.issue_vfcmp }
    when (io.op.bits.active.vfconv) { seq.issue_vfconv }
    when (io.op.bits.active.vamo) { seq.issue_vamo }
    when (io.op.bits.active.vldx) { seq.issue_vldx }
    when (io.op.bits.active.vstx) { seq.issue_vstx }
    when (io.op.bits.active.vld) { seq.issue_vld }
    when (io.op.bits.active.vst) { seq.issue_vst }
  }

  // TODO: this is here to make sure things get vlenantiated
  
  //val temp_valid = io.op.valid
  val temp_valid = Bool(false)

  for (i <- 0 until nbanks) {
    io.lane.bank(i).sram.read.valid := temp_valid
    io.lane.bank(i).sram.read.bits.pred := io.op.bits.vlen
    io.lane.bank(i).sram.read.bits.addr := io.op.bits.vlen

    io.lane.bank(i).sram.write.valid := temp_valid
    io.lane.bank(i).sram.write.bits.pred := io.op.bits.vlen
    io.lane.bank(i).sram.write.bits.addr := io.op.bits.vlen
    io.lane.bank(i).sram.write.bits.selg := io.op.bits.vlen(7)
    io.lane.bank(i).sram.write.bits.wsel := io.op.bits.vlen

    for (j <- 0 until nFFRPorts) {
      io.lane.bank(i).ff.read(j).valid := temp_valid
      io.lane.bank(i).ff.read(j).bits.pred := io.op.bits.vlen
      io.lane.bank(i).ff.read(j).bits.addr := io.op.bits.vlen
    }

    io.lane.bank(i).ff.write.valid := temp_valid
    io.lane.bank(i).ff.write.bits.pred := io.op.bits.vlen
    io.lane.bank(i).ff.write.bits.addr := io.op.bits.vlen
    io.lane.bank(i).ff.write.bits.selg := io.op.bits.vlen(7)
    io.lane.bank(i).ff.write.bits.wsel := io.op.bits.vlen

    io.lane.bank(i).opl.valid := temp_valid
    io.lane.bank(i).opl.bits.pred := io.op.bits.vlen
    io.lane.bank(i).opl.bits.global.latch := io.op.bits.vlen
    io.lane.bank(i).opl.bits.global.selff := io.op.bits.vlen
    io.lane.bank(i).opl.bits.global.en := io.op.bits.vlen
    io.lane.bank(i).opl.bits.local.latch := io.op.bits.vlen
    io.lane.bank(i).opl.bits.local.selff := io.op.bits.vlen

    io.lane.bank(i).brq.valid := temp_valid
    io.lane.bank(i).brq.bits.pred := io.op.bits.vlen
    io.lane.bank(i).brq.bits.selff := io.op.bits.vlen(7)
    io.lane.bank(i).brq.bits.zero := io.op.bits.vlen(7)

    io.lane.bank(i).viu.valid := temp_valid
    io.lane.bank(i).viu.bits.pred := io.op.bits.vlen
    io.lane.bank(i).viu.bits.fn := new VIUFn().fromBits(io.op.bits.vlen)
    io.lane.bank(i).viu.bits.eidx := io.op.bits.vlen
  }

  io.lane.vqu.valid := temp_valid
  io.lane.vqu.bits.pred := io.op.bits.vlen
  io.lane.vqu.bits.fn := new VQUFn().fromBits(io.op.bits.vlen)

  io.lane.vgu.valid := temp_valid
  io.lane.vgu.bits.pred := io.op.bits.vlen
  io.lane.vgu.bits.fn := new VMUFn().fromBits(io.op.bits.vlen)

  io.lane.vimu.valid := temp_valid
  io.lane.vimu.bits.pred := io.op.bits.vlen
  io.lane.vimu.bits.fn := new VIMUFn().fromBits(io.op.bits.vlen)

  io.lane.vidu.valid := temp_valid
  io.lane.vidu.bits.pred := io.op.bits.vlen
  io.lane.vidu.bits.fn := new VIDUFn().fromBits(io.op.bits.vlen)
  io.lane.vidu.bits.bank := io.op.bits.vlen
  io.lane.vidu.bits.addr := io.op.bits.vlen
  io.lane.vidu.bits.selff := io.op.bits.vlen(8)

  io.lane.vfmu0.valid := temp_valid
  io.lane.vfmu0.bits.pred := io.op.bits.vlen
  io.lane.vfmu0.bits.fn := new VFMUFn().fromBits(io.op.bits.vlen)

  io.lane.vfmu1.valid := temp_valid
  io.lane.vfmu1.bits.pred := io.op.bits.vlen
  io.lane.vfmu1.bits.fn := new VFMUFn().fromBits(io.op.bits.vlen)

  io.lane.vfdu.valid := temp_valid
  io.lane.vfdu.bits.pred := io.op.bits.vlen
  io.lane.vfdu.bits.fn := new VFDUFn().fromBits(io.op.bits.vlen)
  io.lane.vfdu.bits.bank := io.op.bits.vlen
  io.lane.vfdu.bits.addr := io.op.bits.vlen
  io.lane.vfdu.bits.selff := io.op.bits.vlen(8)

  io.lane.vfcu.valid := temp_valid
  io.lane.vfcu.bits.pred := io.op.bits.vlen
  io.lane.vfcu.bits.fn := new VFCUFn().fromBits(io.op.bits.vlen)

  io.lane.vfvu.valid := temp_valid
  io.lane.vfvu.bits.pred := io.op.bits.vlen
  io.lane.vfvu.bits.fn := new VFVUFn().fromBits(io.op.bits.vlen)

  seq.footer
}
