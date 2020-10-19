// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.repo

import lucuma.odb.api.model.{AsterismModel, ProgramModel, TargetModel}
import lucuma.odb.api.model.AsterismModel.{AsterismCreatedEvent, AsterismEditedEvent, AsterismProgramLinks, Create}
import lucuma.odb.api.model.syntax.validatedinput._
import cats._
import cats.data.State
import cats.effect.concurrent.Ref
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

sealed trait AsterismRepo[F[_]] extends TopLevelRepo[F, AsterismModel.Id, AsterismModel] {

  def selectAllForProgram(pid: ProgramModel.Id, includeDeleted: Boolean = false): F[List[AsterismModel]]

  def selectAllForTarget(tid: TargetModel.Id, includeDeleted: Boolean = false): F[List[AsterismModel]]

  def insert[T <: AsterismModel](input: AsterismModel.Create[T]): F[T]

  def shareWithPrograms(input: AsterismProgramLinks): F[AsterismModel]

  def unshareWithPrograms(input: AsterismProgramLinks): F[AsterismModel]

}

object AsterismRepo {

  def create[F[_]: Monad](
    tablesRef:     Ref[F, Tables],
    eventService:  EventService[F]
  )(implicit M: MonadError[F, Throwable]): AsterismRepo[F] =

    new TopLevelRepoBase[F, AsterismModel.Id, AsterismModel](
      tablesRef,
      eventService,
      Tables.lastAsterismId,
      Tables.asterisms,
      AsterismCreatedEvent.apply,
      AsterismEditedEvent.apply
    ) with AsterismRepo[F]
      with LookupSupport[F] {

      override def selectAllForProgram(pid: ProgramModel.Id, includeDeleted: Boolean): F[List[AsterismModel]] =
        tablesRef.get.map { t =>
          val ids = t.observations.values.filter(_.pid === pid).flatMap(_.asterism.toList).toSet
          ids.foldLeft(List.empty[AsterismModel]) { (l, i) =>
            t.asterisms.get(i).fold(l)(_ :: l)
          }
        }.map(deletionFilter(includeDeleted))

      override def selectAllForTarget(tid: TargetModel.Id, includeDeleted: Boolean): F[List[AsterismModel]] =
        tablesRef.get.map { t =>
          t.asterisms.values.filter(_.targets(tid)).toList
        }.map(deletionFilter(includeDeleted))

      private def addAsterism[T <: AsterismModel](
        programs: Set[ProgramModel.Id],
        factory:  AsterismModel.Id => T
      ): State[Tables, T] =
        for {
          a   <- createAndInsert(factory)
          _   <- Tables.shareAsterismWithPrograms(a, programs)
        } yield a

      override def insert[T <: AsterismModel](input: Create[T]): F[T] =
        modify { t =>
          val targets  = input.targets.iterator.toList.traverse(lookupTarget(t, _))
          val programs = input.programs.traverse(lookupProgram(t, _))
          val asterism = input.withId
          (targets, programs, asterism)
            .mapN((_, _, f) => addAsterism(input.programs.toSet, f).run(t).value)
            .fold(
              err => (t, err.asLeft[T]),
              tup => tup.map(_.asRight)
            )
        }

      private def programSharing(
        input: AsterismProgramLinks,
        f:     (AsterismModel, Set[ProgramModel.Id]) => State[Tables, Unit]
      ): F[AsterismModel] =
        tablesRef.modifyState {
          for {
            a  <- inspectAsterismId(input.id)
            ps <- input.programs.traverse(inspectProgramId).map(_.sequence)
            r  <- (a, ps).traverseN { (am, _) => f(am, input.programs.toSet).as(am) }
          } yield r
        }.flatMap(_.liftTo[F])

      override def shareWithPrograms(input: AsterismProgramLinks): F[AsterismModel] =
        programSharing(input, Tables.shareAsterismWithPrograms)

      override def unshareWithPrograms(input: AsterismProgramLinks): F[AsterismModel] =
        programSharing(input, Tables.unshareAsterismWithPrograms)

    }
}
