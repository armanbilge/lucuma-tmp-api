// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.schema

import lucuma.odb.api.model.{AsterismModel, Event, Existence, InputError, ObservationModel, Sharing}
import lucuma.odb.api.model.AsterismModel.AsterismEvent
import lucuma.odb.api.model.ObservationModel.ObservationEvent
import lucuma.odb.api.model.Event.EditType.{Created, Updated}
import lucuma.odb.api.repo.{LookupSupport, OdbRepo, Tables, TableState}
import lucuma.core.model.{Asterism, Observation, Target}
import cats.data.{EitherT, State}
import cats.effect.Effect
import cats.effect.implicits._
import cats.syntax.all._
import sangria.macros.derive._
import sangria.marshalling.circe._
import sangria.schema._
import monocle.state.all._

import scala.concurrent.Future


/**
 *
 */
trait SharingMutation {

  import AsterismSchema.AsterismType
  import ObservationSchema.ObservationIdType
  import TargetSchema.TargetIdType

  import syntax.inputobjecttype._

  val InputObjectTargetObservationLinks: InputObjectType[Sharing.TargetObservationLinks] =
    deriveInputObjectType[Sharing.TargetObservationLinks](
      InputObjectTypeName("TargetObservationLinks"),
      InputObjectTypeDescription("Target and the observations with which they are associated")
    )

  val ArgumentTargetObservationLinks: Argument[Sharing.TargetObservationLinks] =
    InputObjectTargetObservationLinks.argument(
      "input",
      "Target/observation links"
    )

  /**
   * Returns a `State[Tables, ?]` program that will create a `Default` asterism
   * with the given target ids and associate it with the given observation.
   *
   * @param observationId observation that will contain the new asterism
   * @param targetIds targets referenced by the new asterism
   *
   * @return new `Default` asterism paired with the list of resultant events
   *         that should be published
   */
  private def createDefaultAsterism(
    observationId: Observation.Id,
    targetIds:     Set[Target.Id]
  ): State[Tables, (AsterismModel, List[Long => Event])] =
    for {
      i <- TableState.nextAsterismId
      a = AsterismModel.Default(i, Existence.Present, None, targetIds): AsterismModel
      _ <- Tables.asterisms.mod(_ + (i -> a))
      _ <- Tables.observation(observationId).mod(_.map(ObservationModel.asterismId.set(Some(i))))
      o <- TableState.requireObservation(observationId)
    } yield (a, List[Long => Event](AsterismEvent(Created, a), ObservationEvent(Updated, o)))

  // Produces a `State` computation that adds or removes targets from an
  // asterism
  private def updateAsterism(
    asterismId:  Asterism.Id,
    observation: ObservationModel,
    update:      Set[Target.Id] => Set[Target.Id]
  ): State[Tables, (AsterismModel, List[Long => Event])] =
    TableState.requireAsterism(asterismId).transform { (t, a) =>
      val newIds = update(a.targetIds)
      if (newIds == a.targetIds)
        (t, (a, List.empty[Long => Event]))
      else {
        val aʹ = AsterismModel.Default.asterismTargetIds.set(newIds)(a)
        (Tables.asterisms.modify(_.updated(asterismId, aʹ))(t),
          // For now, generate an observation event as well as an asterism update for the UI.
          // We will need to revisit how to do this in general.
         (aʹ, List[Long => Event](AsterismEvent(Updated, aʹ), ObservationEvent(Updated, observation)))
        )
      }
    }

  /**
   * Returns a `State[Tables, ?]` program that will update a `Default` asterism,
   * adding the given target ids.
   *
   * @param asterismId asterism to update (assuming it is a `Default` asterism
   * @param targetIds target ids to _add_
   *
   * @return updated `Default` asterism paired with the list of resultant events
   *         that should be published
   */
  private def addTargetsToAsterism(
    asterismId:  Asterism.Id,
    observation: ObservationModel,
    targetIds:   Set[Target.Id]
  ): State[Tables, (AsterismModel, List[Long => Event])] =
    updateAsterism(asterismId, observation, _ ++ targetIds)

  /**
   * Returns a `State[Tables, ?]` program that will update a `Default` asterism,
   * removing the given target ids.
   *
   * @param asterismId asterism to update (assuming it is a `Default` asterism
   * @param targetIds target ids to remove
   *
   * @return updated `Default` asterism paired with the list of resultant events
   *         that should be published
   */
  private def removeTargetsFromAsterism(
    asterismId:  Asterism.Id,
    observation: ObservationModel,
    targetIds:   Set[Target.Id]
  ): State[Tables, (AsterismModel, List[Long => Event])] =
    updateAsterism(asterismId, observation, _ -- targetIds)

  // Implements the bulk of a share/unshare field for targets and observations.
  // Takes a function that produces a `State` operation to apply to do the
  // tables.
  private def targetObservationShare[F[_]: Effect](
    c: Context[OdbRepo[F], Unit]
  )(
    f: (Set[Target.Id], List[ObservationModel]) => State[Tables, List[(AsterismModel, List[Long => Event])]]
  ): Future[List[AsterismModel]] = {

    val links   = c.arg(ArgumentTargetObservationLinks)

    val updates = c.ctx.tables.modify { t =>
      val targetIds  = links.targetIds.toSet
      val targetList = links.targetIds.traverse(LookupSupport.tryFindTarget(t, _))
      val obsList    = links.observationIds.traverse(LookupSupport.tryFindObservation(t, _))
      (targetList, obsList).mapN { (_, os) =>
        f(targetIds, os).run(t).value
      }.fold(err => (t, InputError.Exception(err).asLeft), _.map(_.asRight))
    }

    (for {
      us <- EitherT(updates).rethrowT
      (as, es) = us.unzip
      _  <- es.flatten.traverse(c.ctx.eventService.publish)
    } yield as).toIO.unsafeToFuture()
  }

  def shareTargetsWithObservations[F[_]: Effect]: Field[OdbRepo[F], Unit] =
    Field(
      name      = "shareTargetsWithObservations",
      fieldType = ListType(AsterismType[F]),
      arguments = List(ArgumentTargetObservationLinks),
      resolve   = c => targetObservationShare[F](c) { (targetIds, obsList) =>
        obsList.traverse { o =>
          o.asterismId.fold(createDefaultAsterism(o.id, targetIds))(addTargetsToAsterism(_, o, targetIds))
        }
      }
    )

  def unshareTargetsWithObservations[F[_]: Effect]: Field[OdbRepo[F], Unit] =
    Field(
      name      = "unshareTargetsWithObservations",
      fieldType = ListType(AsterismType[F]),
      arguments = List(ArgumentTargetObservationLinks),
      resolve   = c => targetObservationShare[F](c) { (targetIds, obsList) =>
        obsList.flatMap(o => o.asterismId.toList.tupleRight(o)).traverse { case (a, o) =>
          removeTargetsFromAsterism(a, o, targetIds)
        }
      }
    )

  def allFields[F[_]: Effect]: List[Field[OdbRepo[F], Unit]] =
    List(
      shareTargetsWithObservations,
      unshareTargetsWithObservations
    )

}

object SharingMutation extends SharingMutation