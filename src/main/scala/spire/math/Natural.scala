package spire.math

import scala.annotation.tailrec

import Natural._

// TODO: almost none of this recursion is tailrec. the first goal was
// correctness, but once that's achieved we need to focus on efficiency.
// using a similar "private mutable" strategy that :: and ListBuffer
// use in Scala, we should be able to efficiently build Digit chains
// in a tail-recursive way.

sealed trait Natural {
  lhs =>

  def digit: UInt

  def toList: List[UInt] = {
    @tailrec
    def recur(next: Natural, sofar: List[UInt]): List[UInt] = next match {
      case End(d) => d :: sofar
      case Digit(d, tail) => recur(tail, d :: sofar)
    }
    recur(this, Nil)
  }

  def reversed: Natural = {
    @tailrec
    def recur(next: Natural, sofar: Natural): Natural = next match {
      case End(d) => Digit(d, sofar)
      case Digit(d, tail) => recur(tail, Digit(d, sofar))
    }
    this match {
      case Digit(d, tail) => recur(tail, End(d))
      case _ => this
    }
  }

  def trim: Natural = {
    @tailrec
    def recur(next: Natural): Natural = {
      next match {
        case Digit(n, tail) =>
          if (n == UInt(0)) recur(tail) else next
        case End(n) =>
          next
      }
    }
    recur(reversed).reversed
  }

  def toInt: Int = digit.toInt & 0x7fffffff

  def toLong: Long = this match {
    case End(d) => d.toLong
    case Digit(d, tail) => (tail.toInt << 32L) + d.toLong
  }

  // TODO: consider using BigInt(toString) instead
  def toBigInt: BigInt = this match {
    case End(d) => BigInt(d.toLong)
    case Digit(d, tail) => (tail.toBigInt << 32) + BigInt(d.toLong)
  }

  // calculate 9 digits at a time using /%
  override def toString: String = {
    @tailrec def recur(next: Natural, s: String): String = {
      next match {
        case End(d) =>
          d.toInt.toString + s
        case Digit(d, tail) =>
          val (q, r) = next /% Natural.denom
          if (q == UInt(0))
            r.digit.toInt.toString + s
          else
            recur(q, "%09d%s" format (r.digit.toInt, s))
      }
    }
    recur(this, "")
  }

  def toRepr: String = toList.mkString("Natural(", ", ", ")")

  def isZero: Boolean = {
    @tailrec
    def recur(next: Natural): Boolean = next match {
      case End(n) =>
        n == UInt(0)
      case Digit(n, tail) =>
        if (n == UInt(0)) recur(tail) else false
    }
    recur(this)
  }

  def powerOfTwo: Int = {
    import java.lang.Integer.highestOneBit

    def test(n: UInt): Int = {
      if ((n.signed & -n.signed) != n.signed) return -1
      var i = 0
      while (i < 32 && (n >>> i) != 0) i += 1
      i - 1
    }

    @tailrec
    def recur(next: Natural, shift: Int, bit: Int): Int = next match {
      case End(n) =>
        val t = test(n)
        if (t < 0) bit else if (bit < 0) shift + t else -1
      case Digit(n, tail) =>
        val t = test(n)
        if (t < 0)
          recur(tail, shift + 32, bit)
        else if (bit < 0)
          recur(tail, shift + 32, shift + t)
        else
          -1
    }
    recur(this, 0, -1)
  }

  def compare(rhs: UInt): Int = this match {
    case End(d) =>
      if (d < rhs) -1 else if (d > rhs) 1 else 0
    case Digit(d, tail) =>
      if (d > rhs || !tail.isZero) 1 else if (d < rhs) -1 else 0
  }

  def compare(rhs: Natural): Int = {
    def cmp(a: UInt, b: UInt, c: Int): Int =
      if (a < b) -1 else if (a > b) 1 else c

    @tailrec
    def recur(lhs: Natural, rhs: Natural, d: Int): Int = lhs match {
      case End(ld) => rhs match {
        case End(rd) => cmp(ld, rd, d)
        case _:Digit => -rhs.compare(ld)
      }
      case Digit(ld, ltail) => rhs match {
        case End(rd) => lhs.compare(rd)
        case Digit(rd, rtail) => recur(ltail, rtail, cmp(ld, rd, d))
      }
    }

    recur(lhs, rhs, 0)
  }

  final override def equals(rhs: Any): Boolean = rhs match {
    case rhs: Natural => (lhs compare rhs) == 0
    case rhs: UInt => (lhs compare rhs) == 0
    case _ => false
  }

  def <(rhs: Natural): Boolean = (lhs compare rhs) < 0
  def <=(rhs: Natural): Boolean = (lhs compare rhs) <= 0
  def >(rhs: Natural): Boolean = (lhs compare rhs) > 0
  def >=(rhs: Natural): Boolean = (lhs compare rhs) >= 0

