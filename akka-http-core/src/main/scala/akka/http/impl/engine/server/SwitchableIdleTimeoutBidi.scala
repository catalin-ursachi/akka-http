/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import akka.NotUsed
import akka.annotation.InternalApi
import akka.http.impl.engine.HttpIdleTimeoutException
import akka.http.impl.engine.server.SwitchableIdleTimeoutBidi._
import akka.http.scaladsl.model.headers.CustomHeader
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.Timers.GraphStageLogicTimer
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.BidiFlow.fromGraph
import akka.stream.stage._

import scala.concurrent.duration.Duration.Infinite
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * A version of the [[akka.stream.impl.Timers.IdleTimeoutBidi]] that accepts, in one direction, elements that can
 * disable or re-enable the timeout.
 */

/** INTERNAL API */
@InternalApi
final class SwitchableIdleTimeoutBidi[I, O](val defaultTimeout: Duration) extends GraphStage[BidiShape[OrTimeoutSwitch[I], I, O, O]] {
  val in1 = Inlet[OrTimeoutSwitch[I]]("in1")
  val in2 = Inlet[O]("in2")
  val out1 = Outlet[I]("out1")
  val out2 = Outlet[O]("out2")
  val shape = BidiShape(in1, out1, in2, out2)

  override def initialAttributes = DefaultAttributes.idleTimeoutBidi

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
    var currentTimeout: Duration = defaultTimeout

    setHandlers(in1, out1, new InboundHandler)
    setHandlers(in2, out2, new OutboundHandler)

    final override def onTimer(key: Any): Unit = {
      currentTimeout match {
        case fd: FiniteDuration ⇒
          failStage(new HttpIdleTimeoutException(
            "HTTP idle-timeout encountered, no bytes passed in the last " + fd +
              ". This is configurable by akka.http.server.idle-timeout.", fd))
        case _ ⇒
      }
    }

    override def preStart(): Unit = resetTimer()

    private def resetTimer(): Unit = {
      currentTimeout match {
        case fd: FiniteDuration ⇒
          scheduleOnce(GraphStageLogicTimer, fd)
        case _: Infinite ⇒
          cancelTimer(GraphStageLogicTimer)
      }
    }

    class InboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        grab(in1) match {
          case Left(SetTimeout(timeout)) ⇒
            currentTimeout = timeout
            resetTimer()
            pull(in1)
          case Left(ResetTimeout) ⇒
            currentTimeout = defaultTimeout
            resetTimer()
            pull(in1)
          case Right(elem) ⇒
            resetTimer()
            push(out1, elem)
        }
      }

      override def onPull(): Unit = pull(in1)

      override def onUpstreamFinish(): Unit = complete(out1)

      override def onDownstreamFinish(): Unit = cancel(in1)
    }

    class OutboundHandler extends InHandler with OutHandler {
      override def onPush(): Unit = {
        resetTimer()
        push(out2, grab(in2))
      }

      override def onPull(): Unit = pull(in2)

      override def onUpstreamFinish(): Unit = complete(out2)

      override def onDownstreamFinish(): Unit = cancel(in2)
    }
  }

  override def toString = "SwitchableIdleTimeoutBidi"
}

object SwitchableIdleTimeoutBidi {
  def bidirectionalSettableIdleTimeout[I, O](defaultTimeout: Duration): BidiFlow[OrTimeoutSwitch[I], I, O, O, NotUsed] =
    fromGraph(new SwitchableIdleTimeoutBidi(defaultTimeout))

  type OrTimeoutSwitch[Other] = Either[TimeoutSwitch, Other]

  sealed trait TimeoutSwitch

  case class SetTimeout(timeout: Duration) extends TimeoutSwitch
  case object ResetTimeout extends TimeoutSwitch

  private[http] case class SetTimeoutHeader(timeout: Duration) extends CustomHeader {
    override def name(): String = "SetTimeout"
    override def value(): String = timeout.toString
    override def renderInRequests(): Boolean = false
    override def renderInResponses(): Boolean = false
  }
}
