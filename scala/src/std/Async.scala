package okay.effects.scala.std

import okay.effects.scala.*
import java.util.concurrent.{CountDownLatch, ScheduledThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

/** ASYNC: an answer that comes later. An operation gives a callback to whoever will have the answer; the handler
 * keeps the rest of the program and returns. The callback, whenever and on whatever thread it is called, resumes
 * the rest. No threads are held waiting: asynchrony is callbacks and continuations, nothing else. */
enum Async[X]:
  case Await[X](register: (X => Unit) => Unit) extends Async[X]

object Async:
  /** the answer `register` will be called back with */
  def await[X, R[_]](register: (X => Unit) => Unit)(using In[Async, R]): X ! R = perform[Async](Await(register))

  private lazy val timer =
    ScheduledThreadPoolExecutor(1, runnable =>
      val thread = Thread(runnable, "okay-timer")
      thread.setDaemon(true)
      thread
    )

  def sleep[R[_]](millis: Long)(using In[Async, R]): Unit ! R =
    await(resume => { timer.schedule((() => resume(())): Runnable, millis, TimeUnit.MILLISECONDS); () })

  /** the program started; `done` is called with its value, now or later. Returns as soon as the program waits. */
  def start[A](p: A ! Async)(done: A => Unit): Unit =
    val turns = Turns()
    val handler = Handler.general[Async, Pure, A, Unit](a => pure(done(a)))(
      [X] => (op: Async[X], k: X => Unit ! Pure) => op match
        case Await(register) => pure(register(x => turns.take(() => run(k(x))))))
    turns.take(() => run(handle(p, handler)))

  /** The turns of one started program, one at a time and in a loop: a callback called at once, or from another
   * thread, or a million times in a row, neither nests nor overlaps. */
  private final class Turns:
    private val ready = java.util.ArrayDeque[() => Unit]()
    private var running = false

    def take(turn: () => Unit): Unit =
      val mine = synchronized { ready.add(turn); !running && { running = true; true } }
      if mine then
        var next = synchronized(ready.poll())
        while next != null do
          try next()
          catch
            case e: Throwable =>
              synchronized { running = false }
              throw e
          next = synchronized { val turn = ready.poll(); if turn == null then running = false; turn }

  /** both programs at once; their values when both have come */
  def both[A, B, R[_]](a: A ! Async, b: B ! Async)(using In[Async, R]): (A, B) ! R = await: resume =>
    val lock = Object()
    var left = Option.empty[A]
    var right = Option.empty[B]
    start(a)(x => lock.synchronized { left = Some(x); right }.foreach(y => resume((x, y))))
    start(b)(y => lock.synchronized { right = Some(y); left }.foreach(x => resume((x, y))))

  /** all the programs at once; their values, in the programs' order */
  def all[A, R[_]](ps: List[A ! Async])(using In[Async, R]): List[A] ! R = await: resume =>
    start(ps.foldRight(pure[List[A], Async](Nil))((p, rest) => both[A, List[A], Async](p, rest).map(_ :: _)))(resume)

  /** both programs at once; the value that comes first — the other's is dropped */
  def race[A, R[_]](a: A ! Async, b: A ! Async)(using In[Async, R]): A ! R = await: resume =>
    val won = AtomicBoolean(false)
    start(a)(x => if won.compareAndSet(false, true) then resume(x))
    start(b)(x => if won.compareAndSet(false, true) then resume(x))

  /** the future's outcome, awaited */
  def fromFuture[A, R[_]](future: => Future[A])(using ExecutionContext, In[Async, R]): Try[A] ! R =
    await(resume => future.onComplete(resume))

  def toFuture[A](p: A ! Async): Future[A] =
    val promise = Promise[A]()
    start(p)(promise.success)
    promise.future

  /** the program's value, this thread waiting for it: the edge of a program that is not asynchronous itself */
  def runBlocking[A](p: A ! Async): A =
    val latch = CountDownLatch(1)
    var result = Option.empty[A]
    start(p)(a => { result = Some(a); latch.countDown() })
    latch.await()
    result.get
