/* ===================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ========================================================== */
package akka.instrumentation

import org.aspectj.lang.annotation._
import org.aspectj.lang.ProceedingJoinPoint
import akka.actor._
import akka.dispatch.{ Envelope, MessageDispatcher }
import kamon.trace._
import kamon.metrics.{ ActorMetrics, Metrics }
import kamon.Kamon
import kamon.trace.TraceContext
import kamon.metrics.ActorMetrics.ActorMetricRecorder

@Aspect("perthis(actorCellCreation(*, *, *, *, *))")
class BehaviourInvokeTracing {
  var path: String = _
  var actorMetrics: Option[ActorMetricRecorder] = None

  @Pointcut("execution(akka.actor.ActorCell.new(..)) && args(system, ref, props, dispatcher, parent)")
  def actorCellCreation(system: ActorSystem, ref: ActorRef, props: Props, dispatcher: MessageDispatcher, parent: ActorRef): Unit = {}

  @After("actorCellCreation(system, ref, props, dispatcher, parent)")
  def afterCreation(system: ActorSystem, ref: ActorRef, props: Props, dispatcher: MessageDispatcher, parent: ActorRef): Unit = {
    val metricsExtension = Kamon(Metrics)(system)

    path = ref.path.elements.mkString("/")
    actorMetrics = metricsExtension.register(path, ActorMetrics)
  }

  @Pointcut("(execution(* akka.actor.ActorCell.invoke(*)) || execution(* akka.routing.RoutedActorCell.sendMessage(*))) && this(cell) && args(envelope)")
  def invokingActorBehaviourAtActorCell(cell: ActorCell, envelope: Envelope) = {}

  @Around("invokingActorBehaviourAtActorCell(cell, envelope)")
  def aroundBehaviourInvoke(pjp: ProceedingJoinPoint, cell: ActorCell, envelope: Envelope): Unit = {
    val timestampBeforeProcessing = System.nanoTime()
    val contextAndTimestamp = envelope.asInstanceOf[TraceContextAware]

    TraceRecorder.withContext(contextAndTimestamp.traceContext) {
      pjp.proceed()
    }

    actorMetrics.map { am ⇒
      am.processingTime.record(System.nanoTime() - timestampBeforeProcessing)
      am.timeInMailbox.record(timestampBeforeProcessing - contextAndTimestamp.captureMark)
      am.mailboxSize.record(cell.numberOfMessages)
    }
  }

  @Pointcut("execution(* akka.actor.ActorCell.stop()) && this(cell)")
  def actorStop(cell: Cell): Unit = {}

  @After("actorStop(cell)")
  def afterStop(cell: Cell): Unit = {
    actorMetrics.map(p ⇒ Kamon(Metrics)(cell.system).unregister(path, ActorMetrics))
  }
}

@Aspect
class EnvelopeTraceContextMixin {

  @DeclareMixin("akka.dispatch.Envelope")
  def mixin: TraceContextAware = new TraceContextAware {}

  @Pointcut("execution(akka.dispatch.Envelope.new(..)) && this(ctx)")
  def envelopeCreation(ctx: TraceContextAware): Unit = {}

  @After("envelopeCreation(ctx)")
  def afterEnvelopeCreation(ctx: TraceContextAware): Unit = {
    // Necessary to force the initialization of ContextAware at the moment of creation.
    ctx.traceContext
  }
}