  def <(r: UInt): Boolean = (lhs compare r) < 0
  def <=(r: UInt): Boolean = (lhs compare r) <= 0
  def >(r: UInt): Boolean = (lhs compare r) > 0
  def >=(r: UInt): Boolean = (lhs compare r) >= 0

  // implemented in Digit and End
  def +(rd: UInt): Natural
  def -(rd: UInt): Natural
  def *(rd: UInt): Natural
  def /(rd: UInt): Natural
  def %(rd: UInt): Natural
  def /%(rd: UInt): (Natural, Natural)

  def +(rhs: Natural): Natural = {
    def recur(left: Natural, right: Natural, carry: Long): Natural = left match {
      case End(ld) => right match {
        case End(rd) =>
          Natural(ld.toLong + rd.toLong + carry)
          
        case Digit(rd, rtail) =>
          val t = ld.toLong + rd.toLong + carry
          Digit(UInt(t), rtail + UInt(t >> 32))
      }

      case Digit(ld, ltail) => right match {
        case End(rd) =>
          val t = ld.toLong + rd.toLong + carry
          Digit(UInt(t), ltail + UInt(t >> 32))
          
        case Digit(rd, rtail) =>
          val t = ld.toLong + rd.toLong + carry
          Digit(UInt(t), recur(ltail, rtail, t >> 32))
      }
    }
    recur(lhs, rhs, 0L)
  }

  def -(rhs: Natural): Natural = {
    def recur(left: Natural, right: Natural, carry: Long): Natural = left match {
      case End(ld) => right match {
        case End(rd) =>
          Natural(ld.toLong - rd.toLong - carry)
          
        case Digit(rd, rtail) =>
          val t = ld.toLong - rd.toLong - carry
          val tl = rtail - UInt(-(t >> 32))
          if (tl.isInstanceOf[End] && tl.digit == UInt(0))
            End(t)
          else
            Digit(UInt(t), tl)
      }

      case Digit(ld, ltail) => right match {
        case End(rd) =>
          val t = ld.toLong - rd.toLong - carry
          val tl = ltail - UInt(-(t >> 32))
          if (tl.isInstanceOf[End] && tl.digit == UInt(0))
            End(t)
          else
            Digit(UInt(t), tl)
          
        case Digit(rd, rtail) =>
          val t = ld.toLong - rd.toLong - carry
          val tl = recur(ltail, rtail, -(t >> 32))
          if (tl.isInstanceOf[End] && tl.digit == UInt(0))
            End(UInt(t))
          else
            Digit(UInt(t), tl)
      }
    }
    recur(lhs, rhs, 0L)
  }

  def *(rhs: Natural): Natural = lhs match {
    case End(ld) => rhs * ld
    case Digit(ld, ltail) => rhs match {
      case End(rd) => lhs * rd
      case Digit(rd, rtail) =>
          Natural(ld.toLong * rd.toLong) +
            lhs * rd +
            rhs * ld +
            Digit(UInt(0), Digit(UInt(0), ltail * rtail))
    }
  }

  // TODO: ugh, sorry...
  def /(rhs: Natural): Natural = {
    rhs match {
      case End(rd) => lhs / rd
  
      case Digit(rd, rtail) => rhs.compare(UInt(1)) match {
        case -1 => sys.error("/ by zero")
        case 0 => lhs
        case 1 =>
          val p = rhs.powerOfTwo
          if (p >= 0)
            lhs >> p
          else
            sys.error("todo: multi-digit denominators")
      }
    }
  }

  def <<(n: Int): Natural = {
    val m: Int = n & 0x1f
    def recur(next: Natural, carry: Long): Natural = next match {
      case End(d) =>
        Natural((d.toLong << m) | carry)
      case Digit(d, tail) =>
        val t = (d.toLong << m) | carry
        Digit(UInt(t), recur(tail, t >> 32))
    }
    val num = recur(this, 0L)
    (0 until n / 32).foldLeft(num)((n, _) => Digit(UInt(0), n))
  }

  def chop(n: Int): Natural = {
    @tailrec def recur(next: Natural, n: Int): Natural = if (n <= 0) {
      next
    } else {
      next match {
        case End(d) => End(UInt(0))
        case Digit(d, tail) => recur(tail, n - 1)
      }
    }
    recur(this, n)
  }

  def >>(n: Int): Natural = {
    val m: Int = n & 0x1f
    def recur(next: Natural, carry: Long): Natural = next match {
      case End(d) =>
        Natural((d.toLong >> m) | carry)
      case Digit(d, tail) =>
        val t = (d.toLong | carry) << (32 - m)
        Digit(UInt(t >> 32), recur(tail, t & 0xffffffffL))
    }
    recur(chop(n / 32).reversed, 0L).reversed
  }

  def |(rhs: Natural): Natural = lhs match {
    case End(ld) => rhs match {
      case End(rd) => End(ld | rd)
      case Digit(rd, rtail) => Digit(ld | rd, rtail)
    }
    case Digit(ld, ltail) => rhs match {
      case End(rd) => Digit(ld | rd, ltail)
      case Digit(rd, rtail) => Digit(ld | rd, ltail | rtail)
    }
  }

  def &(rhs: Natural): Natural = {
    def and(lhs: Natural, rhs: Natural): Natural = lhs match {
      case End(ld) => rhs match {
        case End(rd) => End(ld & rd)
        case Digit(rd, rtail) => End(ld & rd)
      }
      case Digit(ld, ltail) => rhs match {
        case End(rd) => End(ld & rd)
        case Digit(rd, rtail) => Digit(ld & rd, and(ltail, rtail))
      }
    }
    and(lhs, rhs).trim
  }

  def ^(rhs: Natural): Natural = {
    def xor(lhs: Natural, rhs: Natural): Natural = lhs match {
      case End(ld) => rhs match {
        case End(rd) => End(ld ^ rd)
        case Digit(rd, rtail) => Digit(ld ^ rd, rtail)
      }
      case Digit(ld, ltail) => rhs match {
        case End(rd) => Digit(ld ^ rd, ltail)
        case Digit(rd, rtail) => Digit(ld ^ rd, ltail ^ rtail)
      }
    }
    xor(lhs, rhs).trim
  }
}

object Natural {
  private[math] final val denom = UInt(1000000000)

  // required in big-endian order
  def apply(us: UInt*): Natural = {
    if (us.isEmpty) sys.error("invalid arguments")
    us.tail.foldLeft(End(us.head): Natural)((n, u) => Digit(u, n))
  }

  def apply(n: Long): Natural = if (n < 0L)
    sys.error("negative numbers not allowed: %s" format n)
  else if (n <= 0xffffffffL)
    End(UInt(n))
  else
    Digit(UInt(n), End(UInt(n >> 32)))

  def apply(n: BigInt): Natural = if (n < 0)
    sys.error("negative numbers not allowd: %s" format n)
  else if (n < 0xffffffffL)
    End(UInt(n.toLong))
  else
    Digit(UInt((n & 0xffffffffL).toLong), apply(n >> 32))

  case class Digit(private var d: UInt, private[math] var tl: Natural) extends Natural {
    def digit: UInt = d
    def tail: Natural = tl

    def +(n: UInt): Natural = if (n == UInt(0)) {
      this
    } else {
      val t = d.toLong + n.toLong
      Digit(UInt(t), tail + UInt(t >> 32))
    }

    def -(n: UInt): Natural = if (n == UInt(0)) {
      this
    } else {
      val t = d.toLong - n.toLong
      Digit(UInt(t), tail - UInt(-(t >> 32)))
    }

    def *(n: UInt): Natural = if (n == UInt(0))
      End(n)
    else if (n == UInt(1))
      this
    else
      Natural(d.toLong * n.toLong) + Digit(UInt(0), tl * n)

    def /(n: UInt): Natural = (this /% n)._1

    def %(n: UInt): Natural = (this /% n)._2

    def /%(n: UInt): (Natural, Natural) = {
      @tailrec
      def recur(next: Natural, rem: UInt, sofar: Natural): (Natural, Natural) = {
        val t: Long = (rem.toLong << 32) + next.digit.toLong
        val q: Long = t / n.toLong
        val r: Long = t % n.toLong
        next match {
          case End(d) => (Digit(q, sofar), End(r))
          case Digit(d, tail) => recur(tail, r, Digit(q, sofar))
        }
      }

      if (n == UInt(0)) {
        sys.error("/ by zero")
      } else if (n == UInt(1)) {
        (this, Natural(UInt(0)))
      } else {
        reversed match {
          case Digit(d, tail) =>
            val q = d / n
            val r = d % n
            recur(tail, r, End(q))
          case _ =>
            sys.error("bug in reversed")
        }
      }
    }
  }

  case class End(private var d: UInt) extends Natural {
    def digit: UInt = d

    def +(n: UInt): Natural = if (n == UInt(0)) {
      this
    } else {
      val t = d.toLong + n.toLong
      if (t <= 0xffffffffL)
        End(UInt(t))
      else
        Digit(UInt(t), End(UInt(1)))
    }

    def -(n: UInt): Natural = if (n == UInt(0)) {
      this
    } else {
      val t = d.toLong - n.toLong
      if (t >= 0L)
        End(UInt(t.toInt))
      else
        sys.error("illegal subtraction: %s %s" format (this, n))
    }

    def *(n: UInt): Natural = if (n == UInt(0))
      End(n)
    else if (n == UInt(1))
      this
    else
      Natural(d.toLong * n.toLong)

    def /(n: UInt): Natural = if (n == UInt(0))
      sys.error("/ by zero")
    else
      End(d / n)

    def %(n: UInt): Natural = if (n == UInt(0))
      sys.error("/ by zero")
    else
      End(d % n)

    def /%(n: UInt): (Natural, Natural) = (this / n, this % n)
  }
}
